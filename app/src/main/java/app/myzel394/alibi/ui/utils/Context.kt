package app.myzel394.alibi.ui.utils

import android.content.Context
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance

suspend fun Context.getCameraProvider(): ProcessCameraProvider =
	ProcessCameraProvider.awaitInstance(this)
