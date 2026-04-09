package app.myzel394.alibi.ui.utils

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalLensFacing

@OptIn(ExperimentalLensFacing::class, ExperimentalCamera2Interop::class)
data class CameraInfo(
	val cameraId: String,
	val lens: Lens,
	val focalLengths: FloatArray?,
	val cameraType: CameraType,
	val logicalCameraId: String? = null,
) {
	/** Backward-compatible integer ID for legacy code paths. */
	val id: Int get() = cameraId.toIntOrNull() ?: -1

	/** True when this represents a physical camera behind a logical multi-camera. */
	val isPhysicalCamera: Boolean get() = logicalCameraId != null

	enum class Lens(val androidValue: Int) {
		BACK(CameraSelector.LENS_FACING_BACK),
		FRONT(CameraSelector.LENS_FACING_FRONT),
		EXTERNAL(CameraSelector.LENS_FACING_EXTERNAL),
		UNKNOWN(999),
	}

	enum class CameraType {
		MAIN,
		WIDE_ANGLE,
		TELEPHOTO,
		MACRO,
		UNKNOWN,
	}

	/** Shortest available focal length, used for classification. */
	val primaryFocalLength: Float?
		get() = focalLengths?.minOrNull()

	/**
	 * Builds a [CameraSelector] that targets this camera.
	 *
	 * For physical cameras behind a logical multi-camera, we select the
	 * logical parent. The caller must then set the appropriate zoom ratio
	 * on the camera to activate the desired physical sensor.
	 */
	fun buildCameraSelector(): CameraSelector {
		val targetId = logicalCameraId ?: cameraId
		return CameraSelector.Builder()
			.addCameraFilter { cameras ->
				cameras.filter { cameraInfo ->
					Camera2CameraInfo.from(cameraInfo).cameraId == targetId
				}
			}
			.build()
	}

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is CameraInfo) return false
		return cameraId == other.cameraId
	}

	override fun hashCode(): Int = cameraId.hashCode()

	companion object {
		private val LENS_FACING_TO_LENS = mapOf(
			CameraCharacteristics.LENS_FACING_BACK to Lens.BACK,
			CameraCharacteristics.LENS_FACING_FRONT to Lens.FRONT,
			CameraCharacteristics.LENS_FACING_EXTERNAL to Lens.EXTERNAL,
		)

		fun queryAvailableCameras(context: Context): List<CameraInfo> {
			val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
			val rawCameras = mutableListOf<CameraInfo>()

			for (cameraId in cameraManager.cameraIdList) {
				val characteristics = cameraManager.getCameraCharacteristics(cameraId)
				val lensFacingInt = characteristics.get(CameraCharacteristics.LENS_FACING)
					?: continue
				val lens = LENS_FACING_TO_LENS[lensFacingInt] ?: Lens.UNKNOWN

				// On API 28+, check for physical cameras behind logical cameras
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
					val physicalIds = characteristics.physicalCameraIds
					if (physicalIds.size > 1) {
						for (physicalId in physicalIds) {
							val physChars = runCatching {
								cameraManager.getCameraCharacteristics(physicalId)
							}.getOrNull() ?: continue
							val physFocalLengths = physChars.get(
								CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
							)
							rawCameras.add(
								CameraInfo(
									cameraId = physicalId,
									lens = lens,
									focalLengths = physFocalLengths,
									cameraType = CameraType.UNKNOWN,
									logicalCameraId = cameraId,
								)
							)
						}
						continue
					}
				}

				// Single camera or API < 28: use the logical camera directly
				val focalLengths = characteristics.get(
					CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS
				)
				rawCameras.add(
					CameraInfo(
						cameraId = cameraId,
						lens = lens,
						focalLengths = focalLengths,
						cameraType = CameraType.UNKNOWN,
						logicalCameraId = null,
					)
				)
			}

			return classifyCameras(rawCameras)
		}

		/**
		 * Returns true when the device has exactly one back camera and one front
		 * camera — the typical two-camera phone setup where we show the simple
		 * "Back / Front" UI.
		 */
		fun checkHasNormalCameras(cameras: Iterable<CameraInfo>): Boolean {
			val list = cameras.toList()
			val backCount = list.count { it.lens == Lens.BACK }
			val frontCount = list.count { it.lens == Lens.FRONT }
			return list.size == 2 && backCount == 1 && frontCount == 1
		}

		/**
		 * Classifies cameras within each lens-facing group by focal length.
		 *
		 * Heuristic:
		 * - Single camera in group -> MAIN
		 * - Two cameras: shorter focal -> WIDE_ANGLE, longer -> MAIN
		 * - Three+ cameras: shortest -> WIDE_ANGLE, longest -> TELEPHOTO, rest -> MAIN
		 */
		fun classifyCameras(cameras: List<CameraInfo>): List<CameraInfo> =
			cameras.groupBy { it.lens }.flatMap { (_, group) ->
				if (group.size <= 1) {
					group.map { it.copy(cameraType = CameraType.MAIN) }
				} else {
					classifyGroup(group)
				}
			}

		private fun classifyGroup(cameras: List<CameraInfo>): List<CameraInfo> {
			val sorted = cameras.sortedBy { it.primaryFocalLength ?: Float.MAX_VALUE }

			return sorted.mapIndexed { index, camera ->
				val type = when {
					camera.primaryFocalLength == null -> CameraType.UNKNOWN
					sorted.size == 2 -> {
						if (index == 0) CameraType.WIDE_ANGLE else CameraType.MAIN
					}
					index == 0 -> CameraType.WIDE_ANGLE
					index == sorted.lastIndex -> CameraType.TELEPHOTO
					else -> CameraType.MAIN
				}
				camera.copy(cameraType = type)
			}
		}

		/**
		 * Computes the zoom ratio needed to activate the given physical camera
		 * within a logical multi-camera, relative to the main (default) camera.
		 *
		 * Returns null for non-physical cameras or if focal lengths are unavailable.
		 */
		fun computeZoomRatio(
			target: CameraInfo,
			allCameras: List<CameraInfo>,
		): Float? {
			if (!target.isPhysicalCamera) return null
			val targetFocal = target.primaryFocalLength ?: return null

			// Find the main camera in the same logical group to use as reference
			val siblings = allCameras.filter {
				it.logicalCameraId == target.logicalCameraId && it.lens == target.lens
			}
			val mainCamera = siblings.firstOrNull { it.cameraType == CameraType.MAIN }
				?: return null
			val mainFocal = mainCamera.primaryFocalLength ?: return null

			if (mainFocal == 0f) return null
			return targetFocal / mainFocal
		}
	}
}
