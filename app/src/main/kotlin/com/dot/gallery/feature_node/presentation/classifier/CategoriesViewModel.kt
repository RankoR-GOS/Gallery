package com.dot.gallery.feature_node.presentation.classifier

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo.State
import androidx.work.WorkManager
import com.dot.gallery.core.MediaDistributor
import com.dot.gallery.core.ml.ModelManager
import com.dot.gallery.core.ml.ModelStatus
import com.dot.gallery.core.workers.CategoryWorker
import com.dot.gallery.core.workers.startClassification
import com.dot.gallery.core.workers.stopClassification
import com.dot.gallery.feature_node.data.data_source.CategoryWithMediaCount
import com.dot.gallery.feature_node.data.model.Category
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.domain.model.MediaMetadataState
import com.dot.gallery.feature_node.domain.model.MediaState
import com.dot.gallery.feature_node.data.repository.MediaRepository
import com.dot.gallery.feature_node.domain.use_case.AiMediaAnalysis
import com.dot.gallery.feature_node.domain.use_case.AiMediaAnalysisSettings
import com.dot.gallery.feature_node.presentation.util.update
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Categories feature.
 * Manages both the legacy classification system and the new embedding-based category system.
 */
@HiltViewModel
class CategoriesViewModel @Inject internal constructor(
    private val repository: MediaRepository,
    private val distributor: MediaDistributor,
    private val workManager: WorkManager,
    private val aiMediaAnalysis: AiMediaAnalysis,
    private val modelManager: ModelManager
) : ViewModel() {

    val modelStatus: StateFlow<ModelStatus> = modelManager.status

    internal val analysisSettings: StateFlow<AiMediaAnalysisSettings> = aiMediaAnalysis.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AiMediaAnalysisSettings(
            analysisEnabled = false,
            categoryClassificationEnabled = true,
        ),
    )

    // ============ New Category System ============
    
    /**
     * Flow of all categories with their media counts
     */
    val categoriesWithCount: StateFlow<List<CategoryWithMediaCount>> = repository.getCategoriesWithMediaCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Flow of all categories (including empty ones)
     */
    val allCategories: StateFlow<List<Category>> = repository.getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Top categories for carousel display
     */
    val topCategories: StateFlow<List<CategoryWithMediaCount>> = repository.getTopCategories(10)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Worker state for the new category classification
     */
    val isCategoryWorkerRunning: StateFlow<Boolean> = aiMediaAnalysis.workState
        .map { workState ->
            workState.isCategoryActive
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val categoryWorkerProgress: StateFlow<Float> = aiMediaAnalysis.workState
        .map { workState ->
            workState.categoryProgress
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)

    val categoryWorkerStatus: StateFlow<String> = aiMediaAnalysis.workState
        .map { workState ->
            workState.categoryStatus
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    // ============ Legacy Classification System (kept for backward compatibility) ============
    
    val classifiedCategories = repository.getClassifiedCategories()
        .map { if (it.isNotEmpty()) it.distinct() else it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val mostPopularCategory = repository.getClassifiedMediaByMostPopularCategory()
        .map { it.groupBy { it.category!! }.toSortedMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyMap())

    val categoriesWithMedia = repository.getCategoriesWithMedia()
        .map { it.sortedBy { it.category!! } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val classifiedMediaCount = repository.getClassifiedMediaCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0)

    val isRunning = workManager.getWorkInfosByTagFlow("ImageClassifier")
        .map { it.lastOrNull()?.state == State.RUNNING }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    val progress = workManager.getWorkInfosByTagFlow("ImageClassifier")
        .map {
            it.lastOrNull()?.progress?.getFloat("progress", 0f) ?: 0f
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0f)

    // ============ UI State ============
    
    val selectionState = mutableStateOf(false)
    val selectedMedia = mutableStateListOf<Media.ClassifiedMedia>()

    private val _editingCategory = MutableStateFlow<Category?>(null)
    val editingCategory = _editingCategory.asStateFlow()

    val metadataState = distributor.metadataFlow.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        MediaMetadataState()
    )

    // ============ Category Actions ============

    /**
     * Start the new embedding-based category classification
     */
    fun startCategoryClassification() {
        viewModelScope.launch(Dispatchers.IO) {
            // Initialize default categories if needed
            repository.initializeDefaultCategories()
            // Start the worker
            aiMediaAnalysis.requestCategoryClassification()
        }
    }

    fun setCategoryClassificationEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            aiMediaAnalysis.setCategoryClassificationEnabled(enabled = enabled)
        }
    }

    /**
     * Stop the category classification worker
     */
    fun stopCategoryClassification() {
        viewModelScope.launch(Dispatchers.IO) {
            aiMediaAnalysis.cancelCategoryClassification()
        }
    }

    /**
     * Create a new user-defined category
     */
    fun createCategory(name: String, searchTerms: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val category = Category(
                name = name,
                searchTerms = searchTerms,
                isUserCreated = true
            )
            repository.createCategory(category)
            // Trigger reclassification to include the new category
            aiMediaAnalysis.requestCategoryClassification()
        }
    }

    /**
     * Update a category's settings
     */
    fun updateCategory(category: Category) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateCategory(category.copy(
                updatedAt = System.currentTimeMillis(),
                embedding = null // Clear embedding so it gets regenerated
            ))
            // Trigger reclassification
            aiMediaAnalysis.requestCategoryClassification()
        }
    }

    /**
     * Update category threshold
     */
    fun updateCategoryThreshold(categoryId: Long, threshold: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateCategoryThreshold(categoryId, threshold.coerceIn(Category.MIN_THRESHOLD, Category.MAX_THRESHOLD))
            // Trigger reclassification
            aiMediaAnalysis.requestCategoryClassification()
        }
    }

    /**
     * Update category name
     */
    fun updateCategoryName(categoryId: Long, name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateCategoryName(categoryId, name)
        }
    }

    /**
     * Toggle category pinned status
     */
    fun toggleCategoryPinned(categoryId: Long, isPinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.toggleCategoryPinned(categoryId, isPinned)
        }
    }

    /**
     * Delete a category
     */
    fun deleteCategory(categoryId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteCategory(categoryId)
        }
    }

    /**
     * Manually add media to a category
     */
    fun addMediaToCategory(mediaId: Long, categoryId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addMediaToCategory(mediaId, categoryId, similarity = 1f, isManual = true)
        }
    }

    /**
     * Remove media from a category
     */
    fun removeMediaFromCategory(mediaId: Long, categoryId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeMediaFromCategory(mediaId, categoryId)
        }
    }

    /**
     * Reset all category data and reinitialize with defaults
     */
    fun resetCategories() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.resetCategoryData()
            repository.initializeDefaultCategories()
            aiMediaAnalysis.requestCategoryClassification()
        }
    }

    // ============ Dialog State ============

    fun showEditCategoryDialog(category: Category) {
        _editingCategory.value = category
    }

    fun hideEditCategoryDialog() {
        _editingCategory.value = null
    }

    // ============ Legacy Actions ============

    fun toggleSelection(mediaState: MediaState<Media.ClassifiedMedia>, index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = mediaState.media[index]
            val selectedPhoto = selectedMedia.find { it.id == item.id }
            if (selectedPhoto != null) {
                selectedMedia.remove(selectedPhoto)
            } else {
                selectedMedia.add(item)
            }
            selectionState.update(selectedMedia.isNotEmpty())
        }
    }

    /**
     * Start the category classification using the new CLIP-based system.
     * This replaces the old ONNX-based classification.
     */
    fun startClassification() {
        // Use the new CLIP-based category classification
        viewModelScope.launch(Dispatchers.IO) {
            aiMediaAnalysis.requestCategoryClassification()
        }
    }

    fun deleteClassifications() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteClassifications()
        }
    }

    /**
     * Stop the category classification
     */
    fun stopClassification() {
        // Stop both the old and new systems for safety
        viewModelScope.launch(Dispatchers.IO) {
            aiMediaAnalysis.cancelCategoryClassification()
        }
        workManager.stopClassification()
    }

}
