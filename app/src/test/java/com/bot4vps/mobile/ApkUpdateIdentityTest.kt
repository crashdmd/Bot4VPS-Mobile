package com.bot4vps.mobile

import com.bot4vps.mobile.data.model.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class ApkUpdateIdentityTest {
    private val installed = ApkUpdateIdentity("com.bot4vps.mobile", "1.2.0", 20, false, setOf("a".repeat(64)))
    private val next = installed.copy(version = "1.2.1", code = 21)
    private val bytes = "download with valid size and SHA256".toByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val release = MobileRelease("1.2.1", 21, bytes.size.toLong(), digest, "https://raw.githubusercontent.com/update.apk", "")

    @Test fun foreignSignerIsRejectedEvenWithMatchingDownloadSizeAndHash() {
        assertEquals(release.size, bytes.size.toLong())
        assertEquals(release.sha256, digest)
        assertThrows(IllegalStateException::class.java) {
            verifyApkUpdateIdentity(next.copy(signerSha256 = setOf("b".repeat(64))), installed, release)
        }
    }
    @Test fun validUpdateWithExistingSignerIsAccepted() { verifyApkUpdateIdentity(next, installed, release) }
    @Test fun emptyOrPartialSignerSetsCannotUpdate() {
        for (signers in listOf(emptySet(), setOf("a".repeat(64), "b".repeat(64)))) {
            assertThrows(IllegalStateException::class.java) { verifyApkUpdateIdentity(next.copy(signerSha256 = signers), installed, release) }
        }
        assertThrows(IllegalStateException::class.java) { verifyApkUpdateIdentity(next, installed.copy(signerSha256 = emptySet()), release) }
    }
    @Test fun wrongPackageVersionCodeOrDebuggableArchiveIsRejected() {
        for (identity in listOf(next.copy(packageName = "other"), next.copy(version = "1.2.2"), next.copy(code = 20), next.copy(debuggable = true))) {
            assertThrows(IllegalStateException::class.java) { verifyApkUpdateIdentity(identity, installed, release) }
        }
    }
    @Test fun oldAppRejectsTheNewReleaseSigner() {
        assertThrows(IllegalStateException::class.java) {
            verifyApkUpdateIdentity(next, installed.copy(signerSha256 = setOf("old-certificate")), release)
        }
    }
}
