package com.dot.gallery.feature_node.presentation.util

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toIntRect
import com.dot.gallery.feature_node.data.util.isHeaderKey
import com.dot.gallery.feature_node.data.util.isIgnoredKey

private val String?.mediaIdFromKey: Long?
    get() = this?.let {
        if (isHeaderKey || isIgnoredKey) null
        else if (it.startsWith("{")) removePrefix("{").substringBefore(",").toLongOrNull()
        else removePrefix("media_").substringBefore("_").toLongOrNull()
    }

private fun LazyGridState.hitKeyAt(
    raw: Offset,
    padL: Float,
    padT: Float,
): String? {
    val contentOffset = raw - Offset(padL, padT)
    return layoutInfo.visibleItemsInfo
        .find { info ->
            info.size.toIntRect()
                .contains((contentOffset.round() - info.offset))
        }
        ?.key as? String
}

private class HitInfo(
    val key: String,
    val normalizedX: Float,
    val normalizedY: Float,
)

private fun LazyGridState.hitInfoAt(
    raw: Offset,
    padL: Float,
    padT: Float,
): HitInfo? {
    val contentOffset = raw - Offset(padL, padT)
    val info = layoutInfo.visibleItemsInfo
        .find { it.size.toIntRect().contains((contentOffset.round() - it.offset)) }
        ?: return null
    val key = info.key as? String ?: return null
    val relativeOffset = contentOffset - Offset(info.offset.x.toFloat(), info.offset.y.toFloat())
    return HitInfo(
        key = key,
        normalizedX = (relativeOffset.x / info.size.width).coerceIn(0f, 1f),
        normalizedY = (relativeOffset.y / info.size.height).coerceIn(0f, 1f),
    )
}

private fun resolveHitIds(
    hit: HitInfo,
    allIds: List<Long>,
): List<Long> {
    return when {
        hit.key.startsWith("mosaic_pair_") && allIds.size == 2 ->
            listOf(if (hit.normalizedY < 0.5f) allIds[0] else allIds[1])

        hit.key.startsWith("mosaic_quad_") && allIds.size == 4 -> {
            val column = if (hit.normalizedX < 0.5f) 0 else 1
            val row = if (hit.normalizedY < 0.5f) 0 else 1
            listOf(allIds[row * 2 + column])
        }

        else -> allIds
    }
}

fun Modifier.mosaicGridDragHandler(
    lazyGridState: LazyGridState,
    haptics: HapticFeedback,
    selectedIds: State<Set<Long>>,
    updateSelectedIds: (Set<Long>) -> Unit,
    autoScrollSpeed: MutableState<Float>,
    autoScrollThreshold: Float,
    scrollGestureActive: MutableState<Boolean>,
    layoutDirection: LayoutDirection,
    contentPadding: PaddingValues,
    orderedGridKeys: List<String>,
    gridKeyToMediaIds: Map<String, List<Long>>,
): Modifier {
    val gridKeyToIndex = orderedGridKeys.withIndex().associate { (index, key) -> key to index }
    return pointerInput(orderedGridKeys, gridKeyToMediaIds, contentPadding, layoutDirection) {
        val leftPadding = contentPadding.calculateLeftPadding(layoutDirection).toPx()
        val topPadding = contentPadding.calculateTopPadding().toPx()

        var initialIndex: Int? = null
        var currentIndex: Int? = null

        detectDragGesturesAfterLongPress(
            onDragStart = { rawOffset ->
                scrollGestureActive.value = true
                lazyGridState.hitInfoAt(rawOffset, leftPadding, topPadding)?.let { hit ->
                    val hitIndex = gridKeyToIndex[hit.key] ?: -1
                    val hitMediaIds = gridKeyToMediaIds[hit.key].orEmpty()
                    if (hitIndex >= 0 && hitMediaIds.isNotEmpty()) {
                        val selectedHitIds = resolveHitIds(hit, hitMediaIds).toSet()
                        if (!selectedIds.value.containsAll(selectedHitIds)) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            updateSelectedIds(selectedIds.value + selectedHitIds)
                        }
                        initialIndex = hitIndex
                        currentIndex = hitIndex
                    }
                }
            },
            onDragCancel = {
                scrollGestureActive.value = false
                initialIndex = null
                autoScrollSpeed.value = 0f
            },
            onDragEnd = {
                scrollGestureActive.value = false
                initialIndex = null
                autoScrollSpeed.value = 0f
            },
            onDrag = { change, _ ->
                val rawOffset = change.position
                val initialDragIndex = initialIndex
                if (initialDragIndex != null) {
                    val distanceFromBottom =
                        lazyGridState.layoutInfo.viewportSize.height - rawOffset.y
                    val distanceFromTop = rawOffset.y
                    autoScrollSpeed.value = when {
                        distanceFromBottom < autoScrollThreshold ->
                            autoScrollThreshold - distanceFromBottom
                        distanceFromTop < autoScrollThreshold ->
                            -(autoScrollThreshold - distanceFromTop)
                        else -> 0f
                    }

                    lazyGridState.hitKeyAt(rawOffset, leftPadding, topPadding)?.let { key ->
                        val newIndex = gridKeyToIndex[key] ?: -1
                        val previousIndex = currentIndex
                        if (newIndex >= 0 && previousIndex != null && newIndex != previousIndex) {
                            val oldRange = when {
                                previousIndex >= initialDragIndex -> initialDragIndex..previousIndex
                                else -> previousIndex..initialDragIndex
                            }
                            val newRange = when {
                                newIndex >= initialDragIndex -> initialDragIndex..newIndex
                                else -> newIndex..initialDragIndex
                            }

                            val oldIds = oldRange.flatMapTo(mutableSetOf()) { index ->
                                gridKeyToMediaIds[orderedGridKeys[index]].orEmpty()
                            }
                            val newIds = newRange.flatMapTo(mutableSetOf()) { index ->
                                gridKeyToMediaIds[orderedGridKeys[index]].orEmpty()
                            }

                            updateSelectedIds(selectedIds.value - oldIds + newIds)
                            currentIndex = newIndex
                        }
                    }
                }
            },
        )
    }
}

