/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.cast

import androidx.lifecycle.ViewModel
import com.dot.gallery.feature_node.domain.model.Media
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class FCastViewModel @Inject constructor(
    private val session: FCastSession
) : ViewModel() {

    val state: StateFlow<CastSessionState> = session.state

    val isConnected: Boolean get() = session.isConnected
    val isCasting: Boolean get() = session.isCasting

    fun isCastAvailable(): Boolean = session.isCastAvailable()
    fun checkPermissions(): List<CastPermission> = session.checkPermissions()
    fun hasAllPermissions(): Boolean = session.hasAllPermissions()

    fun startDiscovery() = session.startDiscovery()
    fun stopDiscovery() = session.stopDiscovery()

    fun connect(device: FCastDevice) {
        session.stopDiscovery()
        session.connect(device)
    }

    fun disconnect() = session.disconnect()

    fun <T : Media> castMedia(media: T) {
        session.castMedia(media)
    }

    fun togglePlayPause() = session.togglePlayPause()
    fun pause() = session.pause()
    fun resume() = session.resume()
    fun seek(timeSeconds: Double) = session.seek(timeSeconds)
    fun setVolume(volume: Double) = session.setVolume(volume)
    fun setSpeed(speed: Double) = session.setSpeed(speed)
    fun stopCasting() = session.stop()
}
