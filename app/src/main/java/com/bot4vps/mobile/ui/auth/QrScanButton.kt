package com.bot4vps.mobile.ui.auth

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.bot4vps.mobile.data.api.readMobileInvitation
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@Composable
fun QrScanButton(enabled: Boolean = true, onCode: (String) -> Unit, onError: (String) -> Unit) {
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val value = result.contents
        if (value != null) {
            try {
                if (readMobileInvitation(value) != null) onCode(value)
                else onError("Это не QR-код привязки Bot4VPS. Создайте привязку в Web-панели.")
            } catch (change: com.bot4vps.mobile.data.api.MobileAddressChangeRequired) {
                onError(change.message.orEmpty())
            } catch (e: Exception) { onError(com.bot4vps.mobile.data.api.errorText(e)) }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setCaptureActivity(QrCaptureActivity::class.java).setBeepEnabled(false).setBarcodeImageEnabled(false)
            .setPrompt("Наведите камеру на QR-код в Web-панели Bot4VPS"))
        else onError("Доступ к камере не разрешён. Нажмите «Ввести код» и скопируйте код из Web-панели.")
    }
    TextButton(enabled = enabled, onClick = { permission.launch(Manifest.permission.CAMERA) }) {
        Text("Сканировать QR")
    }
}