fun Modifier.photoGridDragHandler(
    lazyGridState: LazyGridState,
    haptics: HapticFeedback,
    selectedIds: State<Set<Long>>,
    updateSelectedIds: (Set<Long>) -> Unit,
    autoScrollSpeed: MutableState<Float>,
    autoScrollThreshold: Float,
    scrollGestureActive: MutableState<Boolean>,
    layoutDirection: LayoutDirection,
    contentPadding: PaddingValues,
    allKeys: List<String>,
): Modifier {
    val mediaKeysInOrder = allKeys.filter { key -> key.mediaIdFromKey != null }
    val mediaIdsInOrder = mediaKeysInOrder.mapNotNull { key -> key.mediaIdFromKey }
    val keyToIndex = mediaKeysInOrder.withIndex().associate { (index, key) -> key to index }

    return pointerInput(allKeys, contentPadding, layoutDirection) {
        val leftPadding = contentPadding.calculateLeftPadding(layoutDirection).toPx()
        val topPadding = contentPadding.calculateTopPadding().toPx()

        var initialMediaIndex: Int? = null
        var currentMediaIndex: Int? = null

        detectDragGesturesAfterLongPress(
            onDragStart = { rawOffset ->
                scrollGestureActive.value = true
                lazyGridState.hitKeyAt(rawOffset, leftPadding, topPadding)?.let { key ->
                    val mediaIndex = keyToIndex[key] ?: -1
                    val mediaId = key.mediaIdFromKey
                    if (mediaIndex >= 0 && mediaId != null) {
                        if (mediaId !in selectedIds.value) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            updateSelectedIds(selectedIds.value + mediaId)
                        }
                        initialMediaIndex = mediaIndex
                        currentMediaIndex = mediaIndex
                    }
                }
            },
            onDragCancel = {
                scrollGestureActive.value = false
                initialMediaIndex = null
                autoScrollSpeed.value = 0f
            },
            onDragEnd = {
                scrollGestureActive.value = false
                initialMediaIndex = null
                autoScrollSpeed.value = 0f
            },
            onDrag = { change, _ ->
                val rawOffset = change.position
                val initialDragIndex = initialMediaIndex
                if (initialDragIndex != null) {
                    val distanceFromBottom =
                        lazyGridState.layoutInfo.viewportSize.height - rawOffset.y
                    val distanceFromTop = rawOffset.y
                    autoScrollSpeed.value = when {
                        distanceFromBottom < autoScrollThreshold ->
                            autoScrollThreshold - distanceFromBottom
                        distanceFromTop < autoScrollThreshold ->
                            -(autoScrollThreshold - distanceFromTop)
                        else -> 0f
                    }

                    lazyGridState.hitKeyAt(rawOffset, leftPadding, topPadding)?.let { key ->
                        val newIndex = keyToIndex[key] ?: -1
                        val previousIndex = currentMediaIndex
                        if (newIndex >= 0 && previousIndex != null && newIndex != previousIndex) {
                            val oldRange = when {
                                previousIndex >= initialDragIndex -> initialDragIndex..previousIndex
                                else -> previousIndex..initialDragIndex
                            }
                            val newRange = when {
                                newIndex >= initialDragIndex -> initialDragIndex..newIndex
                                else -> newIndex..initialDragIndex
                            }

                            val oldIds = oldRange.map { index -> mediaIdsInOrder[index] }.toSet()
                            val newIds = newRange.map { index -> mediaIdsInOrder[index] }.toSet()

                            updateSelectedIds(selectedIds.value - oldIds + newIds)
                            currentMediaIndex = newIndex
                        }
                    }
                }
            },
        )
    }
}
