package com.appblish.calculatorvault.intruder

import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Silent front-camera capture for the Intruder Selfie. Bound only when a wrong-PIN threshold
 * is crossed on the lock screen — never speculatively — so the camera permission is honoured
 * at point of need. Uses a preview-less CameraX [ImageCapture] pipeline bound to the lock
 * activity's lifecycle: no viewfinder is shown, one frame is grabbed as a JPEG, then the use
 * case is unbound. If there is no front lens, permission is missing, or capture fails, it
 * returns null and the caller still logs the attempt (photo-less).
 */
class IntruderCamera(
    context: Context,
) {
    private val appContext = context.applicationContext

    /** True if a front-facing camera exists and the CAMERA permission is granted. */
    fun isAvailable(): Boolean {
        val hasFront = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT)
        val granted =
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        return hasFront && granted
    }

    /**
     * Capture one front-camera frame as JPEG bytes, or null on any failure. Must be called
     * with a live [owner] (the lock activity) to bind the use case; camera work is marshalled
     * to the main thread as CameraX requires.
     */
    suspend fun captureSelfie(owner: LifecycleOwner): ByteArray? {
        if (!isAvailable()) return null
        return withContext(Dispatchers.Main) {
            val provider = awaitProvider() ?: return@withContext null
            val imageCapture =
                ImageCapture
                    .Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, imageCapture)
                takePicture(imageCapture)
            } catch (t: Throwable) {
                null
            } finally {
                runCatching { provider.unbindAll() }
            }
        }
    }

    private suspend fun awaitProvider(): ProcessCameraProvider? =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener(
                { cont.resume(runCatching { future.get() }.getOrNull()) },
                ContextCompat.getMainExecutor(appContext),
            )
        }

    private suspend fun takePicture(imageCapture: ImageCapture): ByteArray? =
        suspendCancellableCoroutine { cont ->
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(appContext),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bytes =
                            runCatching {
                                val buffer = image.planes[0].buffer
                                ByteArray(buffer.remaining()).also { buffer.get(it) }
                            }.getOrNull()
                        image.close()
                        if (cont.isActive) cont.resume(bytes)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resume(null)
                    }
                },
            )
        }
}
