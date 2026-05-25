package com.dot.gallery.feature_node.presentation.settings.subsettings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dot.gallery.R
import com.dot.gallery.core.Position
import com.dot.gallery.core.Settings
import com.dot.gallery.core.Settings.Misc.rememberTrashConfirmationEnabled
import com.dot.gallery.core.SettingsEntity
import com.dot.gallery.core.util.SdkCompat
import com.dot.gallery.feature_node.presentation.settings.components.BaseSettingsScreen
import com.dot.gallery.feature_node.presentation.settings.components.SwitchPreferenceDetailScreen
import com.dot.gallery.feature_node.presentation.settings.components.rememberSwitchPreference

private const val DETAIL_TRASH = "trash"
private const val DETAIL_TRASH_CONFIRM = "trash_confirm"
private const val DETAIL_SECURE = "secure"
private const val DETAIL_VIBRATIONS = "vibrations"

@Composable
fun SettingsGeneralScreen() {
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }

    var trashCanEnabled by Settings.Misc.rememberTrashEnabled()
    var trashConfirmationEnabled by rememberTrashConfirmationEnabled()
    var secureMode by Settings.Misc.rememberSecureMode()
    var allowVibrations by Settings.Misc.rememberAllowVibrations()

    when (detailKey) {
        DETAIL_TRASH -> {
            BackHandler { detailKey = null }
            SwitchPreferenceDetailScreen(
                title = stringResource(R.string.settings_trash_title),
                isChecked = trashCanEnabled,
                onCheckedChange = { trashCanEnabled = it },
                description = stringResource(R.string.trash_enabled_description),
            )
        }
        DETAIL_TRASH_CONFIRM -> {
            BackHandler { detailKey = null }
            SwitchPreferenceDetailScreen(
                title = stringResource(R.string.settings_trash_confirmation_title),
                isChecked = trashConfirmationEnabled,
                onCheckedChange = { trashConfirmationEnabled = it },
                description = stringResource(R.string.trash_confirmation_description),
            )
        }
        DETAIL_SECURE -> {
            BackHandler { detailKey = null }
            SwitchPreferenceDetailScreen(
                title = stringResource(R.string.secure_mode_title),
                isChecked = secureMode,
                onCheckedChange = { secureMode = it },
                description = stringResource(R.string.secure_mode_description),
                preview = { checked -> SecureModePreview(checked) },
            )
        }
        DETAIL_VIBRATIONS -> {
            BackHandler { detailKey = null }
            SwitchPreferenceDetailScreen(
                title = stringResource(R.string.allow_vibrations),
                isChecked = allowVibrations,
                onCheckedChange = { allowVibrations = it },
                description = stringResource(R.string.allow_vibrations_description),
            )
        }
        else -> {
            GeneralListScreen(
                trashCanEnabled = trashCanEnabled,
                onTrashChange = { trashCanEnabled = it },
                trashConfirmationEnabled = trashConfirmationEnabled,
                onTrashConfirmChange = { trashConfirmationEnabled = it },
                secureMode = secureMode,
                onSecureChange = { secureMode = it },
                allowVibrations = allowVibrations,
                onVibrationsChange = { allowVibrations = it },
                onDetailClick = { detailKey = it },
            )
        }
    }
}

@Composable
private fun GeneralListScreen(
    trashCanEnabled: Boolean,
    onTrashChange: (Boolean) -> Unit,
    trashConfirmationEnabled: Boolean,
    onTrashConfirmChange: (Boolean) -> Unit,
    secureMode: Boolean,
    onSecureChange: (Boolean) -> Unit,
    allowVibrations: Boolean,
    onVibrationsChange: (Boolean) -> Unit,
    onDetailClick: (String) -> Unit,
) {
    @Composable
    fun settings(): SnapshotStateList<SettingsEntity> {
        val res = LocalResources.current

        val trashSectionPref = remember(res) {
            SettingsEntity.Header(title = res.getString(R.string.trash))
        }

        val trashCanEnabledPref = rememberSwitchPreference(
            trashCanEnabled,
            title = stringResource(R.string.settings_trash_title),
            summary = stringResource(R.string.settings_trash_summary),
            isChecked = trashCanEnabled,
            onCheck = onTrashChange,
            onClick = { onDetailClick(DETAIL_TRASH) },
            screenPosition = Position.Top
        )

        val trashConfirmationEnabledPref = rememberSwitchPreference(
            trashConfirmationEnabled,
            title = stringResource(R.string.settings_trash_confirmation_title),
            summary = stringResource(R.string.settings_trash_confirmation_summary),
            isChecked = trashConfirmationEnabled,
            onCheck = onTrashConfirmChange,
            onClick = { onDetailClick(DETAIL_TRASH_CONFIRM) },
            screenPosition = Position.Bottom
        )

        val otherSectionPref = remember(res) {
            SettingsEntity.Header(title = res.getString(R.string.other))
        }

        val secureModePref = rememberSwitchPreference(
            secureMode,
            title = stringResource(R.string.secure_mode_title),
            summary = stringResource(R.string.secure_mode_summary),
            isChecked = secureMode,
            onCheck = onSecureChange,
            onClick = { onDetailClick(DETAIL_SECURE) },
            screenPosition = Position.Top
        )

        val allowVibrationsPref = rememberSwitchPreference(
            allowVibrations,
            title = stringResource(R.string.allow_vibrations),
            summary = stringResource(R.string.allow_vibrations_summary),
            isChecked = allowVibrations,
            onCheck = onVibrationsChange,
            onClick = { onDetailClick(DETAIL_VIBRATIONS) },
            screenPosition = Position.Bottom
        )

        return remember(
            trashCanEnabledPref, trashConfirmationEnabledPref,
            secureModePref, allowVibrationsPref
        ) {
            mutableStateListOf<SettingsEntity>().apply {
                if (SdkCompat.supportsTrash) {
                    add(trashSectionPref)
                    add(trashCanEnabledPref)
                    add(trashConfirmationEnabledPref)
                }
                add(otherSectionPref)
                add(secureModePref)
                add(allowVibrationsPref)
            }
        }
    }

    BaseSettingsScreen(
        title = stringResource(R.string.settings_general),
        settingsList = settings(),
    )
}

@Composable
private fun SecureModePreview(isChecked: Boolean) {
    val overlayAlpha by animateFloatAsState(if (isChecked) 1f else 0f, label = "overlay")
    val cellColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .height(140.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(3.dp)).background(cellColor))
            repeat(2) {
                Row(Modifier.weight(1f).fillMaxWidth(), Arrangement.spacedBy(4.dp)) {
                    repeat(3) {
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(cellColor))
                    }
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f * overlayAlpha),
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f * overlayAlpha)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (overlayAlpha > 0f) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = overlayAlpha)
                )
            }
        }
    }
}
