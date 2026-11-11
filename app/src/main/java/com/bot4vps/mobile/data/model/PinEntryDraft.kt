package com.bot4vps.mobile.data.model

enum class PinEntryStage { CURRENT, NEW, REPEAT }

/** Holds only the current on-screen entry; never persisted or saveable. */
data class PinEntryDraft(val stage: PinEntryStage, val digits: String = "", val firstPin: String = "") {
    fun digit(value: Int): PinEntryDraft = if (value in 0..9 && digits.length < LockPin.LENGTH) copy(digits = digits + value) else this
    fun delete(): PinEntryDraft = copy(digits = digits.dropLast(1))
    fun repeat(): PinEntryDraft {
        check(stage == PinEntryStage.NEW && digits.length == LockPin.LENGTH)
        return PinEntryDraft(PinEntryStage.REPEAT, firstPin = digits)
    }
    val complete get() = digits.length == LockPin.LENGTH
    val matches get() = stage == PinEntryStage.REPEAT && complete && digits == firstPin
}
