package com.zqr.freenote.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 系统分享（ACTION_SEND text/plain）进入的待处理内容。
 *
 * MainActivity 收到分享后写入；新建便签页既可能在分享后新开（首次组合时消费），
 * 也可能已经打开（用 StateFlow 实时送达并追加），消费一次后清空，
 * 避免「分享被静默丢弃」或残留到下一次新建便签。
 */
object SharedIntake {
    data class SharedText(val text: String, val subject: String?)

    private val _pending = MutableStateFlow<SharedText?>(null)
    val pending: StateFlow<SharedText?> = _pending.asStateFlow()

    /** 写入分享内容；正文为空时不产生待处理项。 */
    fun put(text: CharSequence?, subject: String?) {
        val body = text?.toString()?.takeIf { it.isNotBlank() } ?: return
        _pending.value = SharedText(body, subject?.toString()?.takeIf { it.isNotBlank() })
    }

    /** 取走并清空（返回 null 表示没有待处理内容）。仅在主线程调用。 */
    fun consume(): SharedText? {
        val current = _pending.value ?: return null
        _pending.value = null
        return current
    }
}
