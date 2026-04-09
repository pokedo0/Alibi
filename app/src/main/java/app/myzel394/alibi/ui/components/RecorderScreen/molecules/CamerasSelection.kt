package app.myzel394.alibi.ui.components.RecorderScreen.molecules

import CameraSelectionButton
import CAMERA_TYPE_ICON_MAP
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalLensFacing
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.myzel394.alibi.R
import app.myzel394.alibi.dataStore
import app.myzel394.alibi.ui.models.VideoRecorderModel
import app.myzel394.alibi.ui.utils.CameraInfo
import kotlinx.coroutines.launch

@Composable
fun CamerasSelection(
	cameras: List<CameraInfo>,
	videoSettings: VideoRecorderModel,
) {
	val scope = rememberCoroutineScope()
	val dataStore = LocalContext.current.dataStore

	fun selectCamera(camera: CameraInfo) {
		videoSettings.selectedCamera = camera
		scope.launch {
			dataStore.updateData {
				it.setVideoRecorderSettings(
					it.videoRecorderSettings.setPreferredCameraId(camera.cameraId)
				)
			}
		}
	}

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

	Column {
		if (CameraInfo.checkHasNormalCameras(cameras)) {
			// Simple two-camera device: show Back / Front
			val backCamera = cameras.first { it.lens == CameraInfo.Lens.BACK }
			val frontCamera = cameras.first { it.lens == CameraInfo.Lens.FRONT }

			CameraSelectionButton(
				cameraID = CameraInfo.Lens.BACK,
				label = lensLabelMap[CameraInfo.Lens.BACK]!!,
				selected = videoSettings.selectedCamera == backCamera,
				onSelected = { selectCamera(backCamera) },
			)
			CameraSelectionButton(
				cameraID = CameraInfo.Lens.FRONT,
				label = lensLabelMap[CameraInfo.Lens.FRONT]!!,
				selected = videoSettings.selectedCamera == frontCamera,
				onSelected = { selectCamera(frontCamera) },
			)
		} else {
			// Multi-camera device: group by lens facing, show individual cameras
			val grouped = cameras.groupBy { it.lens }
			// Order: BACK cameras first, then FRONT, then EXTERNAL, then UNKNOWN
			val orderedLenses = listOf(
				CameraInfo.Lens.BACK,
				CameraInfo.Lens.FRONT,
				CameraInfo.Lens.EXTERNAL,
				CameraInfo.Lens.UNKNOWN,
			)

			orderedLenses.forEach { lens ->
				val group = grouped[lens] ?: return@forEach

				if (group.size == 1) {
					// Single camera for this facing — show simple label
					val camera = group.first()
					CameraSelectionButton(
						cameraID = camera.lens,
						label = lensLabelMap[camera.lens] ?: camera.cameraId,
						selected = videoSettings.selectedCamera == camera,
						onSelected = { selectCamera(camera) },
					)
				} else {
					// Multiple cameras for this facing — show type as label, facing as description
					group.forEach { camera ->
						CameraSelectionButton(
							cameraID = camera.lens,
							label = cameraTypeLabelMap[camera.cameraType]
								?: stringResource(
									R.string.ui_videoRecorder_action_start_settings_cameraLens_label,
									camera.cameraId
								),
							description = lensLabelMap[camera.lens],
							selected = videoSettings.selectedCamera == camera,
							onSelected = { selectCamera(camera) },
							icon = CAMERA_TYPE_ICON_MAP[camera.cameraType],
						)
					}
				}
			}
		}
	}
}
