package app.myzel394.alibi.helpers

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.nio.ByteBuffer

sealed class InputSource {
	data class FilePath(val path: String) : InputSource()
	data class ContentUri(val uri: Uri) : InputSource()
}

/**
 * Holds a [MediaExtractor] alongside the [ParcelFileDescriptor] that backs it
 * for content URI sources, so both can be released together.
 */
private class ExtractorHandle(
	val extractor: MediaExtractor,
	private val pfd: ParcelFileDescriptor? = null,
) {
	fun release() {
		runCatching { extractor.release() }
		runCatching { pfd?.close() }
	}
}

class NativeMediaConcatenator private constructor() {

	class ConcatenationException(message: String, cause: Throwable? = null) :
		Exception(message, cause)

	companion object {
		private const val TAG = "NativeMediaConcatenator"
		private const val BUFFER_SIZE = 1024 * 1024 // 1 MB sample buffer

		/**
		 * Concatenates multiple media files (audio or video) into a single output.
		 *
		 * Uses [MediaExtractor] to read samples from each input file and
		 * [MediaMuxer] to write them into the output, adjusting timestamps
		 * so the files play back sequentially.
		 *
		 * All input files must share the same codec and track configuration.
		 */
		suspend fun concatenateFiles(
			context: Context,
			inputSources: List<InputSource>,
			outputPath: String,
			outputFormat: Int,
			onProgress: (Float) -> Unit = {},
		): Unit = withContext(Dispatchers.IO) {
			if (inputSources.isEmpty()) {
				throw ConcatenationException("No input files provided")
			}

			val muxer = MediaMuxer(outputPath, outputFormat)
			runConcatenation(context, inputSources, muxer, onProgress)
		}

		/**
		 * Overload accepting a [FileDescriptor] for SAF/scoped storage output.
		 */
		suspend fun concatenateFiles(
			context: Context,
			inputSources: List<InputSource>,
			outputFd: FileDescriptor,
			outputFormat: Int,
			onProgress: (Float) -> Unit = {},
		): Unit = withContext(Dispatchers.IO) {
			if (inputSources.isEmpty()) {
				throw ConcatenationException("No input files provided")
			}

			val muxer = MediaMuxer(outputFd, outputFormat)
			runConcatenation(context, inputSources, muxer, onProgress)
		}

		private fun runConcatenation(
			context: Context,
			inputSources: List<InputSource>,
			muxer: MediaMuxer,
			onProgress: (Float) -> Unit,
		) {
			var muxerStarted = false
			try {
				concatenateInternal(context, inputSources, muxer, onProgress) {
					muxerStarted = it
				}
			} catch (t: Throwable) {
				Log.e(TAG, "Concatenation failed", t)
				throw if (t is ConcatenationException) t
				else ConcatenationException("Concatenation failed: ${t.message}", t)
			} finally {
				if (muxerStarted) {
					runCatching { muxer.stop() }
				}
				runCatching { muxer.release() }
			}
		}

		private fun concatenateInternal(
			context: Context,
			inputSources: List<InputSource>,
			muxer: MediaMuxer,
			onProgress: (Float) -> Unit,
			onMuxerStarted: (Boolean) -> Unit,
		) {
			// Detect tracks from first file
			val probeHandle = createExtractor(context, inputSources.first())
			val muxerTrackIndices: List<Int>
			var orientationHint = 0
			try {
				val trackCount = probeHandle.extractor.trackCount
				if (trackCount == 0) {
					throw ConcatenationException("First input file has no tracks")
				}

				// Map extractor track indices to muxer track indices
				muxerTrackIndices = (0 until trackCount).map { i ->
					val format = probeHandle.extractor.getTrackFormat(i)
					// Capture rotation metadata from the first video track we find
					if (orientationHint == 0 &&
						format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
					) {
						orientationHint = runCatching {
							format.getInteger(MediaFormat.KEY_ROTATION)
						}.getOrDefault(0)
					}
					muxer.addTrack(format)
				}
			} finally {
				probeHandle.release()
			}

			// Apply the rotation before starting the muxer so player apps render
			// the output with the correct orientation. Without this, vertical
			// recordings play back sideways.
			if (orientationHint != 0) {
				runCatching { muxer.setOrientationHint(orientationHint) }
			}

			muxer.start()
			onMuxerStarted(true)

			val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
			val bufferInfo = MediaCodec.BufferInfo()
			var cumulativeTimeUs = 0L
			val totalFiles = inputSources.size

			for ((fileIndex, source) in inputSources.withIndex()) {
				val handle = try {
					createExtractor(context, source)
				} catch (t: Throwable) {
					Log.w(TAG, "Skipping unreadable input: $source", t)
					continue
				}

				try {
					// Select all tracks
					for (i in 0 until handle.extractor.trackCount) {
						handle.extractor.selectTrack(i)
					}

					var lastSampleTimeUs = 0L

					while (true) {
						buffer.clear()
						val sampleSize = handle.extractor.readSampleData(buffer, 0)
						if (sampleSize < 0) break

						val extractorTrackIndex = handle.extractor.sampleTrackIndex
						if (extractorTrackIndex < 0 || extractorTrackIndex >= muxerTrackIndices.size) {
							handle.extractor.advance()
							continue
						}

						val sampleTimeUs = handle.extractor.sampleTime
						val adjustedTimeUs = sampleTimeUs + cumulativeTimeUs

						bufferInfo.apply {
							offset = 0
							size = sampleSize
							presentationTimeUs = adjustedTimeUs
							flags = handle.extractor.sampleFlags
						}

						try {
							muxer.writeSampleData(
								muxerTrackIndices[extractorTrackIndex],
								buffer,
								bufferInfo,
							)
						} catch (t: IllegalStateException) {
							// Muxer rejected the sample (e.g. non-monotonic timestamp).
							// Log and keep going to salvage as much as possible.
							Log.w(TAG, "Muxer rejected sample at $adjustedTimeUs us", t)
						}

						if (sampleTimeUs > lastSampleTimeUs) {
							lastSampleTimeUs = sampleTimeUs
						}

						handle.extractor.advance()
					}

					// Offset next file's timestamps by this file's duration.
					// Add a 1ms gap so the first sample of the next file doesn't
					// collide with the last sample of the previous one.
					cumulativeTimeUs += lastSampleTimeUs + 1000
				} finally {
					handle.release()
				}

				onProgress((fileIndex + 1).toFloat() / totalFiles)
			}

			Log.i(
				TAG,
				"Concatenation complete: $totalFiles files, ${cumulativeTimeUs / 1_000_000}s total",
			)
		}

		private fun createExtractor(context: Context, source: InputSource): ExtractorHandle {
			val extractor = MediaExtractor()
			return try {
				when (source) {
					is InputSource.FilePath -> {
						extractor.setDataSource(source.path)
						ExtractorHandle(extractor)
					}

					is InputSource.ContentUri -> {
						val pfd = context.contentResolver.openFileDescriptor(source.uri, "r")
							?: throw ConcatenationException("Cannot open URI: ${source.uri}")
						try {
							extractor.setDataSource(pfd.fileDescriptor)
						} catch (t: Throwable) {
							runCatching { pfd.close() }
							throw t
						}
						// Keep the PFD alive for the extractor's lifetime.
						ExtractorHandle(extractor, pfd)
					}
				}
			} catch (t: Throwable) {
				runCatching { extractor.release() }
				throw t
			}
		}

		/**
		 * Determines the appropriate [MediaMuxer.OutputFormat] for the given
		 * file extension.
		 */
		fun outputFormatForExtension(extension: String): Int = when (extension) {
			"mp4", "m4a" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
			"webm" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
			"3gp" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP
			"ogg" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG
			else -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
		}
	}
}
