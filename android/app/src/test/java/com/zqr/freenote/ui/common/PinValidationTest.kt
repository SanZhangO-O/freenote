package com.zqr.freenote.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinValidationTest {

    @Test
    fun validatesPinInputAndCompletion() {
        // 输入过程：仅数字且不超过最大长度
        assertTrue(PinValidation.isAcceptable("1234"))
        assertTrue(PinValidation.isAcceptable("123456"))
        assertTrue(PinValidation.isAcceptable("0"))
        assertTrue(PinValidation.isAcceptable(""))
        assertFalse(PinValidation.isAcceptable("12a4"))
        assertFalse(PinValidation.isAcceptable("1234567"))
        assertFalse(PinValidation.isAcceptable("12 34"))

        // 可提交：4~6 位纯数字
        assertTrue(PinValidation.isComplete("1234"))
        assertTrue(PinValidation.isComplete("123456"))
        assertFalse(PinValidation.isComplete("123"))
        assertFalse(PinValidation.isComplete("1234567"))
        assertFalse(PinValidation.isComplete("12ab"))
        assertFalse(PinValidation.isComplete(""))
    }
}
