package com.bot4vps.mobile.ui.auth

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun rememberQrScanner(prompt: String, onCode: (String) -> Unit, onError: (String) -> Unit): () -> Unit {
    val accept by rememberUpdatedState(onCode)
    val fail by rememberUpdatedState(onError)
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let(accept) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setCaptureActivity(QrCaptureActivity::class.java).setBeepEnabled(false).setBarcodeImageEnabled(false).setPrompt(prompt))
        else fail("Доступ к камере не разрешён. Можно ввести код вручную.")
    }
    return { permission.launch(Manifest.permission.CAMERA) }
}
