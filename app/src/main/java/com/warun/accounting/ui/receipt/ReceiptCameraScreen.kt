package com.warun.accounting.ui.receipt

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.warun.accounting.camera.ReceiptCameraController
import com.warun.accounting.camera.ReceiptCaptureResult
import com.warun.accounting.camera.ReceiptImageStore
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ReceiptCameraScreen(
    onCaptured: (ReceiptCaptureResult) -> Unit,
    onCancel: () -> Unit,
    cameraViewModel: ReceiptCameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val requestHistory = remember(context) { SharedPreferencesCameraPermissionRequestHistory(context) }
    val imageStore = remember(context) {
        ReceiptImageStore(File(context.filesDir, "receipt-images/pending"))
    }
    val controller = remember(context) { ReceiptCameraController(context.applicationContext) }
    val uiState by cameraViewModel.uiState.collectAsState()
    var permissionState by remember {
        mutableStateOf(resolvePermissionState(context, activity, requestHistory))
    }
    var acceptedCaptureId by remember { mutableStateOf<String?>(null) }
    var activeCaptureId by remember { mutableStateOf<String?>(null) }
    var stabilizationJob by remember { mutableStateOf<Job?>(null) }
    val cancelledCaptureIds = remember { mutableSetOf<String>() }
    val captureScope = rememberCoroutineScope()
    val latestUiState by rememberUpdatedState(uiState)
    val latestAcceptedCaptureId by rememberUpdatedState(acceptedCaptureId)
    val latestActiveCaptureId by rememberUpdatedState(activeCaptureId)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestHistory.markCameraPermissionRequested()
        permissionState = resolvePermissionState(context, activity, requestHistory)
    }

    fun cancel() {
        stabilizationJob?.cancel()
        stabilizationJob = null
        activeCaptureId?.let {
            cancelledCaptureIds += it
            imageStore.delete(it)
        }
        (uiState as? ReceiptCameraUiState.Captured)?.result?.captureId?.let(imageStore::delete)
        onCancel()
    }

    BackHandler(onBack = ::cancel)

    LaunchedEffect(Unit) {
        imageStore.cleanupExpired()
    }

    DisposableEffect(controller) {
        onDispose {
            controller.unbind()
            latestActiveCaptureId?.let {
                cancelledCaptureIds += it
                imageStore.delete(it)
            }
            val captured = latestUiState as? ReceiptCameraUiState.Captured
            if (captured != null && captured.result.captureId != latestAcceptedCaptureId) {
                imageStore.delete(captured.result.captureId)
            }
        }
    }

    DisposableEffect(lifecycleOwner, permissionState) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionState = resolvePermissionState(context, activity, requestHistory)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            when (permissionState) {
            CameraPermissionState.Granted -> {
                CameraContent(
                    uiState = uiState,
                    onPreviewCreated = { previewView ->
                        cameraViewModel.startInitializing()
                        controller.bind(
                            lifecycleOwner = lifecycleOwner,
                            previewView = previewView,
                            onReady = cameraViewModel::onPreviewReady,
                            onError = { cameraViewModel.onError("カメラを初期化できませんでした") }
                        )
                    },
                    onCapture = {
                        if (cameraViewModel.startCapture()) {
                            val captureId = imageStore.newCaptureId()
                            activeCaptureId = captureId
                            val file = runCatching { imageStore.prepareFile(captureId) }
                                .getOrElse {
                                    activeCaptureId = null
                                    cameraViewModel.onError("撮影画像の保存先を準備できませんでした")
                                    return@CameraContent
                                }
                            stabilizationJob?.cancel()
                            stabilizationJob = captureScope.launch {
                                delay(CaptureStabilizationDelayMillis)
                                if (activeCaptureId != captureId || captureId in cancelledCaptureIds) {
                                    imageStore.delete(captureId)
                                    return@launch
                                }
                                controller.capture(
                                    file = file,
                                    onSuccess = { localUri ->
                                        activeCaptureId = null
                                        if (captureId in cancelledCaptureIds) {
                                            imageStore.delete(captureId)
                                        } else {
                                            cameraViewModel.onCaptured(
                                                ReceiptCaptureResult(captureId, localUri, System.currentTimeMillis())
                                            )
                                        }
                                    },
                                    onError = {
                                        activeCaptureId = null
                                        imageStore.delete(captureId)
                                        if (captureId !in cancelledCaptureIds) {
                                            cameraViewModel.onError("レシートを撮影できませんでした")
                                        }
                                    }
                                )
                            }
                        }
                    },
                    onRetake = { result ->
                        imageStore.delete(result.captureId)
                        cameraViewModel.resumePreview()
                    },
                    onUsePhoto = { result ->
                        acceptedCaptureId = result.captureId
                        onCaptured(result)
                    },
                    onCancel = ::cancel
                )
            }

            CameraPermissionState.FirstRequest -> PermissionMessage(
                title = "レシート撮影にはカメラ権限が必要です",
                body = "権限を許可しても、撮影した画像はアプリ専用領域にだけ保存されます。",
                actionLabel = "カメラを許可",
                onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onCancel = onCancel
            )

            CameraPermissionState.DeniedCanRetry -> PermissionMessage(
                title = "カメラ権限が許可されていません",
                body = "もう一度権限を確認するか、手入力へ戻ることができます。",
                actionLabel = "もう一度確認",
                onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onCancel = onCancel
            )

            CameraPermissionState.SettingsRequired -> PermissionMessage(
                title = "設定画面でカメラを許可してください",
                body = "権限の確認画面を再表示できません。アプリ設定からカメラを許可できます。",
                actionLabel = "アプリ設定を開く",
                onAction = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", context.packageName, null)
                        }
                    )
                },
                onCancel = onCancel
            )

            CameraPermissionState.NoCamera -> PermissionMessage(
                title = "この端末ではカメラを利用できません",
                body = "レシート情報は手入力できます。",
                actionLabel = null,
                onAction = {},
                onCancel = onCancel
            )
            }
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
            ) {
                IconButton(onClick = ::cancel) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "戻る"
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraContent(
    uiState: ReceiptCameraUiState,
    onPreviewCreated: (PreviewView) -> Unit,
    onCapture: () -> Unit,
    onRetake: (ReceiptCaptureResult) -> Unit,
    onUsePhoto: (ReceiptCaptureResult) -> Unit,
    onCancel: () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                PreviewView(context).also {
                    it.scaleType = PreviewView.ScaleType.FILL_CENTER
                    onPreviewCreated(it)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (uiState) {
                ReceiptCameraUiState.Idle,
                ReceiptCameraUiState.Initializing -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                ReceiptCameraUiState.Previewing -> Button(
                    onClick = onCapture,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("撮影") }
                ReceiptCameraUiState.Capturing -> Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("端末を動かさずにお待ちください…") }
                is ReceiptCameraUiState.Captured -> {
                    Text("レシートを撮影しました", color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onRetake(uiState.result) }, modifier = Modifier.weight(1f)) {
                            Text("再撮影")
                        }
                        Button(onClick = { onUsePhoto(uiState.result) }, modifier = Modifier.weight(1f)) {
                            Text("この画像を使用")
                        }
                    }
                }
                is ReceiptCameraUiState.Error -> {
                    Text(uiState.message, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("手入力へ戻る") }
                }
            }
            if (uiState !is ReceiptCameraUiState.Error) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("キャンセル") }
            }
        }
    }
}

@Composable
private fun PermissionMessage(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, modifier = Modifier.padding(vertical = 16.dp))
        actionLabel?.let {
            Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(it) }
        }
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("手入力へ戻る") }
    }
}

private fun resolvePermissionState(
    context: Context,
    activity: Activity?,
    requestHistory: CameraPermissionRequestHistory
): CameraPermissionState {
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
        return CameraPermissionState.NoCamera
    }
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
        return CameraPermissionState.Granted
    }
    if (!requestHistory.hasRequestedCameraPermission()) {
        return CameraPermissionState.FirstRequest
    }
    return if (activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)) {
        CameraPermissionState.DeniedCanRetry
    } else {
        CameraPermissionState.SettingsRequired
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private const val CaptureStabilizationDelayMillis = 750L
