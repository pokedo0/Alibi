package app.myzel394.alibi.ui.models

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import app.myzel394.alibi.db.AppSettings
import app.myzel394.alibi.db.RecordingInformation
import app.myzel394.alibi.enums.RecorderState
import app.myzel394.alibi.helpers.Doctor
import app.myzel394.alibi.helpers.VideoBatchesFolder
import app.myzel394.alibi.services.VideoRecorderService
import app.myzel394.alibi.ui.RECORDER_MEDIA_SELECTED_VALUE
import app.myzel394.alibi.ui.utils.CameraInfo
import app.myzel394.alibi.ui.utils.PermissionHelper

class VideoRecorderModel :
	BaseRecorderModel<RecordingInformation, VideoBatchesFolder, VideoRecorderService>() {
	override var batchesFolder: VideoBatchesFolder? = null
	override val intentClass = VideoRecorderService::class.java

	var enableAudio by mutableStateOf(true)
	var selectedCamera by mutableStateOf<CameraInfo?>(null)
	private var availableCameras: List<CameraInfo> = emptyList()

	override val isInRecording: Boolean
		get() = super.isInRecording

	var isStartingRecording by mutableStateOf(true)
		private set

	val cameraSelector: CameraSelector
		get() = selectedCamera?.buildCameraSelector()
			?: CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

	fun init(context: Context, settings: AppSettings? = null) {
		enableAudio = PermissionHelper.hasGranted(context, Manifest.permission.RECORD_AUDIO)

		availableCameras = CameraInfo.queryAvailableCameras(context)
		val preferredId = settings?.videoRecorderSettings?.preferredCameraId
		selectedCamera = availableCameras.firstOrNull { it.cameraId == preferredId }
			?: availableCameras.firstOrNull {
				it.lens == CameraInfo.Lens.BACK && it.cameraType == CameraInfo.CameraType.MAIN
			}
			?: availableCameras.firstOrNull { it.lens == CameraInfo.Lens.BACK }
			?: availableCameras.firstOrNull()
	}

	override fun startRecording(context: Context, settings: AppSettings) {
		batchesFolder = when (settings.saveFolder) {
			null -> VideoBatchesFolder.viaInternalFolder(context)
			RECORDER_MEDIA_SELECTED_VALUE -> VideoBatchesFolder.viaMediaFolder(context)
			else -> VideoBatchesFolder.viaCustomFolder(
				context,
				DocumentFile.fromTreeUri(
					context,
					Uri.parse(settings.saveFolder)
				)!!
			)
		}

		super.startRecording(context, settings)
	}

	override fun onServiceConnected(service: VideoRecorderService) {
		// `onServiceConnected` may be called when reconnecting to the service,
		// so we only want to actually start the recording if the service is idle and thus
		// not already recording
		if (service.state == RecorderState.IDLE) {
			isStartingRecording = true

			service.clearAllRecordings()
			service.startRecording()
			onRecordingStart()
		} else {
			isStartingRecording = false
		}

		service.onCameraControlAvailable = {
			isStartingRecording = false
		}

		recorderState = service.state
		recordingTime = service.recordingTime
	}

	override fun handleIntent(intent: Intent) =
		intent.apply {
			val camera = selectedCamera
			if (camera != null) {
				putExtra("cameraIdString", camera.cameraId)
				if (camera.logicalCameraId != null) {
					putExtra("logicalCameraId", camera.logicalCameraId)
				}
				putExtra("cameraType", camera.cameraType.name)
			}
			// Legacy fallback: always include lens facing int
			putExtra(
				"cameraID",
				camera?.lens?.androidValue ?: CameraSelector.LENS_FACING_BACK
			)
			putExtra("enableAudio", enableAudio)
		}
}
