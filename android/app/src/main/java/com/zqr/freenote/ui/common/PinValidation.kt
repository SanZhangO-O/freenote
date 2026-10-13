package com.zqr.freenote.ui.common

/**
 * Shared helpers for validating free-text PIN entry across screens
 * (note lock dialog, settings, unlock screen).
 *
 * Keeping the rules in one place avoids the duplicated
 * `it.length <= 6 && it.all(Char::isDigit)` guard that was previously
 * inlined in several composables.
 *
 * 长度上限与 Windows 版 crypto.PIN_MAX_LENGTH 保持一致（4~12 位数字）。
 */
object PinValidation {
    const val MAX_LENGTH = 12
    const val MIN_LENGTH = 4

    /** True when [input] only contains digits and is within the allowed length. */
    fun isAcceptable(input: String): Boolean =
        input.length <= MAX_LENGTH && input.all { it.isDigit() }

    /** True when [input] is a complete, settable PIN (length within [MIN_LENGTH]..[MAX_LENGTH]). */
    fun isComplete(input: String): Boolean =
        input.length in MIN_LENGTH..MAX_LENGTH && input.all { it.isDigit() }
}
