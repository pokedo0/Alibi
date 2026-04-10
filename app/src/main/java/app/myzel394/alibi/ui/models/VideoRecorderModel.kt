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
import app.myzel394.alibi.helpers.CameraPosition
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
		val dualMode = settings.videoRecorderSettings.dualCameraEnabled
		val primaryPosition =
			if (dualMode) CameraPosition.BACK else CameraPosition.SINGLE

		batchesFolder = createBatchesFolder(context, settings, primaryPosition)

		super.startRecording(context, settings)
	}

	private fun createBatchesFolder(
		context: Context,
		settings: AppSettings,
		position: CameraPosition,
	): VideoBatchesFolder = when (settings.saveFolder) {
		null -> VideoBatchesFolder.viaInternalFolder(context, position)
		RECORDER_MEDIA_SELECTED_VALUE -> VideoBatchesFolder.viaMediaFolder(context, position)
		else -> VideoBatchesFolder.viaCustomFolder(
			context,
			DocumentFile.fromTreeUri(
				context,
				Uri.parse(settings.saveFolder)
			)!!,
			position,
		)
	}

	override fun onServiceConnected(service: VideoRecorderService) {
		// `onServiceConnected` may be called when reconnecting to the service,
		// so we only want to actually start the recording if the service is idle and thus
		// not already recording
		if (service.state == RecorderState.IDLE) {
			isStartingRecording = true

			// For dual camera mode, create and attach the secondary (front) folder
			settings?.let { appSettings ->
				if (appSettings.videoRecorderSettings.dualCameraEnabled) {
					service.secondaryBatchesFolder = createBatchesFolder(
						context = service,
						settings = appSettings,
						position = CameraPosition.FRONT,
					).also { it.initFolders() }
				} else {
					service.secondaryBatchesFolder = null
				}
			}

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
