import androidx.camera.core.ExperimentalLensFacing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.myzel394.alibi.ui.utils.CameraInfo


@Composable
fun CameraSelectionButton(
	cameraID: CameraInfo.Lens,
	selected: Boolean,
	onSelected: () -> Unit,
	label: String,
	description: String? = null,
	icon: ImageVector? = null,
) {
	val backgroundColor by animateColorAsState(
		targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(
			alpha = 0.2f
		) else Color.Transparent,
		animationSpec = spring(
			stiffness = Spring.StiffnessLow,
			dampingRatio = Spring.DampingRatioNoBouncy,
		),
		label = "backgroundColor"
	)

	val displayIcon = icon ?: CAMERA_LENS_ICON_MAP[cameraID] ?: Icons.Default.QuestionMark

	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.SpaceBetween,
		modifier = Modifier
			.fillMaxWidth()
			.clip(MaterialTheme.shapes.medium)
			.clickable(onClick = onSelected)
			.background(backgroundColor)
			.padding(vertical = 8.dp, horizontal = 12.dp)
	) {
		Row(
			verticalAlignment = Alignment.CenterVertically,
		) {
			RadioButton(
				selected = selected,
				onClick = onSelected,
			)
			if (description == null) {
				Text(
					label,
					style = MaterialTheme.typography.labelLarge,
				)
			} else {
				Column(
					verticalArrangement = Arrangement.spacedBy(4.dp),
				) {
					Text(
						label,
						style = MaterialTheme.typography.labelLarge,
					)
					Text(
						description,
						style = MaterialTheme.typography.bodySmall,
					)
				}
			}
		}
		Icon(
			displayIcon,
			contentDescription = null,
			modifier = Modifier
				.size(24.dp),
		)
	}
}

val CAMERA_LENS_ICON_MAP = mapOf(
	CameraInfo.Lens.BACK to Icons.Default.Camera,
	CameraInfo.Lens.FRONT to Icons.Default.Person,
	CameraInfo.Lens.EXTERNAL to Icons.Default.Videocam,
	CameraInfo.Lens.UNKNOWN to Icons.Default.QuestionMark,
)

val CAMERA_TYPE_ICON_MAP = mapOf(
	CameraInfo.CameraType.MAIN to Icons.Default.CameraAlt,
	CameraInfo.CameraType.WIDE_ANGLE to Icons.Default.Panorama,
	CameraInfo.CameraType.TELEPHOTO to Icons.Default.Camera,
	CameraInfo.CameraType.MACRO to Icons.Default.Camera,
	CameraInfo.CameraType.UNKNOWN to Icons.Default.QuestionMark,
)
