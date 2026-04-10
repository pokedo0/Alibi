package app.myzel394.alibi.services

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.myzel394.alibi.NotificationHelper
import app.myzel394.alibi.db.RecordingInformation
import app.myzel394.alibi.enums.RecorderState
import app.myzel394.alibi.helpers.BatchesFolder
import app.myzel394.alibi.helpers.VideoBatchesFolder
import app.myzel394.alibi.ui.SUPPORTS_SAVING_VIDEOS_IN_CUSTOM_FOLDERS
import app.myzel394.alibi.ui.SUPPORTS_SCOPED_STORAGE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.properties.Delegates

class VideoRecorderService :
    IntervalRecorderService<RecordingInformation, VideoBatchesFolder>() {
    override var batchesFolder = VideoBatchesFolder.viaInternalFolder(this)

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    // Used to listen and check if the camera is available
    private var _cameraAvailableListener = CompletableDeferred<Unit>()
    private lateinit var _videoFinalizerListener: CompletableDeferred<Unit>

    // Finalizer for the currently active recording. Set when a recording starts,
    // completed when that recording receives its Finalize event.
    @Volatile
    private var activeRecordingFinalizer: CompletableDeferred<Unit>? = null

    // Finalizer for the most recently stopped recording.
    // Await this before reading batch files to ensure the last batch is fully written.
    @Volatile
    private var previousRecordingFinalizer: CompletableDeferred<Unit>? = null

    // Absolute last completer that can be awaited to ensure that the camera is closed
    private var _cameraCloserListener = CompletableDeferred<Unit>()

    private lateinit var selectedCamera: CameraSelector
    private var enableAudio by Delegates.notNull<Boolean>()
    private var cameraTypeHint: String? = null

    var onCameraControlAvailable = {}

    var cameraControl: CameraControl? = null
        private set

    @OptIn(ExperimentalCamera2Interop::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "init") {
            val logicalId = intent.getStringExtra("logicalCameraId")
            val cameraIdString = intent.getStringExtra("cameraIdString")
            // Use logical camera ID for CameraX selection (it only knows logical cameras)
            val selectorId = logicalId ?: cameraIdString
            selectedCamera = if (selectorId != null) {
                CameraSelector.Builder()
                    .addCameraFilter { cameras ->
                        cameras.filter { cameraInfo ->
                            Camera2CameraInfo.from(cameraInfo).cameraId == selectorId
                        }
                    }
                    .build()
            } else {
                // Legacy fallback: select by lens facing direction
                CameraSelector.Builder().requireLensFacing(
                    intent.getIntExtra("cameraID", CameraSelector.LENS_FACING_BACK)
                ).build()
            }
            cameraTypeHint = intent.getStringExtra("cameraType")
            enableAudio = intent.getBooleanExtra("enableAudio", true)
        }

        return super.onStartCommand(intent, flags, startId)
    }

    override fun start() {
        super.start()

        scope.launch {
            openCamera()
        }
    }

    override suspend fun stop() {
        super.stop()

        stopActiveRecording()

        // Camera can only be closed after the recording has been finalized
        withTimeoutOrNull(CAMERA_CLOSE_TIMEOUT) {
            _videoFinalizerListener.await()
        }

        closeCamera()

        withTimeoutOrNull(CAMERA_CLOSE_TIMEOUT) {
            _cameraCloserListener.await()
        }
    }

    override fun pause() {
        super.pause()

        stopActiveRecording()
    }

    override fun startForegroundService() {
        ServiceCompat.startForeground(
            this,
            NotificationHelper.RECORDER_CHANNEL_NOTIFICATION_ID,
            getNotificationHelper().buildStartingNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (enableAudio)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                0
            },
        )
    }

    /**
     * Awaits finalization of the most recently stopped batch recording.
     * Call this before reading batch files for concatenation to ensure
     * the last batch's MP4 moov atom has been written to disk.
     */
    suspend fun awaitPreviousBatchFinalization() {
        withTimeoutOrNull(CAMERA_CLOSE_TIMEOUT) {
            previousRecordingFinalizer?.await()
        }
    }

    @SuppressLint("MissingPermission")
    override fun startNewCycle() {
        super.startNewCycle()

        fun action() {
            // The currently-active finalizer belongs to the recording we're
            // about to stop. Save it as the "previous" one so save flows
            // can await it.
            previousRecordingFinalizer = activeRecordingFinalizer

            stopActiveRecording()

            // Create a new finalizer for the new recording. The listener
            // captures it via closure, so it survives even when the instance
            // field is later reassigned on the next cycle.
            val newFinalizer = CompletableDeferred<Unit>()
            activeRecordingFinalizer = newFinalizer

            val newRecording = prepareVideoRecording()

            _videoFinalizerListener = CompletableDeferred()

            activeRecording = newRecording.start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    newFinalizer.complete(Unit)
                    if (this@VideoRecorderService.state == RecorderState.STOPPED ||
                        this@VideoRecorderService.state == RecorderState.PAUSED) {
                        _videoFinalizerListener.complete(Unit)
                    }
                }
            }
        }

        if (_cameraAvailableListener.isCompleted) {
            action()
        } else {
            // Race condition of `startNewCycle` being called before `invokeOnCompletion`
            // has been called can be ignored, as the camera usually opens within 5 seconds
            // and the interval can't be set shorter than 10 seconds.
            _cameraAvailableListener.invokeOnCompletion {
                action()
            }
        }
    }


    // Runs a function in the main thread
    private fun runOnMain(callback: () -> Unit) {
        val mainHandler = ContextCompat.getMainExecutor(this)

        mainHandler.execute(callback)
    }

    private fun buildRecorder() = Recorder.Builder()
        .setQualitySelector(
            settings.videoRecorderSettings.getQualitySelector()
                ?: QualitySelector.from(Quality.HD)
        )
        .apply {
            val bitRate = settings.videoRecorderSettings.targetedVideoBitRate
                ?: DEFAULT_VIDEO_BITRATE
            setTargetVideoEncodingBitRate(bitRate)
        }
        .build()

    private fun buildVideoCapture(recorder: Recorder) = VideoCapture.Builder(recorder)
        .apply {
            val frameRate = settings.videoRecorderSettings.targetFrameRate
                ?: DEFAULT_FRAME_RATE
            setTargetFrameRate(Range(frameRate, frameRate))
        }
        .build()

    // Open the camera.
    // Used to open it for a longer time, shouldn't be called when pausing / resuming.
    // This should only be called when starting a recording.
    private suspend fun openCamera() {
        cameraProvider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(this@VideoRecorderService).get()
        }

        val recorder = buildRecorder()
        videoCapture = buildVideoCapture(recorder)

        runOnMain {
            try {
                camera = cameraProvider!!.bindToLifecycle(
                    this,
                    selectedCamera,
                    videoCapture
                )

                // Apply zoom to select the physical camera within a logical multi-camera
                cameraTypeHint?.let { type ->
                    val zoomState = camera!!.cameraInfo.zoomState.value
                    if (zoomState != null) {
                        val ratio = when (type) {
                            "WIDE_ANGLE" -> zoomState.minZoomRatio
                            "TELEPHOTO" -> zoomState.maxZoomRatio
                            else -> null
                        }
                        ratio?.let { camera!!.cameraControl.setZoomRatio(it) }
                    }
                }

                cameraControl = CameraControl(camera!!).also {
                    it.init()
                }
                onCameraControlAvailable()

                _cameraAvailableListener.complete(Unit)
            } catch (error: IllegalArgumentException) {
                // Always unblock awaiters so startNewCycle() doesn't hang
                if (!_cameraAvailableListener.isCompleted) {
                    _cameraAvailableListener.completeExceptionally(error)
                }
                onError()
            }
        }
    }

    // Close the camera
    // Used to close it finally, shouldn't be called when pausing / resuming.
    // This should only be called after recording has finished.
    private fun closeCamera() {
        runOnMain {
            runCatching {
                cameraProvider?.unbindAll()
            }
            _cameraCloserListener.complete(Unit)

            // Doesn't need to run on main thread, but
            // if it runs outside `runOnMain`, `cameraProvider` is already null
            // before it's unbound
            cameraProvider = null
            videoCapture = null
            camera = null
        }
    }

    // `resume` override not needed as `startNewCycle` is called by `IntervalRecorderService`

    private fun stopActiveRecording() {
        runCatching {
            activeRecording?.stop()
        }
    }

    private fun getNameForMediaFile() =
        "${batchesFolder.mediaPrefix}$counter.${settings.videoRecorderSettings.fileExtension}"

    @SuppressLint("MissingPermission", "NewApi")
    private fun prepareVideoRecording() =
        videoCapture!!.output
            .let {
                if (batchesFolder.type == BatchesFolder.BatchType.CUSTOM && SUPPORTS_SAVING_VIDEOS_IN_CUSTOM_FOLDERS) {
                    it.prepareRecording(
                        this,
                        FileDescriptorOutputOptions.Builder(
                            batchesFolder.asCustomGetParcelFileDescriptor(
                                counter,
                                settings.videoRecorderSettings.fileExtension
                            )
                        ).build()
                    )
                } else if (batchesFolder.type == BatchesFolder.BatchType.MEDIA) {
                    if (SUPPORTS_SCOPED_STORAGE) {
                        val name = getNameForMediaFile()

                        it.prepareRecording(
                            this,
                            MediaStoreOutputOptions
                                .Builder(
                                    contentResolver,
                                    batchesFolder.scopedMediaContentUri,
                                )
                                .setContentValues(
                                    batchesFolder.asMediaGetScopedStorageContentValues(
                                        name
                                    )
                                )
                                .build()
                        )
                    } else {
                        val name = getNameForMediaFile()

                        it.prepareRecording(
                            this,
                            FileOutputOptions
                                .Builder(batchesFolder.asMediaGetLegacyFile(name))
                                .build()
                        )
                    }
                } else {
                    it.prepareRecording(
                        this,
                        FileOutputOptions.Builder(
                            batchesFolder.asInternalGetFile(
                                counter,
                                settings.videoRecorderSettings.fileExtension
                            ).apply {
                                createNewFile()
                            }
                        ).build()
                    )
                }
            }
            .run {
                if (enableAudio) {
                    return@run withAudioEnabled()
                }

                this
            }

    override fun getRecordingInformation() =
        RecordingInformation(
            folderPath = batchesFolder.exportFolderForSettings(),
            recordingStart = recordingStart,
            maxDuration = settings.maxDuration,
            batchesAmount = batchesFolder.getBatchInputSources().size,
            fileExtension = settings.videoRecorderSettings.fileExtension,
            intervalDuration = settings.intervalDuration,
            type = RecordingInformation.Type.VIDEO,
        )

    companion object {
        const val CAMERA_CLOSE_TIMEOUT = 20000L
        const val DEFAULT_FRAME_RATE = 24
        const val DEFAULT_VIDEO_BITRATE = 2 * 1000 * 1000 // 2 Mbps
    }

    class CameraControl(
        val camera: Camera,
        // Save state for optimistic updates
        var torchEnabled: Boolean = false,
    ) {
        fun init() {
            torchEnabled = camera.cameraInfo.torchState.value == TorchState.ON
        }

        fun enableTorch() {
            torchEnabled = true
            camera.cameraControl.enableTorch(true)
        }

        fun disableTorch() {
            torchEnabled = false
            camera.cameraControl.enableTorch(false)
        }

        fun isHardwareTorchReallyEnabled(): Boolean {
            return camera.cameraInfo.torchState.value == TorchState.ON
        }

        fun hasTorchAvailable() = camera.cameraInfo.hasFlashUnit()
    }
}
