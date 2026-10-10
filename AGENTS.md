# FreeNote 项目约定（给 AI/协作者的规则）

FreeNote（自由笔记）三端笔记应用：
- `android/`：Kotlin + Jetpack Compose + Room（协议 v6，服务端同步 / P2P 同步）
- `windows/`：Python + PySide6 + SQLite（与 Android 协议 v6 互通，含 P2P）
- `server/`：FastAPI + SQLite 同步服务端（`/api/sync`，附件内容寻址）

## 改代码时的硬性规则

1. 任何一端的同步协议字段/哈希/摘要改动，必须三端同步改并保持一致（android `SyncProtocol.kt` / windows `models.py`+`sync_client.py` / server `storage.py`）。
2. Room 迁移里 `CREATE INDEX` 的每一条都必须在 `@Entity(indices=...)` 有同名同列序声明；实体声明的索引也必须出现在迁移里（否则迁移后 schema 校验崩溃）。
3. 删除 `note_images/` 附件文件必须先做全库引用检查（`AttachmentStore.deleteUnused` / `attachments.delete_unused`）；内容寻址后同一文件会被多条便签共用。
4. 「下一次时间」类持久状态（提醒/同步位点/附件上传记录）必须：写回落盘无条件（不能只靠“有新数据才保存”）、按 server+username 隔离、Android 提醒同时重排 AlarmManager。
5. 跨页面一次性事件（系统分享等）用 Flow 消费，不要只挂在 ViewModel 创建路径上。
6. 默认不主动跑测试（见全局 no-auto-tests 规则）；改动后至少做静态核对 + 可行的语法检查，并在汇报里给出建议的验证命令。

详细踩坑背景与验证方式见 `LESSONS.md`。
