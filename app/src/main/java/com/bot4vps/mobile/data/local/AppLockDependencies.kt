package com.bot4vps.mobile.data.local

import com.bot4vps.mobile.data.model.LockBiometricSeal
import javax.crypto.Cipher

internal interface AppLockStorage {
    fun read(): String?
    fun write(value: String?)
}

interface AppLockBiometricOperations {
    fun enrollment(): Pair<String, Cipher>
    fun seal(alias: String, cipher: Cipher): LockBiometricSeal
    fun decrypt(seal: LockBiometricSeal): Cipher
    fun verify(seal: LockBiometricSeal, cipher: Cipher): Boolean
    fun remove(alias: String?)
    fun sealSeed(alias: String, cipher: Cipher, seed: ByteArray): LockBiometricSeal =
        com.bot4vps.mobile.data.model.LockBiometricCipher.sealSeed(alias, cipher, seed)
    fun openSeed(seal: LockBiometricSeal, cipher: Cipher): ByteArray =
        com.bot4vps.mobile.data.model.LockBiometricCipher.openSeed(seal, cipher)
}
