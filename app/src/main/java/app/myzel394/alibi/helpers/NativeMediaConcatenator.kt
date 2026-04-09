package app.myzel394.alibi.helpers

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.nio.ByteBuffer

sealed class InputSource {
	data class FilePath(val path: String) : InputSource()
	data class ContentUri(val uri: Uri) : InputSource()
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
			try {
				concatenateInternal(context, inputSources, muxer, onProgress)
			} finally {
				runCatching { muxer.stop() }
				runCatching { muxer.release() }
			}
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
			try {
				concatenateInternal(context, inputSources, muxer, onProgress)
			} finally {
				runCatching { muxer.stop() }
				runCatching { muxer.release() }
			}
		}

		private fun concatenateInternal(
			context: Context,
			inputSources: List<InputSource>,
			muxer: MediaMuxer,
			onProgress: (Float) -> Unit,
		) {
			// Detect tracks from first file
			val probeExtractor = createExtractor(context, inputSources.first())
			val trackCount = probeExtractor.trackCount
			if (trackCount == 0) {
				probeExtractor.release()
				throw ConcatenationException("First input file has no tracks")
			}

			// Map extractor track indices to muxer track indices
			val trackFormats = mutableListOf<MediaFormat>()
			val muxerTrackIndices = mutableListOf<Int>()
			for (i in 0 until trackCount) {
				val format = probeExtractor.getTrackFormat(i)
				trackFormats.add(format)
				val muxerTrack = muxer.addTrack(format)
				muxerTrackIndices.add(muxerTrack)
			}
			probeExtractor.release()

			muxer.start()

			val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
			val bufferInfo = MediaCodec.BufferInfo()
			var cumulativeTimeUs = 0L
			val totalFiles = inputSources.size

			for ((fileIndex, source) in inputSources.withIndex()) {
				val extractor = createExtractor(context, source)

				// Select all tracks
				for (i in 0 until extractor.trackCount) {
					extractor.selectTrack(i)
				}

				var lastSampleTimeUs = 0L

				while (true) {
					buffer.clear()
					val sampleSize = extractor.readSampleData(buffer, 0)
					if (sampleSize < 0) break

					val trackIndex = extractor.sampleTrackIndex
					if (trackIndex < 0 || trackIndex >= muxerTrackIndices.size) {
						extractor.advance()
						continue
					}

					val sampleTimeUs = extractor.sampleTime
					val adjustedTimeUs = sampleTimeUs + cumulativeTimeUs

					bufferInfo.apply {
						offset = 0
						size = sampleSize
						presentationTimeUs = adjustedTimeUs
						flags = extractor.sampleFlags
					}

					muxer.writeSampleData(muxerTrackIndices[trackIndex], buffer, bufferInfo)

					if (sampleTimeUs > lastSampleTimeUs) {
						lastSampleTimeUs = sampleTimeUs
					}

					extractor.advance()
				}

				// Offset next file's timestamps by this file's duration
				// Add a small gap (1000 µs = 1ms) to avoid timestamp collisions
				cumulativeTimeUs += lastSampleTimeUs + 1000

				extractor.release()

				// Report progress per file
				onProgress((fileIndex + 1).toFloat() / totalFiles)
			}

			Log.i(TAG, "Concatenation complete: $totalFiles files, ${cumulativeTimeUs / 1_000_000}s total")
		}

		private fun createExtractor(context: Context, source: InputSource): MediaExtractor {
			val extractor = MediaExtractor()
			when (source) {
				is InputSource.FilePath -> extractor.setDataSource(source.path)
				is InputSource.ContentUri -> {
					val pfd = context.contentResolver.openFileDescriptor(source.uri, "r")
						?: throw ConcatenationException("Cannot open URI: ${source.uri}")
					extractor.setDataSource(pfd.fileDescriptor)
					pfd.close()
				}
			}
			return extractor
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
