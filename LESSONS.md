# FreeNote 踩坑记录

记录「花了时间才搞明白」的问题：bug 根因、跨端协议/平台不兼容、迁移与工具怪癖。
改代码前先查这里；同类问题再现时更新原条目，不要新开。

## 2026-10-10 Room 迁移里 CREATE INDEX 必须同时在实体声明 indices
- 现象: 从 v3 升级到 v4 打开数据库抛 `IllegalStateException: Migration didn't properly handle: note_history`（Expected/Found 的索引集合不一致）。
- 根因: Room 在迁移后按实体生成的 TableInfo 校验实际 schema，索引集合必须完全一致；`MIGRATION_3_4` 手写建了 `index_note_history_noteId_savedAt`，而 `NoteHistory` 实体没写 `indices`（既有的 notes/outbox 都没有索引，容易照抄遗漏）。
- 修复: `android/app/src/main/java/com/zqr/freenote/data/NoteHistory.kt`（`@Entity(indices = [Index(value = ["noteId", "savedAt"])])`）。
- 验证: 静态核对迁移 SQL 与 Room 默认索引名（`index_<表>_<列1>_<列2>`）完全一致；建议真机/模拟器执行 `android/gradlew assembleDebug` 并从旧版本覆盖安装回归。
- 防再犯: 迁移里的每一条 `CREATE INDEX` 必须在 `@Entity(indices=...)` 有同名同列序声明；反之，实体声明的索引必须出现在某次迁移里（否则旧库缺索引同样报错）。

## 2026-10-10 内容寻址附件：删文件前必须做全库引用检查
- 现象: 便签 A 删掉一张图片后，便签 B 仍引用的同一张图片文件被删除（B 的图片丢失，只能等下一次同步补回）。
- 根因: 协议 v6 后附件名 = 内容哈希，同一文件会被多条便签共用；`NoteEditViewModel.cleanupOrphanImages` 只比较「本条便签」的引用就删文件——旧版 UUID 命名时代每张图唯一，所以是安全的。
- 修复: `cleanupOrphanImages` 里便携附件名改走 `NoteViewModel.deleteUnusedAttachments` → `AttachmentStore.deleteUnused`（全局引用检查，与 Windows `attachments.delete_unused` 一致）；legacy `file://` 路径仍按原逻辑处理。
- 验证: 静态走查调用链；建议手工回归：两条便签插入同一张图，删除其中一条的图片块并返回，另一条图片仍应显示。
- 防再犯: 任何删除 `note_images/` 文件的操作，必须先经过全库引用检查（`deleteUnused`），不允许按单条便签引用直接删。

## 2026-10-10 服务端 seq 回退后，附件上传记录必须无条件落盘
- 现象: 服务端换库/恢复备份后，客户端便签能全量重拉，但图片不再重传；其他设备一直缺图（提示「N 张图片待续传」但永远不动）。
- 根因: 回退分支只在内存里 `uploaded.clear()`，而写回 meta 的条件是「本轮有新上传」（`uploadedAdded` 非空）；本轮没有新上传时清空状态丢失，下次同步又加载旧记录，误判为已上传。
- 修复: `windows/freenote/sync_client.py` 与 `android/.../ServerSyncClient.kt` 增加 `uploadedReset` 标志，重置过就强制 `saveUploadedNames`。
- 验证: 静态走查分支；建议构造服务端 DB 重置后的同步，观察日志出现附件重传。
- 防再犯: 「内存中作废的持久状态」必须在同一函数内无条件写回，不要依赖「有新数据才保存」。

## 2026-10-10 Android 重复提醒：更新 DB 不等于排下一次闹钟
- 现象: 设置「每天」提醒后只收到一次通知；重启手机后重复提醒直接消失。
- 根因: `ReminderReceiver` 只把下一次时间写进 DB，没有调用 `ReminderScheduler.schedule`；应用不在前台时无人重排。另外 `ReminderService` 对已过期的提醒一律清空（重启错过闹钟就被清除）。
- 修复: `ReminderReceiver` 计算 next 后调用 `ReminderScheduler.schedule`；`ReminderService.sync/rescheduleAll` 对过期提醒改为「有重复规则推进到下一次并重排，否则清空」。
- 验证: 静态走查；建议真机回归：提醒设到 1 分钟后、选「每天」，应连续收到两次通知；重启后提醒仍存在。
- 防再犯: 任何持久化的「下一次时间」必须同时落到 AlarmManager；只写 DB 不算排期。

