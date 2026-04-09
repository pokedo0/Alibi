package app.myzel394.alibi.ui.components.SettingsScreen.Tiles

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.myzel394.alibi.R
import app.myzel394.alibi.dataStore
import app.myzel394.alibi.db.AppSettings
import app.myzel394.alibi.ui.components.atoms.SettingsTile
import app.myzel394.alibi.ui.utils.CameraInfo
import app.myzel394.alibi.ui.utils.IconResource
import com.maxkeppeker.sheets.core.models.base.Header
import com.maxkeppeker.sheets.core.models.base.IconSource
import com.maxkeppeker.sheets.core.models.base.rememberUseCaseState
import com.maxkeppeler.sheets.list.ListDialog
import com.maxkeppeler.sheets.list.models.ListOption
import com.maxkeppeler.sheets.list.models.ListSelection
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoCameraSelectionTile(
	settings: AppSettings,
) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val showDialog = rememberUseCaseState()
	val dataStore = context.dataStore

	val cameras = CameraInfo.queryAvailableCameras(context)
	val isMultiCamera = !CameraInfo.checkHasNormalCameras(cameras)

	val cameraTypeLabelMap = mapOf(
		CameraInfo.CameraType.MAIN to stringResource(R.string.ui_videoRecorder_cameraType_main),
		CameraInfo.CameraType.WIDE_ANGLE to stringResource(R.string.ui_videoRecorder_cameraType_wideAngle),
		CameraInfo.CameraType.TELEPHOTO to stringResource(R.string.ui_videoRecorder_cameraType_telephoto),
		CameraInfo.CameraType.MACRO to stringResource(R.string.ui_videoRecorder_cameraType_macro),
		CameraInfo.CameraType.UNKNOWN to stringResource(R.string.ui_videoRecorder_cameraType_unknown),
	)

	val lensLabelMap = mapOf(
		CameraInfo.Lens.BACK to stringResource(R.string.ui_videoRecorder_action_start_settings_cameraLens_back_label),
		CameraInfo.Lens.FRONT to stringResource(R.string.ui_videoRecorder_action_start_settings_cameraLens_front_label),
		CameraInfo.Lens.EXTERNAL to stringResource(R.string.ui_videoRecorder_action_start_settings_cameraLens_external_label),
		CameraInfo.Lens.UNKNOWN to stringResource(R.string.ui_videoRecorder_action_start_settings_cameraLens_unknown_label),
	)

	fun cameraLabel(camera: CameraInfo): String {
		val lensLabel = lensLabelMap[camera.lens] ?: camera.cameraId
		if (!isMultiCamera) return lensLabel

		val typeLabel = cameraTypeLabelMap[camera.cameraType]
		return if (typeLabel != null && camera.cameraType != CameraInfo.CameraType.UNKNOWN) {
			"$typeLabel — $lensLabel"
		} else {
			lensLabel
		}
	}

	val preferredId = settings.videoRecorderSettings.preferredCameraId
	val selectedCameraForLabel = cameras.firstOrNull { it.cameraId == preferredId }
	val currentSelectionLabel = if (selectedCameraForLabel != null) {
		cameraLabel(selectedCameraForLabel)
	} else {
		stringResource(R.string.ui_settings_value_auto_label)
	}

	fun updateValue(cameraId: String?) {
		scope.launch {
			dataStore.updateData {
				it.setVideoRecorderSettings(
					it.videoRecorderSettings.setPreferredCameraId(cameraId)
				)
			}
		}
	}

	ListDialog(
		state = showDialog,
		header = Header.Default(
			title = stringResource(R.string.ui_settings_option_videoCameraSelection_title),
			icon = IconSource(
				painter = IconResource.fromImageVector(Icons.Default.CameraAlt)
					.asPainterResource(),
				contentDescription = null,
			),
		),
		selection = ListSelection.Single(
			showRadioButtons = true,
			options = cameras.map { camera ->
				ListOption(
					titleText = cameraLabel(camera),
					selected = settings.videoRecorderSettings.preferredCameraId == camera.cameraId,
				)
			}.toList()
		) { index, _ ->
			updateValue(cameras[index].cameraId)
		},
	)
	SettingsTile(
		title = stringResource(R.string.ui_settings_option_videoCameraSelection_title),
		leading = {
			Icon(
				Icons.Default.CameraAlt,
				contentDescription = null,
			)
		},
		trailing = {
			Button(
				onClick = showDialog::show,
				colors = ButtonDefaults.filledTonalButtonColors(
					containerColor = MaterialTheme.colorScheme.surfaceVariant,
				),
				shape = MaterialTheme.shapes.medium,
			) {
				Text(currentSelectionLabel)
			}
		},
	)
}
