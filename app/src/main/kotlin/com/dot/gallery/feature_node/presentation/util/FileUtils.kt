package com.dot.gallery.feature_node.presentation.util

import android.content.Context
import com.dot.gallery.R
import java.io.File
import java.math.RoundingMode
import java.text.DecimalFormat
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

@Suppress("NOTHING_TO_INLINE")
inline fun String.sentenceCase(): String = lowercase().replaceFirstChar { it.uppercase() }

fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"

    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()

    val formattedSize = size / 1024.0.pow(digitGroups.toDouble())
    return String.format(Locale.getDefault(), "%.2f %s", formattedSize, units[digitGroups])
}

fun File.formattedFileSize(context: Context): String {
    var fileSize = this.length().toDouble() / 1024.0
    var fileSizeName = context.getString(R.string.kb)
    if (fileSize > 1024.0) {
        fileSize /= 1024.0
        fileSizeName = context.getString(R.string.mb)
        if (fileSize > 1024.0) {
            fileSize /= 1024.0
            fileSizeName = context.getString(R.string.gb)
        }
    }
    val roundingSize = DecimalFormat("#.##").apply {
        roundingMode = RoundingMode.DOWN
    }
    return "${roundingSize.format(fileSize)} $fileSizeName"
}
