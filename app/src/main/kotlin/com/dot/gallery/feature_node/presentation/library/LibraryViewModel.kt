package com.dot.gallery.feature_node.presentation.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dot.gallery.core.MediaDistributor
import com.dot.gallery.core.Resource
import com.dot.gallery.core.ml.ModelManager
import com.dot.gallery.core.ml.ModelStatus
import com.dot.gallery.core.util.SdkCompat
import com.dot.gallery.feature_node.data.model.CategoryWithMediaCount
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.repository.MediaRepository
import com.dot.gallery.feature_node.data.util.MediaOrder
import com.dot.gallery.feature_node.domain.model.LibraryIndicatorState
import com.dot.gallery.feature_node.domain.use_case.AiMediaAnalysis
import com.dot.gallery.feature_node.domain.use_case.AiMediaAnalysisSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Data class for category with its thumbnail media
 */
data class CategoryMedia(
    val category: CategoryWithMediaCount,
    val thumbnailMedia: Media.UriMedia?
)

@HiltViewModel
class LibraryViewModel @Inject internal constructor(
    private val repository: MediaRepository,
    private val mediaDistributor: MediaDistributor,
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

    val indicatorState = combine(
        if (SdkCompat.supportsTrash) repository.getTrashed() else flowOf(Resource.Success(emptyList())),
        if (SdkCompat.supportsFavorites) repository.getFavorites(MediaOrder.Default) else flowOf(Resource.Success(emptyList()))
    ) { trashed, favorites ->
        LibraryIndicatorState(
            trashCount = trashed.data?.size ?: 0,
            favoriteCount = favorites.data?.size ?: 0
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), LibraryIndicatorState())

    // New category system - top categories for library display with thumbnails
    private val topCategoriesRaw = repository.getTopCategories(5)
    
    val topCategories = combine(
        topCategoriesRaw,
        mediaDistributor.timelineMediaFlow
    ) { categories, mediaState ->
        val mediaMap = mediaState.media.associateBy { it.id }
        categories.map { category ->
            CategoryMedia(
                category = category,
                thumbnailMedia = category.thumbnailMediaId?.let { mediaMap[it] }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())
    
    // Total count of categories with media (for the "See all" indicator)
    val totalCategoryCount = repository.getCategoryCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0)

    // Legacy classification system (for backwards compatibility)
    val classifiedCategories = repository.getClassifiedCategories()
        .map { if (it.isNotEmpty()) it.distinct() else it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    val mostPopularCategory = repository.getClassifiedMediaByMostPopularCategory()
        .map { it.groupBy { it.category!! }.toSortedMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyMap())

}
