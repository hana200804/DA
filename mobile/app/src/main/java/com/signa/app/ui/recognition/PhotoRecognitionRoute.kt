package com.signa.app.ui.recognition

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.util.concurrent.TimeUnit

@Composable
internal fun PhotoRecognitionRoute(
    onSignIn: () -> Unit,
    viewModel: PhotoRecognitionViewModel = hiltViewModel(),
    content: @Composable (takePhoto: () -> Unit, choosePhoto: () -> Unit) -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle(initialValue = null)
    val captureDir = File(context.cacheDir, "recognition-capture")
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    var pendingCode by rememberSaveable { mutableStateOf("") }

    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun cameraUri(file: File) = FileProvider.getUriForFile(
        context, "${context.packageName}.recognition.capture", file
    )

    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = capturePath?.let { File(captureDir, it) }
        capturePath = null
        if (success && file != null) viewModel.load(cameraUri(file), file)
        else file?.delete()
    }

    val launchCamera: () -> Unit = {
        var file: File? = null
        viewModel.show()
        try {
            captureDir.mkdirs()
            captureDir.listFiles()?.filter {
                System.currentTimeMillis() - it.lastModified() > TimeUnit.HOURS.toMillis(1)
            }?.forEach { it.delete() }
            file = File.createTempFile("photo-", ".jpg", captureDir)
            capturePath = file.name
            capture.launch(cameraUri(file))
        } catch (_: Exception) {
            file?.delete()
            capturePath = null
            viewModel.showMessage("Không mở được máy ảnh.")
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) launchCamera() else viewModel.showMessage("Hãy cấp quyền máy ảnh để chụp ảnh.")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.load(it, temporaryFile = null) }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val index = pendingIndex
        pendingIndex = null
        if (index != null && it.values.any { allowed -> allowed }) viewModel.submit(index, pendingCode)
        else viewModel.showMessage("Cần quyền vị trí để gửi đề xuất.")
    }

    val takePhoto = {
        viewModel.show()
        if (granted(Manifest.permission.CAMERA)) launchCamera()
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }
    val choosePhoto = {
        try {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: Exception) {
            viewModel.show()
            viewModel.showMessage("Không mở được thư viện ảnh.")
        }
    }
    if (!state.open) {
        content(takePhoto, choosePhoto)
        return
    }

    BackHandler { viewModel.close() }
    PhotoRecognitionScreen(
        state = state,
        signedIn = session != null,
        onBack = viewModel::close,
        onTakePhoto = takePhoto,
        onChoosePhoto = choosePhoto,
        onRefreshModels = viewModel::refreshModels,
        onModel = viewModel::chooseModel,
        onDetect = viewModel::detect,
        onSignIn = onSignIn,
        onSubmit = { index, code ->
            if (granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                viewModel.submit(index, code)
            } else {
                pendingIndex = index
                pendingCode = code
                locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
    )
}
