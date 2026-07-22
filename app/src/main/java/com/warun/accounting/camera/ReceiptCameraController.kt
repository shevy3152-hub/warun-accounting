package com.warun.accounting.camera

import android.content.Context
import android.net.Uri
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.util.concurrent.TimeUnit

class ReceiptCameraController(private val context: Context) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var previewView: PreviewView? = null

    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onReady: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                provider.unbindAll()
                val boundCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                cameraProvider = provider
                camera = boundCamera
                imageCapture = capture
                this.previewView = previewView
            }.onSuccess { onReady() }.onFailure(onError)
        }, ContextCompat.getMainExecutor(context))
    }

    fun capture(file: File, onSuccess: (String) -> Unit, onError: (Throwable) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onError(IllegalStateException("Camera is not ready"))
            return
        }
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        val executor = ContextCompat.getMainExecutor(context)
        val takePicture = {
            capture.takePicture(
                options,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        onSuccess((outputFileResults.savedUri ?: Uri.fromFile(file)).toString())
                    }

                    override fun onError(exception: ImageCaptureException) {
                        onError(exception)
                    }
                }
            )
        }
        val boundCamera = camera
        val boundPreviewView = previewView
        if (boundCamera == null || boundPreviewView == null) {
            takePicture()
            return
        }
        val centerPoint = boundPreviewView.meteringPointFactory.createPoint(0.5f, 0.5f)
        val focusAction = FocusMeteringAction.Builder(
            centerPoint,
            FocusMeteringAction.FLAG_AF or
                FocusMeteringAction.FLAG_AE or
                FocusMeteringAction.FLAG_AWB
        )
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        val focusFuture = boundCamera.cameraControl.startFocusAndMetering(focusAction)
        focusFuture.addListener(
            {
                runCatching { focusFuture.get() }
                takePicture()
            },
            executor
        )
    }

    fun unbind() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        camera = null
        imageCapture = null
        previewView = null
    }
}
