package com.bot4vps.mobile

import com.bot4vps.mobile.data.api.readMobileInvitation
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream

class PairingQrInteropTest {
    @Test fun serverGeneratedQrDecodesWithTheOnDeviceQrDecoder() {
        // Grayscale bytes decoded from the server PNG (synthetic invitation).
        // Two big-endian dimensions followed by one luminance byte per pixel.
        val source = DataInputStream(javaClass.getResourceAsStream("/mobile-invitation.gray")).use { input ->
            val width = input.readInt(); val height = input.readInt()
            val pixels = IntArray(width * height) {
                val value = input.readUnsignedByte()
                (0xff shl 24) or (value shl 16) or (value shl 8) or value
            }
            RGBLuminanceSource(width, height, pixels)
        }
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
        val invitation = readMobileInvitation(decoded.text)!!
        assertEquals("https://api.example.com:8444", invitation.url.trimEnd('/'))
        assertEquals("i".repeat(43), invitation.code)
        assertEquals("sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", invitation.serverPin)
    }
}
