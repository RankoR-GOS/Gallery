package com.dot.gallery.feature_node.presentation.edit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.core.graphics.scale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.FutureTarget
import com.dot.gallery.core.MediaHandler
import com.dot.gallery.feature_node.data.model.Media
import com.dot.gallery.feature_node.data.model.Media.UriMedia
import com.dot.gallery.feature_node.data.model.editor.SaveFormat
import com.dot.gallery.feature_node.data.repository.MediaRepository
import com.dot.gallery.feature_node.domain.model.editor.Adjustment
import com.dot.gallery.feature_node.domain.model.editor.DrawMode
import com.dot.gallery.feature_node.domain.model.editor.DrawType
import com.dot.gallery.feature_node.domain.model.editor.ImageFilter
import com.dot.gallery.feature_node.domain.model.editor.PathProperties
import com.dot.gallery.feature_node.domain.model.editor.SuggestionPreset
import com.dot.gallery.feature_node.domain.model.editor.VariableFilter
import com.dot.gallery.feature_node.presentation.edit.adjustments.Flip
import com.dot.gallery.feature_node.presentation.edit.adjustments.Markup
import com.dot.gallery.feature_node.presentation.edit.adjustments.Rotate90CW
import com.dot.gallery.feature_node.presentation.edit.adjustments.varfilter.Denoise
import com.dot.gallery.feature_node.presentation.edit.adjustments.varfilter.Rotate
import com.dot.gallery.feature_node.presentation.edit.adjustments.varfilter.Sharpness
import com.dot.gallery.feature_node.presentation.edit.adjustments.varfilter.VariableFilterTypes
import com.dot.gallery.feature_node.presentation.edit.adjustments.varfilter.Vignette
import com.dot.gallery.feature_node.presentation.util.applyColorMatrix
import com.dot.gallery.feature_node.presentation.util.overlayBitmaps
import com.dot.gallery.feature_node.presentation.util.printDebug
import com.dot.gallery.feature_node.presentation.util.printError
import com.dot.gallery.feature_node.presentation.util.toGlideModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.math.abs
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@HiltViewModel
class EditViewModel @Inject constructor(
    private val repository: MediaRepository,
    private val mediaHandler: MediaHandler,
) : ViewModel() {

    private val _originalBitmap = MutableStateFlow<Bitmap?>(null)
    val originalBitmap = _originalBitmap.asStateFlow()

    private val _targetBitmap = MutableStateFlow(originalBitmap.value)
    val targetBitmap = _targetBitmap.asStateFlow()

    private val _previewMatrix = MutableStateFlow<ColorMatrix?>(null)
    val previewMatrix = _previewMatrix.asStateFlow()

    private val _previewRotation = MutableStateFlow(0f)
    val previewRotation = _previewRotation.asStateFlow()

    private val _previewRotation90 = MutableStateFlow(0f)
    val previewRotation90 = _previewRotation90.asStateFlow()

    private val _previewFlipH = MutableStateFlow(false)
    val previewFlipH = _previewFlipH.asStateFlow()

    private val bitmaps = mutableStateListOf<Pair<Bitmap?, Adjustment?>>()

    private val _currentBitmap = MutableStateFlow<Bitmap?>(null)
    val currentBitmap = _currentBitmap.asStateFlow()

    private val _appliedAdjustments = MutableStateFlow<List<Adjustment>>(emptyList())
    val appliedAdjustments = _appliedAdjustments.asStateFlow()

    private val activeMedia = MutableStateFlow<UriMedia?>(null)

    private val _loadFailed = MutableStateFlow(false)
    val loadFailed = _loadFailed.asStateFlow()

    private val _isSaving = MutableStateFlow(true)
    val isSaving = _isSaving.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    private val _uri = MutableStateFlow<Uri?>(null)
    val uri = _uri.asStateFlow()

    private val _paths = MutableStateFlow<List<Pair<Path, PathProperties>>>(emptyList())
    val paths = _paths.asStateFlow()

    private val _pathsUndone = MutableStateFlow<List<Pair<Path, PathProperties>>>(emptyList())
    val pathsUndone = _pathsUndone.asStateFlow()

    private val _currentPosition = MutableStateFlow(Offset.Unspecified)
    val currentPosition = _currentPosition.asStateFlow()

    private val _previousPosition = MutableStateFlow(Offset.Unspecified)
    val previousPosition = _previousPosition.asStateFlow()

    private val _drawMode = MutableStateFlow(DrawMode.Draw)
    val drawMode = _drawMode.asStateFlow()

    private val _drawType = MutableStateFlow(DrawType.Stylus)
    val drawType = _drawType.asStateFlow()

    private val _currentPath = MutableStateFlow(Path())
    val currentPath = _currentPath.asStateFlow()

    private val _currentPathProperty = MutableStateFlow(PathProperties())
    val currentPathProperty = _currentPathProperty.asStateFlow()

    private val _selectedPreset = MutableStateFlow<SuggestionPreset?>(null)
    val selectedPreset = _selectedPreset.asStateFlow()

    private val redoStack = mutableStateListOf<Pair<Bitmap?, Adjustment?>>()
    private val _redoAdjustments = MutableStateFlow<List<Adjustment>>(emptyList())

    private val _canUndo = MutableStateFlow(false)
    val canUndo = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo = _canRedo.asStateFlow()

    private val _filterIntensity = MutableStateFlow(1f)
    val filterIntensity = _filterIntensity.asStateFlow()

    private val _previewVignette = MutableStateFlow(0f)
    val previewVignette = _previewVignette.asStateFlow()

    private val _previewBlur = MutableStateFlow(0f)
    val previewBlur = _previewBlur.asStateFlow()

    private val _previewSharpness = MutableStateFlow(0f)
    val previewSharpness = _previewSharpness.asStateFlow()

    private val _activeFilterFlow = MutableStateFlow<ImageFilter?>(null)
    val activeFilter = _activeFilterFlow.asStateFlow()
    private var _activeFilter: ImageFilter?
        get() = _activeFilterFlow.value
        set(value) { _activeFilterFlow.value = value }
    private var _previewJob: Job? = null
    private var _intensityJob: Job? = null

    private fun updateUndoRedoState() {
        _canUndo.value = _appliedAdjustments.value.isNotEmpty()
        _canRedo.value = _redoAdjustments.value.isNotEmpty()
    }

    private fun Adjustment.isMatrixBased(): Boolean = when (this) {
        is VariableFilter -> colorMatrix() != null
        is ImageFilter -> colorMatrix() != null
        else -> false
    }

    private fun Adjustment.getColorMatrix(): ColorMatrix? = when (this) {
        is VariableFilter -> colorMatrix()
        is ImageFilter -> colorMatrix()
        else -> null
    }

    /** Find the last real bitmap in the bitmaps stack */
    private fun lastRealBitmap(): Bitmap? =
        bitmaps.lastOrNull { it.first != null }?.first

    /** Recompute the composed matrix from all trailing matrix-only entries */
    private fun recomputeComposedMatrix() {
        val trailing = bitmaps.takeLastWhile { it.first == null }
        if (trailing.isEmpty()) {
            _previewMatrix.value = null
            return
        }
        val composed = identityColorMatrix()
        for ((_, adj) in trailing) {
            adj?.getColorMatrix()?.let { composed.timesAssign(it) }
        }
        _previewMatrix.value = composed
    }

    /** Get a temporary bitmap with composed matrix applied (for previews, doesn't modify state) */
    private fun bitmapWithComposedMatrix(): Bitmap? {
        val base = lastRealBitmap() ?: return null
        val matrix = _previewMatrix.value ?: return base
        return applyColorMatrix(base, matrix.values)
    }

    /** Bake the composed matrix into a real bitmap checkpoint */
    private suspend fun flattenComposedMatrix() {
        val matrix = _previewMatrix.value ?: return
        val base = lastRealBitmap() ?: return
        val trailing = bitmaps.takeLastWhile { it.first == null }
        if (trailing.isEmpty()) return
        val flattened = applyColorMatrix(base, matrix.values)
        // Remove all trailing null-bitmap entries and replace with one real bitmap
        repeat(trailing.size) { bitmaps.removeAt(bitmaps.lastIndex) }
        bitmaps.add(flattened to null) // null adjustment = flatten checkpoint
        _currentBitmap.value = flattened
        _targetBitmap.value = flattened
        // Clear preview on Main so the UI renders the flattened bitmap first
        withContext(Dispatchers.Main) {
            _previewMatrix.value = null
        }
    }

    /**
     * Pick the best save format based on the source image's MIME type.
     * JPEG is ~5-10x faster to compress than PNG with negligible quality loss for photos.
     */
    private fun bestSaveFormat(): SaveFormat {
        val mime = activeMedia.value?.mimeType?.lowercase()
        return when {
            mime?.contains("png") == true -> SaveFormat.Png
            mime?.contains("webp") == true -> SaveFormat.WebpLossy
            else -> SaveFormat.Jpeg // JPEG is the fast default for photos
        }
    }

    private fun clearRedoStack() {
        redoStack.clear()
        _redoAdjustments.value = emptyList()
        updateUndoRedoState()
    }

    val mutex = Mutex()

    fun addPath(path: Path, properties: PathProperties) {
        _paths.value += path to properties
    }

    fun clearPathsUndone() {
        _pathsUndone.value = emptyList()
    }

    fun setCurrentPosition(offset: Offset) {
        _currentPosition.value = offset
    }

    fun setPreviousPosition(offset: Offset) {
        _previousPosition.value = offset
    }

    fun setDrawMode(mode: DrawMode) {
        setCurrentPathProperty(
            _currentPathProperty.value.copy(
                eraseMode = mode == DrawMode.Erase
            )
        )
        _drawMode.value = mode
    }

    fun setDrawType(type: DrawType) {
        when (type) {
            DrawType.Stylus -> {
                setCurrentPathProperty(
                    _currentPathProperty.value.copy(
                        strokeWidth = 20f,
                        color = _currentPathProperty.value.color.copy(alpha = 1f),
                        strokeCap = StrokeCap.Round
                    )
                )
            }

            DrawType.Highlighter -> {
                setCurrentPathProperty(
                    _currentPathProperty.value.copy(
                        strokeWidth = 30f,
                        color = _currentPathProperty.value.color.copy(alpha = 0.4f),
                        strokeCap = StrokeCap.Square
                    )
                )
            }

            DrawType.Marker -> {
                setCurrentPathProperty(
                    _currentPathProperty.value.copy(
                        strokeWidth = 40f,
                        color = _currentPathProperty.value.color.copy(alpha = 1f),
                        strokeCap = StrokeCap.Round
                    )
                )
            }
        }
        _drawType.value = type
    }

    fun setCurrentPath(path: Path) {
        _currentPath.value = path
    }

    fun setCurrentPathProperty(properties: PathProperties) {
        _currentPathProperty.value = properties
    }

    fun setSelectedPreset(preset: SuggestionPreset?) {
        _selectedPreset.value = preset
        if (preset != null) {
            // Compose preset preview with existing stacked matrix adjustments
            val trailing = bitmaps.toList().takeLastWhile { it.first == null }
            val composed = identityColorMatrix()
            for ((_, adj) in trailing) {
                adj?.getColorMatrix()?.let { composed.timesAssign(it) }
            }
            composed.timesAssign(preset.colorMatrix())
            _previewMatrix.value = composed
        } else {
            recomputeComposedMatrix()
        }
    }

    fun undoLastPath() {
        val paths = _paths.value
        if (paths.isNotEmpty()) {
            val lastPath = paths.last()
            _paths.value = paths.dropLast(1)
            _pathsUndone.value += lastPath
        }
    }

    fun redoLastPath() {
        val pathsUndone = _pathsUndone.value
        if (pathsUndone.isNotEmpty()) {
            val lastPath = pathsUndone.last()
            _pathsUndone.value = pathsUndone.dropLast(1)
            _paths.value += lastPath
        }
    }

    fun clearDrawingBoard() {
        _paths.value = emptyList()
        _pathsUndone.value = emptyList()
        _currentPath.value = Path()
        _currentPathProperty.value = PathProperties()
        _drawMode.value = DrawMode.Draw
    }

    fun setSourceData(context: Context, uri: Uri) {
        if (_uri.value == uri && _currentBitmap.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            _uri.value = uri
            _loadFailed.value = false
            _isSaving.value = true
            val requestManager = Glide.with(context.applicationContext)
            var request: FutureTarget<Bitmap>? = null
            try {
                val mediaList = repository.getMediaListByUris(
                    listOfUris = listOf(uri),
                    reviewMode = false,
                    onlyMatching = true,
                ).firstOrNull()?.data.orEmpty()
                activeMedia.value = mediaList.firstOrNull() ?: Media.createFromUri(context = context, uri = uri)
                request = requestManager.asBitmap()
                    .load(activeMedia.value?.toGlideModel())
                    .skipMemoryCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.NONE)
                    .submit()
                val bitmap = requireNotNull(request.get().copy(Bitmap.Config.ARGB_8888, false))
                _originalBitmap.value = bitmap
                _targetBitmap.value = bitmap
                _currentBitmap.value = bitmap
                bitmaps.add(0, bitmap to null)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                _loadFailed.value = true
            } finally {
                request?.let { target -> requestManager.clear(target) }
                _isSaving.value = false
            }
        }
    }

    fun removeLast() {
        viewModelScope.launch(Dispatchers.IO) {
            val adjustments = _appliedAdjustments.value
            if (adjustments.isNotEmpty()) {
                val removedAdj = adjustments.last()
                _appliedAdjustments.value = adjustments.dropLast(1)

                // Push to redo stack
                if (bitmaps.isNotEmpty()) {
                    val removedEntry = bitmaps.last()
                    redoStack.add(removedEntry)
                    _redoAdjustments.value = _redoAdjustments.value + removedAdj
                    bitmaps.removeAt(bitmaps.lastIndex)
                }

                // Update current bitmap to last real bitmap
                _currentBitmap.value = lastRealBitmap()
                _targetBitmap.value = _currentBitmap.value
                _previewMatrix.value = null

                // If we undid a filter, check if there's a previous filter underneath
                if (removedAdj is ImageFilter) {
                    val prevFilter = _appliedAdjustments.value
                        .filterIsInstance<ImageFilter>()
                        .lastOrNull()
                    _activeFilter = if (prevFilter != null && prevFilter.name != "None") prevFilter else null
                    _filterIntensity.value = 1f
                }

                updateUndoRedoState()
            }
        }
    }

    fun redoLast() {
        viewModelScope.launch(Dispatchers.IO) {
            val redoAdjs = _redoAdjustments.value
            if (redoAdjs.isNotEmpty() && redoStack.isNotEmpty()) {
                val restoredAdj = redoAdjs.last()
                val restoredEntry = redoStack.last()
                _redoAdjustments.value = redoAdjs.dropLast(1)
                redoStack.removeAt(redoStack.lastIndex)

                _appliedAdjustments.value = _appliedAdjustments.value + restoredAdj
                bitmaps.add(restoredEntry)
                _currentBitmap.value = lastRealBitmap()
                _targetBitmap.value = _currentBitmap.value
                _previewMatrix.value = null
                updateUndoRedoState()
            }
        }
    }

    fun setFilterIntensity(intensity: Float) {
        val clamped = intensity.coerceIn(0f, 1f)
        _filterIntensity.value = clamped
        val filter = _activeFilter ?: return

        // GPU-only preview — commitFilter() bakes when leaving Filters section
        val baseBitmap = bitmaps.toList()
            .filter { it.second !is ImageFilter }
            .lastOrNull()?.first ?: return

        if (clamped <= 0f) {
            _currentBitmap.value = baseBitmap
            _previewMatrix.value = null
        } else {
            val blendedMatrix = lerpColorMatrix(identityColorMatrix(), filter.colorMatrix(), clamped)
            _currentBitmap.value = baseBitmap
            _previewMatrix.value = blendedMatrix
        }
    }

    private fun identityColorMatrix(): ColorMatrix = ColorMatrix(floatArrayOf(
        1f, 0f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    ))

    private fun lerpColorMatrix(from: ColorMatrix, to: ColorMatrix?, t: Float): ColorMatrix {
        if (to == null) return from
        val result = FloatArray(20)
        for (i in 0 until 20) {
            result[i] = from.values[i] + t * (to.values[i] - from.values[i])
        }
        return ColorMatrix(result)
    }

    fun removeKind(variableFilterTypes: VariableFilterTypes) {
        viewModelScope.launch(Dispatchers.IO) {
            val filters = _appliedAdjustments.value.toMutableList()
            filters.removeAll { it.name.equals(variableFilterTypes.name, ignoreCase = true) }
            bitmaps.removeAll { it.second?.name.equals(variableFilterTypes.name, ignoreCase = true) }
            _appliedAdjustments.value = filters
            _currentBitmap.value = lastRealBitmap()
            _targetBitmap.value = _currentBitmap.value
            _previewMatrix.value = null
        }
    }

    fun applyAdjustment(adjustment: Adjustment) {
        viewModelScope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    applyAdjustmentAndWait(adjustment = adjustment)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    printError("Failed to apply adjustment: ${exception.message}")
                }
            }
        }
    }

    // Caller owns mutex so markup can commit before clearing its drawing state.
    private suspend fun applyAdjustmentAndWait(adjustment: Adjustment) {
        val currentBitmap = checkNotNull(_currentBitmap.value) { "Current bitmap is null" }
        _isProcessing.value = true
        try {
            val adjustmentsWithout = _appliedAdjustments.value.filterNot { previous ->
                when (adjustment) {
                    is VariableFilter -> previous.name.equals(adjustment.name, ignoreCase = true)
                    is ImageFilter -> previous is ImageFilter
                    else -> false
                }
            }
            val bitmapsWithout = bitmaps.filterNot { (_, previous) ->
                when (adjustment) {
                    is VariableFilter -> previous?.name.equals(adjustment.name, ignoreCase = true)
                    is ImageFilter -> previous is ImageFilter
                    else -> false
                }
            }
            val baseBitmap = bitmapsWithout.lastOrNull { it.first != null }?.first
                ?: _originalBitmap.value ?: currentBitmap
            val isDefault = adjustment is VariableFilter &&
                abs(adjustment.value - adjustment.defaultValue) < 1e-4f
            val newBitmap = when {
                isDefault -> baseBitmap
                else -> adjustment.apply(baseBitmap)
            }
            val hasChange = !isDefault && !newBitmap.sameAs(baseBitmap)
            // Publish history only after the adjustment succeeds.
            clearRedoStack()
            bitmaps.clear()
            bitmaps.addAll(bitmapsWithout)
            if (hasChange) bitmaps.add(newBitmap to adjustment)
            _appliedAdjustments.value = when {
                hasChange -> adjustmentsWithout + adjustment
                else -> adjustmentsWithout
            }
            withContext(Dispatchers.Main) {
                _currentBitmap.value = when {
                    hasChange -> newBitmap
                    else -> baseBitmap
                }
                _targetBitmap.value = _currentBitmap.value
                _previewMatrix.value = null
                if (adjustment is Rotate) _previewRotation.value = 0f
                if (adjustment is Rotate90CW) _previewRotation90.value = 0f
                if (adjustment is Flip) _previewFlipH.value = false
                clearGpuPreviewEffects()
            }
            updateUndoRedoState()
        } finally {
            _isProcessing.value = false
        }
    }

    fun applyRotate90() {
        // Instant GPU preview, then bake
        _previewRotation90.value += 90f
        applyAdjustment(Rotate90CW(90f))
    }

    fun applyFlipH() {
        // Instant GPU preview, then bake
        _previewFlipH.value = !_previewFlipH.value
        applyAdjustment(Flip(horizontal = true))
    }

    fun applyDrawing(graphicsImage: Bitmap, onFinish: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val applied = mutex.withLock {
                try {
                    flattenComposedMatrix()
                    val currentImage = checkNotNull(lastRealBitmap()) { "Current bitmap is null" }
                    val finalBitmap = overlayBitmaps(
                        currentImage,
                        graphicsImage.scale(currentImage.width, currentImage.height),
                    )
                    applyAdjustmentAndWait(adjustment = Markup(newBitmap = finalBitmap))
                    clearDrawingBoard()
                    true
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    printError("Failed to apply markup: ${exception.message}")
                    false
                }
            }
            withContext(Dispatchers.Main) { onFinish(applied) }
        }
    }

    fun toggleFilter(filter: ImageFilter) {
        _intensityJob?.cancel()
        // GPU-only preview — bitmap is baked later by commitFilter()
        val baseBitmap = bitmaps.toList()
            .filter { it.second !is ImageFilter }
            .lastOrNull()?.first ?: return

        if (filter.name != "None") {
            _activeFilter = filter
            _filterIntensity.value = 1f
            // Show base + GPU color matrix overlay
            _currentBitmap.value = baseBitmap
            _previewMatrix.value = filter.colorMatrix()
            clearGpuPreviewEffects()
        } else {
            _activeFilter = null
            _filterIntensity.value = 1f
            _currentBitmap.value = baseBitmap
            _previewMatrix.value = null
        }
    }

    /**
     * Bake the currently previewed filter into a real bitmap.
     * Called when navigating away from the Filters section.
     */
    fun commitFilter() {
        val filter = _activeFilter
        viewModelScope.launch(Dispatchers.IO) {
            _isProcessing.value = true
            _intensityJob?.cancel()

            val baseBitmap = bitmaps.toList()
                .filter { it.second !is ImageFilter }
                .lastOrNull()?.first ?: run { _isProcessing.value = false; return@launch }

            val intensity = _filterIntensity.value

            if (filter != null && filter.name != "None") {
                val matrix = if (intensity < 1f) {
                    lerpColorMatrix(identityColorMatrix(), filter.colorMatrix(), intensity)
                } else {
                    filter.colorMatrix()
                }

                val newBitmap = if (matrix != null) {
                    applyColorMatrix(baseBitmap, matrix.values)
                } else {
                    filter.apply(baseBitmap)
                }
                _currentBitmap.value = newBitmap
                bitmaps.add(newBitmap to filter)
                _appliedAdjustments.value = _appliedAdjustments.value + filter
            } else if (_previewMatrix.value != null) {
                // Had a preview but switched to None — nothing to bake
                _currentBitmap.value = baseBitmap
            }

            // Clear preview on Main so the UI renders the new bitmap first
            withContext(Dispatchers.Main) {
                _previewMatrix.value = null
            }
            clearRedoStack()
            updateUndoRedoState()
            _isProcessing.value = false
        }
    }

    private fun clearGpuPreviewEffects() {
        _previewVignette.value = 0f
        _previewBlur.value = 0f
        _previewSharpness.value = 0f
    }

    fun previewAdjustment(adjustment: Adjustment) {
        _previewJob?.cancel()
        when {
            adjustment is Vignette -> {
                // Show base bitmap without existing vignette, then overlay GPU preview
                val baseBitmap = bitmaps.toList()
                    .filter { !it.second?.name.equals(adjustment.name, ignoreCase = true) }
                    .lastOrNull()?.first
                _currentBitmap.value = baseBitmap ?: lastRealBitmap()
                _previewVignette.value = adjustment.value
                _previewBlur.value = 0f
                _previewSharpness.value = 0f
            }
            adjustment is Denoise -> {
                val baseBitmap = bitmaps.toList()
                    .filter { !it.second?.name.equals(adjustment.name, ignoreCase = true) }
                    .lastOrNull()?.first
                _currentBitmap.value = baseBitmap ?: lastRealBitmap()
                _previewBlur.value = adjustment.value
                _previewVignette.value = 0f
                _previewSharpness.value = 0f
            }
            adjustment is Sharpness -> {
                val baseBitmap = bitmaps.toList()
                    .filter { !it.second?.name.equals(adjustment.name, ignoreCase = true) }
                    .lastOrNull()?.first
                _currentBitmap.value = baseBitmap ?: lastRealBitmap()
                _previewSharpness.value = adjustment.value
                _previewVignette.value = 0f
                _previewBlur.value = 0f
            }
            adjustment is Rotate -> {
                _previewRotation.value = adjustment.value
            }
            adjustment is VariableFilter && adjustment.colorMatrix() != null -> {
                // Show base bitmap (without this adjustment)
                // and apply only this adjustment's colorMatrix as GPU filter
                val baseBitmap = bitmaps.toList()
                    .filter { !it.second?.name.equals(adjustment.name, ignoreCase = true) }
                    .lastOrNull()?.first
                _currentBitmap.value = baseBitmap ?: lastRealBitmap()
                _previewMatrix.value = adjustment.colorMatrix()
                clearGpuPreviewEffects()
            }
            else -> {
                clearGpuPreviewEffects()
            }
        }
    }

    fun saveCopy(
        saveFormat: SaveFormat? = null,
        onSuccess: () -> Unit = {},
        onFail: () -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _isSaving.value = true
            val format = saveFormat ?: bestSaveFormat()
            // Flatten any pending matrix adjustments into the bitmap before saving
            flattenComposedMatrix()
            val media = activeMedia.value!!
            lastRealBitmap()?.let { bitmap ->
                try {
                    if (mediaHandler.saveImage(
                            bitmap = bitmap,
                            format = format.compressFormat,
                            relativePath = Environment.DIRECTORY_PICTURES + "/Edited",
                            displayName = media.label,
                            mimeType = format.mimeType
                        ) != null
                    ) {
                        onSuccess().also { _isSaving.value = false }
                    } else {
                        onFail().also { _isSaving.value = false }
                    }
                } catch (_: Exception) {
                    _isSaving.value = false
                    onFail().also { _isSaving.value = false }
                }
            } ?: onFail().also { _isSaving.value = false }
        }
    }

}
