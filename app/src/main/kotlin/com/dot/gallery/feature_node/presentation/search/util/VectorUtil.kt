/**
 * Copyright 2023 Viacheslav Barkov
 */

package com.dot.gallery.feature_node.presentation.search.util

import kotlin.math.sqrt

infix fun FloatArray.dot(other: FloatArray): Float {
    if (size != other.size || isEmpty()) {
        return 0f
    }
    return foldIndexed(0.0) { index, accumulator, value ->
        accumulator + value * other[index]
    }.toFloat()
}

fun normalizeL2(inputArray: FloatArray): FloatArray {
    if (inputArray.isEmpty() || inputArray.any { value -> !value.isFinite() }) {
        return FloatArray(size = inputArray.size)
    }
    var norm = 0.0f
    for (i in inputArray.indices) {
        norm += inputArray[i] * inputArray[i]
    }
    norm = sqrt(norm)
    if (norm == 0f || !norm.isFinite()) {
        return FloatArray(size = inputArray.size)
    }
    return inputArray.map { it / norm }.toFloatArray()
}
