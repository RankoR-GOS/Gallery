package com.dot.gallery.feature_node.domain.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import com.dot.gallery.feature_node.data.model.MediaMetadata

@Stable
data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val location: String
)

@Composable
fun rememberLocationData(exifMetadata: MediaMetadata?): LocationData? {
    return remember(exifMetadata) {
        val latitude = exifMetadata?.gpsLatitude ?: return@remember null
        val longitude = exifMetadata.gpsLongitude ?: return@remember null
        LocationData(
            latitude = latitude,
            longitude = longitude,
            location = exifMetadata.gpsLocationName ?: exifMetadata.formattedCords.orEmpty(),
        )
    }
}