## 2026-10-10 服务器同步位点与附件上传记录必须按账号隔离
- 现象: Android 换账号登录后漏拉旧数据；`attachments.uploaded` 全局共享还会让图片不再上传到新账号。
- 根因: Windows 端 last_seq 的 key 是 `last_seq:<server>:<user>`，Android 用全局 `last_seq` 且登录/退出不清空；新加的 `attachments.uploaded` 同样全局。
- 修复: Android `ServerSyncClient.accountKey(prefix, serverUrl)` 生成 `last_seq:<server>:<user>` 与 `attachments.uploaded:<server>:<user>`；旧全局 key 被忽略（首次全量重拉/重传，安全幂等）。
- 验证: 静态走查（服务端同名附件重复上传会提前 return，不重复计配额）。
- 防再犯: 与账号绑定的同步状态一律带 server+username 前缀，与 Windows 语义保持一字不差。

## 2026-10-10 系统分享用可观察流消费，launchSingleTop 不会重建页面
- 现象: 分享文本到 FreeNote 时若「新建便签页」已在前台，内容不出现；且残留到下一次新建便签才冒出来。
- 根因: 分享内容是一次性内存变量，只在 `NoteEditViewModel.init` 消费；`navigate(EDIT_NEW){ launchSingleTop }` 对已在栈顶的同路由是空操作，没人消费。
- 修复: `SharedIntake` 改为 `MutableStateFlow<SharedText?>`；`NoteEditScreen`（仅新建页）用 `LaunchedEffect(sharedIntake)` 实时消费并追加（`applySharedText`）。
- 验证: 静态走查；建议真机回归：先打开新建便签页再分享文本，内容应追加进去。
- 防再犯: 跨页面一次性事件用 Flow/StateFlow + 幂等消费，不要只挂在创建路径上。

## 2026-10-10 QUrl.path() 已是 PrettyDecoded，二次 unquote 会解错标题
- 现象: wiki 链接 `[[100%2F]]` 点击后跳去标题 `100/`。
- 根因: `QUrl.toString()/path()` 默认 PrettyDecoded（`%20` 已解码、`%252F` 解成 `%2F`）；代码又对 `path()` 调了一次 `unquote`。
- 修复: `windows/freenote/ui/editor_page.py::_on_anchor_clicked` 改为用 `toString()` 的原始片段 `unquote` 一次。
- 验证: 本机 PySide6 6.11.1 实测（`My Title`/`50% off`/`100%2F`/`C# notes` 全部往返正确）。
- 防再犯: 百分号解码只做一次，且来源必须是「编码态」字符串（`toString()` 原始片段），不要对 `path()` 结果再解。

## 2026-10-10 小修合集
- 服务端 `/admin`：`secrets.compare_digest` 传 str 在非 ASCII token 下会抛 TypeError → 改为双方 `encode("utf-8")` 后比较（`server/freenote_server/app.py`）。
- Windows 全局热键：非 Windows 环境 `ctypes.windll` 不存在会让线程带异常退出 → `hotkey.py` 开头 `hasattr(ctypes, "windll")` 保护。
- P2P `image_get`：服务端因字节上限只回传部分条目（`more=true`）时，客户端原来把整批都当作已处理，导致本轮剩余图片不重试 → 改为只移除「已收到 + 对端明确 missing」的名（`p2p/client.py`、`Android P2PClient.kt`）。
- 备份导出：图片总量超上限（Windows 1.2GB / Android 64MB）时原来静默截断 → 现在在结果消息里明示「其余图片未包含」（`backup.py`、`BackupManager.kt`）。
