package com.zqr.freenote.data

import android.content.Context
import android.net.Uri

class BackupService(private val context: Context) {

    /**
     * 导出所有便签（锁定便签保持密文并携带 isLocked 标记）；
     * 图片附件随 JSON 一起导出/导入（协议 v6，见 BackupManager）：
     */
    fun export(notes: List<Note>, uri: Uri): BackupManager.BackupResult =
        BackupManager.export(context, notes, uri)

    suspend fun import(uri: Uri): BackupManager.BackupResult =
        BackupManager.import(context, uri)

    /** 导入 .md/.txt 文件（每个文件一条便签）。 */
    suspend fun importTextFiles(uris: List<Uri>): BackupManager.BackupResult =
        BackupManager.importTextFiles(context, uris)

    /** 自动备份到 SAF 目录（Tree URI）：写 JSON + 可选 WebDAV 上传。 */
    suspend fun autoBackup(treeUri: Uri): BackupManager.BackupResult =
        BackupManager.autoBackup(context, treeUri)
}
