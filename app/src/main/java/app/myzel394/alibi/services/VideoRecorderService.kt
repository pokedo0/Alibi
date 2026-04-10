package app.myzel394.alibi.services

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.TorchState
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
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
import app.myzel394.alibi.helpers.CameraPosition
import app.myzel394.alibi.helpers.VideoBatchesFolder
import app.myzel394.alibi.ui.SUPPORTS_SAVING_VIDEOS_IN_CUSTOM_FOLDERS
import app.myzel394.alibi.ui.SUPPORTS_SCOPED_STORAGE
import app.myzel394.alibi.ui.utils.DualCameraSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.properties.Delegates

class VideoRecorderService :
    IntervalRecorderService<RecordingInformation, VideoBatchesFolder>() {
    override var batchesFolder = VideoBatchesFolder.viaInternalFolder(this)

    // Secondary batches folder for dual-camera front camera output.
    // Null in single-camera mode.
    var secondaryBatchesFolder: VideoBatchesFolder? = null

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    /**
     * Per-camera recording state. Holds everything needed to manage one
     * concurrent video stream (camera, video capture, active recording,
     * finalizer handles).
     */
    private class RecordingStream(val position: CameraPosition) {
        var camera: Camera? = null
        var videoCapture: VideoCapture<Recorder>? = null
        var activeRecording: Recording? = null

        @Volatile
        var activeRecordingFinalizer: CompletableDeferred<Unit>? = null

        @Volatile
        var previousRecordingFinalizer: CompletableDeferred<Unit>? = null
    }

    private var primary = RecordingStream(CameraPosition.SINGLE)
    private var secondary: RecordingStream? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var isDualMode = false

    // Used to listen and check if the camera is available
    private var _cameraAvailableListener = CompletableDeferred<Unit>()
    private lateinit var _videoFinalizerListener: CompletableDeferred<Unit>

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
     * Awaits finalization of the most recently stopped batch recording(s).
     * Call this before reading batch files for concatenation to ensure
     * each batch's MP4 moov atom has been written to disk.
     */
    suspend fun awaitPreviousBatchFinalization() {
        withTimeoutOrNull(CAMERA_CLOSE_TIMEOUT) {
            primary.previousRecordingFinalizer?.await()
            secondary?.previousRecordingFinalizer?.await()
        }
    }

    @SuppressLint("MissingPermission")
    override fun startNewCycle() {
        super.startNewCycle()

        fun action() {
            startCycleForStream(primary, batchesFolder)
            secondary?.let { stream ->
                secondaryBatchesFolder?.let { folder ->
                    startCycleForStream(stream, folder)
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

    @SuppressLint("MissingPermission")
    private fun startCycleForStream(stream: RecordingStream, folder: VideoBatchesFolder) {
        val videoCapture = stream.videoCapture ?: return

        // The currently-active finalizer belongs to the recording we're
        // about to stop. Save it so save flows can await it.
        stream.previousRecordingFinalizer = stream.activeRecordingFinalizer
        val finalizerForStoppedRecording = stream.activeRecordingFinalizer

        runCatching { stream.activeRecording?.stop() }

        val newFinalizer = CompletableDeferred<Unit>()
        stream.activeRecordingFinalizer = newFinalizer

        val newRecording = prepareVideoRecording(videoCapture, folder)

        // Keep the legacy service-wide finalizer in sync so stop() can await it.
        _videoFinalizerListener = CompletableDeferred()

        stream.activeRecording =
            newRecording.start(ContextCompat.getMainExecutor(this)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    newFinalizer.complete(Unit)
                    if (this@VideoRecorderService.state == RecorderState.STOPPED ||
                        this@VideoRecorderService.state == RecorderState.PAUSED
                    ) {
                        if (!_videoFinalizerListener.isCompleted) {
                            _videoFinalizerListener.complete(Unit)
                        }
                    }
                }
            }

        // After the PREVIOUS batch has finalized (its file is fully written),
        // it is finally safe to run the expired-batches cleanup. Running the
        // cleanup inside startNewCycle (before the recorder flushes) caused
        // File.delete() to silently fail on many Android versions, leaving
        // expired batches on disk and leaking into save flows.
        finalizerForStoppedRecording?.let { deferred ->
            scope.launch {
                runCatching { deferred.await() }
                runCatching { cleanupExpiredBatches() }
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
        cameraProvider = ProcessCameraProvider.awaitInstance(this@VideoRecorderService)

        val dualRequested = settings.videoRecorderSettings.dualCameraEnabled
        val dualPair: DualCameraSupport.SupportedPair? =
            if (dualRequested && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                DualCameraSupport.findSupportedPair(cameraProvider!!)
            } else null

        isDualMode = dualPair != null
        if (dualRequested && !isDualMode) {
            Log.w(TAG, "Dual camera requested but not supported, falling back to single camera")
        }

        runOnMain {
            try {
                if (isDualMode && dualPair != null) {
                    openDualCamera(dualPair)
                } else {
                    openSingleCamera()
                }

                cameraControl = primary.camera?.let {
                    CameraControl(it).also { cc -> cc.init() }
                }
                onCameraControlAvailable()

                _cameraAvailableListener.complete(Unit)
            } catch (error: IllegalArgumentException) {
                if (!_cameraAvailableListener.isCompleted) {
                    _cameraAvailableListener.completeExceptionally(error)
                }
                onError()
            }
        }
    }

    private fun openSingleCamera() {
        val recorder = buildRecorder()
        val videoCapture = buildVideoCapture(recorder)

        primary = RecordingStream(CameraPosition.SINGLE).apply {
            this.videoCapture = videoCapture
        }
        primary.camera = cameraProvider!!.bindToLifecycle(
            this,
            selectedCamera,
            videoCapture,
        )

        // Apply zoom to select the physical camera within a logical multi-camera
        cameraTypeHint?.let { type ->
            val zoomState = primary.camera?.cameraInfo?.zoomState?.value
            if (zoomState != null) {
                val ratio = when (type) {
                    "WIDE_ANGLE" -> zoomState.minZoomRatio
                    "TELEPHOTO" -> zoomState.maxZoomRatio
                    else -> null
                }
                ratio?.let { primary.camera?.cameraControl?.setZoomRatio(it) }
            }
        }
    }

    private fun openDualCamera(pair: DualCameraSupport.SupportedPair) {
        val primaryRecorder = buildRecorder()
        val primaryVideoCapture = buildVideoCapture(primaryRecorder)
        val primaryUseCaseGroup = UseCaseGroup.Builder()
            .addUseCase(primaryVideoCapture)
            .build()

        val secondaryRecorder = buildRecorder()
        val secondaryVideoCapture = buildVideoCapture(secondaryRecorder)
        val secondaryUseCaseGroup = UseCaseGroup.Builder()
            .addUseCase(secondaryVideoCapture)
            .build()

        val primaryConfig = ConcurrentCamera.SingleCameraConfig(
            pair.backSelector,
            primaryUseCaseGroup,
            this,
        )
        val secondaryConfig = ConcurrentCamera.SingleCameraConfig(
            pair.frontSelector,
            secondaryUseCaseGroup,
            this,
        )

        val concurrent = cameraProvider!!.bindToLifecycle(
            listOf(primaryConfig, secondaryConfig)
        )

        primary = RecordingStream(CameraPosition.BACK).apply {
            this.videoCapture = primaryVideoCapture
            this.camera = concurrent.cameras.getOrNull(0)
        }
        secondary = RecordingStream(CameraPosition.FRONT).apply {
            this.videoCapture = secondaryVideoCapture
            this.camera = concurrent.cameras.getOrNull(1)
        }

        // Ensure caller-provided secondary batches folder exists
        if (secondaryBatchesFolder == null) {
            secondaryBatchesFolder = VideoBatchesFolder.viaInternalFolder(
                this,
                CameraPosition.FRONT,
            ).also { it.initFolders() }
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
            primary = RecordingStream(CameraPosition.SINGLE)
            secondary = null
        }
    }

    // `resume` override not needed as `startNewCycle` is called by `IntervalRecorderService`

    private fun stopActiveRecording() {
        runCatching { primary.activeRecording?.stop() }
        runCatching { secondary?.activeRecording?.stop() }
    }

    private fun getNameForMediaFile(folder: VideoBatchesFolder) =
        "${folder.mediaPrefix}$counter.${settings.videoRecorderSettings.fileExtension}"

    @SuppressLint("MissingPermission", "NewApi")
    private fun prepareVideoRecording(
        videoCapture: VideoCapture<Recorder>,
        folder: VideoBatchesFolder,
    ) = videoCapture.output
        .let { output ->
            if (folder.type == BatchesFolder.BatchType.CUSTOM && SUPPORTS_SAVING_VIDEOS_IN_CUSTOM_FOLDERS) {
                output.prepareRecording(
                    this,
                    FileDescriptorOutputOptions.Builder(
                        folder.asCustomGetParcelFileDescriptor(
                            counter,
                            settings.videoRecorderSettings.fileExtension
                        )
                    ).build()
                )
            } else if (folder.type == BatchesFolder.BatchType.MEDIA) {
                if (SUPPORTS_SCOPED_STORAGE) {
                    val name = getNameForMediaFile(folder)

                    output.prepareRecording(
                        this,
                        MediaStoreOutputOptions
                            .Builder(
                                contentResolver,
                                folder.scopedMediaContentUri,
                            )
                            .setContentValues(
                                folder.asMediaGetScopedStorageContentValues(name)
                            )
                            .build()
                    )
                } else {
                    val name = getNameForMediaFile(folder)

                    output.prepareRecording(
                        this,
                        FileOutputOptions
                            .Builder(folder.asMediaGetLegacyFile(name))
                            .build()
                    )
                }
            } else {
                output.prepareRecording(
                    this,
                    FileOutputOptions.Builder(
                        folder.asInternalGetFile(
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

    fun isDualCameraActive(): Boolean = isDualMode

    /**
     * Also cleans up the secondary dual-camera batches folder, if present.
     */
    override fun cleanupExpiredBatches() {
        super.cleanupExpiredBatches()
        secondaryBatchesFolder?.let { folder ->
            val cutoff = computeExpiredBatchCutoff() ?: return
            folder.deleteRecordings(0..cutoff)
        }
    }

    companion object {
        private const val TAG = "VideoRecorderService"
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
