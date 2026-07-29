/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.settings.subsettings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import com.dot.gallery.core.ml.ModelManager
import com.dot.gallery.core.ml.ModelStatus
import com.dot.gallery.core.workers.forceMetadataCollect
import com.dot.gallery.feature_node.domain.use_case.AiMediaAnalysis
import com.dot.gallery.feature_node.domain.use_case.AiMediaAnalysisSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SmartFeaturesViewModel @Inject internal constructor(
    private val modelManager: ModelManager,
    private val workManager: WorkManager,
    private val aiMediaAnalysis: AiMediaAnalysis,
) : ViewModel() {

    internal val analysisSettings: StateFlow<AiMediaAnalysisSettings> = aiMediaAnalysis.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AiMediaAnalysisSettings(
            analysisEnabled = false,
            categoryClassificationEnabled = true,
        ),
    )

    private val _isUpdatingAnalysis = MutableStateFlow(false)
    val isUpdatingAnalysis = _isUpdatingAnalysis.asStateFlow()

    val analysisProgress: StateFlow<Float?> = aiMediaAnalysis.workState.map { workState ->
        workState.analysisProgress
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = null,
    )

    val isAnalysisRunning: StateFlow<Boolean> = aiMediaAnalysis.workState.map { workState ->
        workState.isAnalysisActive
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false,
    )

    val modelStatus: StateFlow<ModelStatus> = modelManager.status

    val isMetadataWorkerRunning: StateFlow<Boolean> = workManager.getWorkInfosFlow(
        WorkQuery.fromUniqueWorkNames("MetadataCollection"),
    ).map { infos ->
        infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = false,
    )

    val metadataProgress: StateFlow<Int> = workManager.getWorkInfosFlow(
        WorkQuery.fromUniqueWorkNames("MetadataCollection"),
    ).map { infos ->
        infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?.progress?.getInt("progress", -1) ?: -1
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = -1,
    )

    fun refreshMetadata() {
        workManager.forceMetadataCollect()
    }

    fun setAnalysisEnabled(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            _isUpdatingAnalysis.value = true
            try {
                aiMediaAnalysis.setAnalysisEnabled(enabled = enabled)
            } finally {
                _isUpdatingAnalysis.value = false
            }
        }
    }
}
