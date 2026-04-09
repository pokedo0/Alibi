package app.myzel394.alibi.helpers

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import app.myzel394.alibi.ui.AUDIO_RECORDING_BATCHES_SUBFOLDER_NAME
import app.myzel394.alibi.ui.MEDIA_SUBFOLDER_NAME
import app.myzel394.alibi.ui.RECORDER_INTERNAL_SELECTED_VALUE
import app.myzel394.alibi.ui.RECORDER_MEDIA_SELECTED_VALUE
import java.io.File
import java.io.FileDescriptor
import java.time.LocalDateTime

class AudioBatchesFolder(
	override val context: Context,
	override val type: BatchType,
	override val customFolder: DocumentFile? = null,
	override val subfolderName: String = AUDIO_RECORDING_BATCHES_SUBFOLDER_NAME,
) : BatchesFolder(
	context,
	type,
	customFolder,
	subfolderName,
) {
	override val scopedMediaContentUri: Uri = SCOPED_MEDIA_CONTENT_URI
	override val legacyMediaFolder = LEGACY_MEDIA_FOLDER

	/**
	 * AAC-ADTS batches (.aac) get merged into MP4 container (.m4a)
	 * since MediaMuxer doesn't support AAC-ADTS as output format.
	 * Other formats keep their original extension.
	 */
	override val mergedFileExtension: String
		get() = "m4a" // MP4 container works for all audio codecs via MediaMuxer

	private var customFileFileDescriptor: ParcelFileDescriptor? = null
	private var mediaFileFileDescriptor: ParcelFileDescriptor? = null

	override fun getOutputPath(
		date: LocalDateTime,
		extension: String,
		fileName: String,
	): String {
		return when (type) {
			BatchType.INTERNAL -> asInternalGetOutputFile(fileName).absolutePath

			BatchType.CUSTOM -> {
				val file = customFolder!!.findFile(fileName)
					?: customFolder.createFile("audio/$extension", fileName)!!
				// For custom folders, we need to write via FileDescriptor
				// Return the URI string as path — concatenate() handles it
				file.uri.toString()
			}

			BatchType.MEDIA -> {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					val mediaUri = getOrCreateMediaFile(
						name = fileName,
						mimeType = "audio/$extension",
						relativePath = BASE_SCOPED_STORAGE_RELATIVE_PATH + "/" + MEDIA_SUBFOLDER_NAME,
					)
					mediaUri.toString()
				} else {
					val path = arrayOf(
						Environment.getExternalStoragePublicDirectory(BASE_LEGACY_STORAGE_FOLDER),
						MEDIA_SUBFOLDER_NAME,
						fileName,
					).joinToString("/")
					File(path).apply { createNewFile() }.absolutePath
				}
			}
		}
	}

	override fun cleanup() {
		runCatching { customFileFileDescriptor?.close() }
		runCatching { mediaFileFileDescriptor?.close() }
	}

	fun asCustomGetFileDescriptor(
		counter: Long,
		fileExtension: String,
	): FileDescriptor {
		runCatching { customFileFileDescriptor?.close() }

		val file =
			getCustomDefinedFolder().createFile("audio/$fileExtension", "$counter.$fileExtension")!!

		customFileFileDescriptor = context.contentResolver.openFileDescriptor(file.uri, "w")!!

		return customFileFileDescriptor!!.fileDescriptor
	}

	@RequiresApi(Build.VERSION_CODES.Q)
	fun asMediaGetScopedStorageFileDescriptor(
		name: String,
		mimeType: String
	): FileDescriptor {
		runCatching { mediaFileFileDescriptor?.close() }

		val mediaUri = getOrCreateMediaFile(
			name = name,
			mimeType = mimeType,
			relativePath = SCOPED_STORAGE_RELATIVE_PATH,
		)

		mediaFileFileDescriptor = context.contentResolver.openFileDescriptor(mediaUri, "w")!!

		return mediaFileFileDescriptor!!.fileDescriptor
	}

	companion object {
		fun viaInternalFolder(context: Context) = AudioBatchesFolder(context, BatchType.INTERNAL)

		fun viaCustomFolder(context: Context, folder: DocumentFile) =
			AudioBatchesFolder(context, BatchType.CUSTOM, folder)

		fun viaMediaFolder(context: Context) = AudioBatchesFolder(context, BatchType.MEDIA)

		fun importFromFolder(folder: String, context: Context) = when (folder) {
			RECORDER_INTERNAL_SELECTED_VALUE -> viaInternalFolder(context)
			RECORDER_MEDIA_SELECTED_VALUE -> viaMediaFolder(context)
			else -> viaCustomFolder(context, DocumentFile.fromTreeUri(context, Uri.parse(folder))!!)
		}

		val BASE_LEGACY_STORAGE_FOLDER = Environment.DIRECTORY_PODCASTS
		val MEDIA_RECORDINGS_SUBFOLDER = MEDIA_SUBFOLDER_NAME + "/.audio_recordings"
		val BASE_SCOPED_STORAGE_RELATIVE_PATH =
			(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
				Environment.DIRECTORY_RECORDINGS
			else
				Environment.DIRECTORY_PODCASTS)
		val SCOPED_STORAGE_RELATIVE_PATH =
			BASE_SCOPED_STORAGE_RELATIVE_PATH + "/" + MEDIA_RECORDINGS_SUBFOLDER

		val SCOPED_MEDIA_CONTENT_URI = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
		val LEGACY_MEDIA_FOLDER = File(
			Environment.getExternalStoragePublicDirectory(BASE_LEGACY_STORAGE_FOLDER),
			MEDIA_RECORDINGS_SUBFOLDER,
		)
	}
}
