/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.presentation.settings.subsettings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.res.stringResource
import com.dot.gallery.R
import com.dot.gallery.core.Position
import com.dot.gallery.core.Settings
import com.dot.gallery.core.SettingsEntity
import com.dot.gallery.feature_node.presentation.settings.components.BaseSettingsScreen
import com.dot.gallery.feature_node.presentation.settings.components.ChooserPreferenceDetailScreen
import com.dot.gallery.feature_node.presentation.settings.components.PreferenceOption
import com.dot.gallery.feature_node.presentation.settings.components.rememberPreference

private const val DETAIL_METADATA_ISOLATION = "metadata_isolation"

@Composable
fun SettingsSecurityScreen() {
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    var metadataIsolationMode by Settings.Security.rememberMetadataIsolationMode()

    when (detailKey) {
        DETAIL_METADATA_ISOLATION -> {
            BackHandler {
                detailKey = null
            }
            ChooserPreferenceDetailScreen(
                title = stringResource(id = R.string.security_metadata_isolation),
                description = stringResource(id = R.string.security_metadata_isolation_summary),
                options = listOf(
                    PreferenceOption(
                        value = Settings.Security.METADATA_ISOLATION_SHARED,
                        label = stringResource(id = R.string.security_metadata_isolation_shared),
                        isSelected = metadataIsolationMode == Settings.Security.METADATA_ISOLATION_SHARED,
                        summary = stringResource(id = R.string.security_metadata_isolation_shared_summary),
                    ),
                    PreferenceOption(
                        value = Settings.Security.METADATA_ISOLATION_HYBRID,
                        label = stringResource(id = R.string.security_metadata_isolation_hybrid),
                        isSelected = metadataIsolationMode == Settings.Security.METADATA_ISOLATION_HYBRID,
                        summary = stringResource(id = R.string.security_metadata_isolation_hybrid_summary),
                    ),
                    PreferenceOption(
                        value = Settings.Security.METADATA_ISOLATION_PER_FILE,
                        label = stringResource(id = R.string.security_metadata_isolation_per_file),
                        isSelected = metadataIsolationMode == Settings.Security.METADATA_ISOLATION_PER_FILE,
                        summary = stringResource(id = R.string.security_metadata_isolation_per_file_summary),
                    ),
                ),
                onOptionSelected = {
                    metadataIsolationMode = it
                },
            )
        }

        else -> {
            SecurityListScreen(
                metadataIsolationMode = metadataIsolationMode,
                onDetailClick = {
                    detailKey = it
                },
            )
        }
    }
}

@Composable
private fun SecurityListScreen(
    metadataIsolationMode: String,
    onDetailClick: (String) -> Unit,
) {
    @Composable
    fun settings(): SnapshotStateList<SettingsEntity> {
        val metadataIsolationSummary = when (metadataIsolationMode) {
            Settings.Security.METADATA_ISOLATION_SHARED -> {
                stringResource(id = R.string.security_metadata_isolation_shared)
            }

            Settings.Security.METADATA_ISOLATION_PER_FILE -> {
                stringResource(id = R.string.security_metadata_isolation_per_file)
            }

            else -> {
                stringResource(id = R.string.security_metadata_isolation_hybrid)
            }
        }
        val metadataIsolationPref = rememberPreference(
            metadataIsolationMode,
            title = stringResource(id = R.string.security_metadata_isolation),
            summary = metadataIsolationSummary,
            onClick = {
                onDetailClick(DETAIL_METADATA_ISOLATION)
            },
            screenPosition = Position.Alone,
        )

        return remember(metadataIsolationPref) {
            mutableStateListOf(metadataIsolationPref)
        }
    }

    BaseSettingsScreen(
        title = stringResource(id = R.string.settings_security),
        settingsList = settings(),
    )
}
