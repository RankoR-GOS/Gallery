/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.util

import android.content.res.Resources
import android.os.Parcelable
import android.text.format.DateFormat
import androidx.compose.ui.text.intl.Locale as ComposeLocale
import androidx.core.os.ConfigurationCompat
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.parcelize.Parcelize

private val FILENAME_DATE_REGEX = Regex("""(\d{4})(\d{2})(\d{2})[_-](\d{2})(\d{2})(\d{2})""")

fun Long.getDateExt(): DateExt {
    val mediaDate = Calendar.getInstance(ComposeLocale.getCurrentAndroid())
    mediaDate.timeInMillis = this * 1000L
    return DateExt(
        month = mediaDate.getDisplayName(Calendar.MONTH, Calendar.LONG_FORMAT, ComposeLocale.getCurrentAndroid())!!,
        day = mediaDate.get(Calendar.DAY_OF_MONTH),
        year = mediaDate.get(Calendar.YEAR)
    )
}

fun getDateHeader(startDate: DateExt, endDate: DateExt): String {
    return if (startDate.year == endDate.year) {
        if (startDate.month == endDate.month) {
            if (startDate.day == endDate.day) {
                "${startDate.month} ${startDate.day}, ${startDate.year}"
            } else "${startDate.month} ${startDate.day} - ${endDate.day}, ${startDate.year}"
        } else
            "${startDate.month} ${startDate.day} - ${endDate.month} ${endDate.day}, ${startDate.year}"
    } else {
        "${startDate.month} ${startDate.day}, ${startDate.year} - ${endDate.month} ${endDate.day}, ${endDate.year}"
    }
}

fun getMonth(extendedFormat: String, defaultFormat: String, date: String): String {
    return try {
        val dateFormatExtended = SimpleDateFormat(extendedFormat, ComposeLocale.getCurrentAndroid()).parse(date)
        val cal = Calendar.getInstance(ComposeLocale.getCurrentAndroid()).apply { timeInMillis = dateFormatExtended!!.time }
        val month = cal.getDisplayName(Calendar.MONTH, Calendar.LONG_FORMAT, ComposeLocale.getCurrentAndroid())!!
        val year = cal.get(Calendar.YEAR)
        "$month $year"
    } catch (e: ParseException) {
        try {
            val dateFormat = SimpleDateFormat(defaultFormat, ComposeLocale.getCurrentAndroid()).parse(date)
            val cal = Calendar.getInstance(ComposeLocale.getCurrentAndroid()).apply { timeInMillis = dateFormat!!.time }
            cal.getDisplayName(Calendar.MONTH, Calendar.LONG_FORMAT, ComposeLocale.getCurrentAndroid())!!
        } catch (e: ParseException) {
            ""
        }
    }
}

fun ComposeLocale.Companion.getCurrentAndroid(): Locale {
    return ConfigurationCompat.getLocales(Resources.getSystem().configuration)[0] ?: Locale.getDefault()
}

fun Long.getDate(
    format: CharSequence,
): String {
    val mediaDate = Calendar.getInstance(ComposeLocale.getCurrentAndroid())
    mediaDate.timeInMillis = this * 1000L
    return DateFormat.format(format, mediaDate).toString()
}

fun Long.getMediaAppBarDate(
    format: CharSequence,
    extendedFormat: CharSequence,
): String {
    val locale = ComposeLocale.getCurrentAndroid()
    val mediaDate = Calendar.getInstance(locale)
    mediaDate.timeInMillis = this * 1000L
    return if (mediaDate.get(Calendar.YEAR) < Calendar.getInstance(locale).get(Calendar.YEAR)) {
        DateFormat.format(extendedFormat, mediaDate).toString()
    } else {
        DateFormat.format(format, mediaDate).toString()
    }
}

fun Long.getDate(
    format: String,
    weeklyFormat: String,
    extendedFormat: String,
    stringToday: String,
    stringYesterday: String
): String {
    val locale = ComposeLocale.getCurrentAndroid()
    val currentDate = Calendar.getInstance(locale)
    currentDate.timeInMillis = System.currentTimeMillis()
    val mediaDate = Calendar.getInstance(locale)
    mediaDate.timeInMillis = this * 1000L

    // Use calendar-day difference (truncate to start of day) to avoid
    // grouping artifacts at day boundaries. Raw time division caused
    // photos from the same calendar day to land in different groups
    // when their hour-of-day differences straddled a rounding boundary.
    val currentDayStart = Calendar.getInstance(locale).apply {
        timeInMillis = currentDate.timeInMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val mediaDayStart = Calendar.getInstance(locale).apply {
        timeInMillis = mediaDate.timeInMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val daysDifference = ((currentDayStart.timeInMillis - mediaDayStart.timeInMillis) / (24 * 60 * 60 * 1000L)).toInt()

    return when {
        daysDifference <= 0 -> stringToday
        daysDifference == 1 -> stringYesterday
        daysDifference in 2..6 -> DateFormat.format(weeklyFormat, mediaDate).toString()
        else -> {
            if (currentDate.get(Calendar.YEAR) > mediaDate.get(Calendar.YEAR)) {
                DateFormat.format(extendedFormat, mediaDate).toString()
            } else DateFormat.format(format, mediaDate).toString()
        }
    }
}

fun Long.getMonth(): String {
    val currentDate = Calendar.getInstance(ComposeLocale.getCurrentAndroid()).apply { timeInMillis = System.currentTimeMillis() }
    val mediaDate = Calendar.getInstance(ComposeLocale.getCurrentAndroid()).apply { timeInMillis = this@getMonth * 1000L }
    val month = mediaDate.getDisplayName(Calendar.MONTH, Calendar.LONG_FORMAT, ComposeLocale.getCurrentAndroid())!!
    val year = mediaDate.get(Calendar.YEAR)
    return if (currentDate.get(Calendar.YEAR) != mediaDate.get(Calendar.YEAR))
        "$month $year"
    else month
}

fun Long.formatMinSec(): String {
    return if (this == 0L) {
        "00:00"
    } else {
        String.format(
            ComposeLocale.getCurrentAndroid(),
            "%02d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(this),
            TimeUnit.MILLISECONDS.toSeconds(this) -
                    TimeUnit.MINUTES.toSeconds(
                        TimeUnit.MILLISECONDS.toMinutes(this)
                    )
        )
    }
}

fun String?.formatMinSec(): String {
    return when (val value = this?.toLong()) {
        null -> ""
        else -> value.formatMinSec()
    }
}

fun String.parseTimestampFromFilename(): Long? {
    val match = FILENAME_DATE_REGEX.find(this) ?: return null
    val (year, month, day, hour, minute, second) = match.destructured
    val yearValue = year.toIntOrNull() ?: return null
    val monthValue = month.toIntOrNull() ?: return null
    val dayValue = day.toIntOrNull() ?: return null
    val hourValue = hour.toIntOrNull() ?: return null
    val minuteValue = minute.toIntOrNull() ?: return null
    val secondValue = second.toIntOrNull() ?: return null

    if (yearValue !in 1970..2100) return null

    return runCatching {
        Calendar.getInstance().apply {
            isLenient = false
            set(yearValue, monthValue - 1, dayValue, hourValue, minuteValue, secondValue)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }.getOrNull()?.takeIf { timestamp -> timestamp <= System.currentTimeMillis() }
}

@Parcelize
data class DateExt(val month: String, val day: Int, val year: Int): Parcelable
