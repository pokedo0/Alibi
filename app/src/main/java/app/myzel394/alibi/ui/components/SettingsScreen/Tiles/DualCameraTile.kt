package app.myzel394.alibi.ui.components.SettingsScreen.Tiles

import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.myzel394.alibi.R
import app.myzel394.alibi.dataStore
import app.myzel394.alibi.db.AppSettings
import app.myzel394.alibi.ui.components.atoms.SettingsTile
import app.myzel394.alibi.ui.utils.DualCameraSupport
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DualCameraTile(
	settings: AppSettings,
) {
	// Hard cutoff: CameraX ConcurrentCamera requires Android 11+
	if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val dataStore = context.dataStore

	var isSupported by remember { mutableStateOf<Boolean?>(null) }

	LaunchedEffect(Unit) {
		isSupported = runCatching {
			DualCameraSupport.isSupported(context)
		}.getOrDefault(false)
	}

	// Hide the tile entirely on unsupported devices
	if (isSupported != true) return

	fun updateValue(enabled: Boolean) {
		scope.launch {
			runCatching {
				dataStore.updateData {
					it.setVideoRecorderSettings(
						it.videoRecorderSettings.setDualCameraEnabled(enabled)
					)
				}
			}.onFailure {
				android.util.Log.e("DualCameraTile", "Failed to update dual camera setting", it)
			}
		}
	}

	SettingsTile(
		title = stringResource(R.string.ui_settings_option_dualCamera_title),
		description = stringResource(R.string.ui_settings_option_dualCamera_description),
		leading = {
			Icon(
				Icons.Default.Cameraswitch,
				contentDescription = null,
			)
		},
		trailing = {
			Switch(
				checked = settings.videoRecorderSettings.dualCameraEnabled,
				onCheckedChange = ::updateValue,
			)
		},
	)
}
