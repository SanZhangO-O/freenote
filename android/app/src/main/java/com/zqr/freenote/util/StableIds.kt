package com.zqr.freenote.util

/**
 * 稳定、可跨进程复现的 Int 标识（用于 PendingIntent requestCode / 通知 ID）。
 * 相比 String.hashCode，FNV-1a 的分布更均匀，碰撞概率更低，且结果与进程无关，
 * 保证取消闹钟、更新通知时能命中同一 ID。
 */
object StableIds {
    private const val FNV_OFFSET_BASIS = 0x811c9dc5L
    private const val FNV_PRIME = 0x01000193L

    /** 由任意字符串派生非负 Int ID。 */
    fun forKey(key: String): Int {
        var hash = FNV_OFFSET_BASIS
        for (byte in key.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (byte.toLong() and 0xFF)) * FNV_PRIME
        }
        return (hash and 0x7fffffffL).toInt()
    }

    /** 便签相关的通知/闹钟 ID。 */
    fun forNote(noteId: String): Int = forKey("note:$noteId")
}
