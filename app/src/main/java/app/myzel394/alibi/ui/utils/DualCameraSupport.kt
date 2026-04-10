package app.myzel394.alibi.ui.utils

import android.content.Context
import android.os.Build
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance

/**
 * Helpers for simultaneous front + back camera recording.
 *
 * Concurrent camera support requires Android 11 (API 30) and
 * device hardware support. CameraX exposes this via
 * [ProcessCameraProvider.availableConcurrentCameraInfos].
 */
object DualCameraSupport {

	/** True if the device can run front and back cameras simultaneously. */
	suspend fun isSupported(context: Context): Boolean {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
		val provider = ProcessCameraProvider.awaitInstance(context)
		return findSupportedPair(provider) != null
	}

	/**
	 * Returns a [SupportedPair] of front and back [CameraInfo]s that can
	 * run concurrently, or null if the device doesn't support it.
	 */
	fun findSupportedPair(provider: ProcessCameraProvider): SupportedPair? {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
		for (combo in provider.availableConcurrentCameraInfos) {
			val back = combo.firstOrNull {
				it.lensFacing == CameraSelector.LENS_FACING_BACK
			}
			val front = combo.firstOrNull {
				it.lensFacing == CameraSelector.LENS_FACING_FRONT
			}
			if (back != null && front != null) {
				return SupportedPair(back = back, front = front)
			}
		}
		return null
	}

	data class SupportedPair(
		val back: CameraInfo,
		val front: CameraInfo,
	) {
		val backSelector: CameraSelector get() = back.cameraSelector
		val frontSelector: CameraSelector get() = front.cameraSelector
	}
}
