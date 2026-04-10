package app.myzel394.alibi.services

import app.myzel394.alibi.db.AppSettings
import app.myzel394.alibi.helpers.BatchesFolder
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

abstract class IntervalRecorderService<I, B : BatchesFolder> :
    RecorderService() {
    protected var counter = 0L
        private set

    // Tracks the index of the currently locked file
    private var lockedIndex: Long? = null

    lateinit var settings: AppSettings

    private var cycleFuture: ScheduledFuture<*>? = null

    abstract var batchesFolder: B

    var onBatchesFolderNotAccessible: () -> Unit = {}

    abstract fun getRecordingInformation(): I

    // When saving the recording, the files should be locked.
    // This prevents the service from deleting the currently available files, so that
    // they can be safely used to save the recording.
    // Once finished, make sure to unlock the files using `unlockFiles`.
    //
    // Idempotent: if already locked, this is a no-op so later calls don't
    // shift the lock forward and accidentally expose the originally locked
    // batch to cleanup.
    fun lockFiles() {
        if (lockedIndex == null) {
            lockedIndex = counter
        }
    }

    // Unlocks and deletes the files that were locked using `lockFiles`.
    fun unlockFiles(cleanupFiles: Boolean = false) {
        if (cleanupFiles) {
            batchesFolder.deleteRecordings(0..<lockedIndex!!)
        }

        lockedIndex = null
    }

    // Make overrideable
    open fun startNewCycle() {
        counter += 1
    }

    private fun createTimer() {
        cycleFuture = sharedExecutor.scheduleAtFixedRate(
            ::startNewCycle,
            0,
            settings.intervalDuration,
            TimeUnit.MILLISECONDS
        )
    }

    override fun start() {
        super.start()

        batchesFolder.initFolders()

        if (!batchesFolder.checkIfFolderIsAccessible()) {
            onBatchesFolderNotAccessible()

            throw AvoidErrorDialogError()
        }

        createTimer()
    }

    override fun pause() {
        super.pause()
        cycleFuture?.cancel(false)
    }

    override fun resume() {
        super.resume()
        createTimer()
    }

    override suspend fun stop() {
        cycleFuture?.cancel(false)
        batchesFolder.cleanup()
        super.stop()
    }

    fun clearAllRecordings() {
        batchesFolder.deleteRecordings()
    }

    /**
     * Deletes batch files that exceed the configured [AppSettings.maxDuration] window.
     *
     * MUST be called after the previous batch recording has been finalized
     * (stopped and its file fully written to disk). Calling this while a
     * recorder still has the file open causes [java.io.File.delete] to
     * silently fail on some Android versions, leaking old batches into
     * subsequent save operations.
     *
     * Subclasses that own multiple batches folders should override to
     * clean up each one.
     */
    protected open fun cleanupExpiredBatches() {
        val cutoff = computeExpiredBatchCutoff() ?: return
        batchesFolder.deleteRecordings(0..cutoff)
    }

    /**
     * Computes the highest batch counter to delete (inclusive) based on
     * [AppSettings.maxDuration] / [AppSettings.intervalDuration] and the
     * currently locked index. Returns null if nothing should be deleted.
     *
     * Keeps one EXTRA completed batch beyond what timeMultiplier alone
     * would suggest. Without this, at the start of a new cycle the buffer
     * would contain only the freshly-started (partial) batch plus
     * timeMultiplier-1 older completed batches, so the effective minimum
     * rolling window would be `(timeMultiplier - 1) * intervalDuration`,
     * which on the default 60s/60s settings works out to 0 seconds.
     *
     * By keeping the extra batch we guarantee at least `maxDuration`
     * worth of content is available at every moment, at the cost of
     * occasionally saving up to `maxDuration + intervalDuration` seconds.
     */
    protected fun computeExpiredBatchCutoff(): Long? {
        val timeMultiplier = settings.maxDuration / settings.intervalDuration
        // Keep `timeMultiplier + 1` batches (current in-progress + timeMultiplier
        // completed). This ensures the rolling window is never smaller than
        // `maxDuration` seconds.
        val normalCutoff = counter - timeMultiplier - 1

        // If a save is in progress the locked batch must not be deleted.
        // Clamp the cutoff so it stays strictly below the locked index.
        val cutoff = lockedIndex?.let { minOf(normalCutoff, it - 1) } ?: normalCutoff

        return if (cutoff <= 0) null else cutoff
    }
}