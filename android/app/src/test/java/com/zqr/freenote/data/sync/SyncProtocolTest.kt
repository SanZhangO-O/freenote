package com.zqr.freenote.data.sync

import com.zqr.freenote.data.Note
import com.zqr.freenote.data.SyncEntry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2P 协议核心的纯 JVM 测试。
 *
 * 关键向量由 Python（FreeNote p2p/protocol.py 使用的同一算法）生成，
 * 保证 Android 端签名 / 组密钥 / tag 与 Windows 端字节级一致。
 */
class SyncProtocolTest {

    private val expectedKey =
        "0cd0385289a6121dc61eb61f43a0b9306fb8ac202d26af0a1e45753c7cf7010d"
    private val expectedTag = "3c9edf98aa4e7db5"
    private val expectedSignature =
        "41a8eebd22765359a41ced7647e07d269f3ae84e6c280bdb8289605ec3edea43"
    private val expectedAccountKey =
        "7d99c5975817e415a9ab1aef54f9221e6bb137f9feac87f0100692359c2bbc2d"
    private val expectedAccountFingerprint = "c5b4dae96b4bcfb2"

    @Test
    fun keysAccountsAndTagsMatchPythonVectors() {
        // 协议版本与迭代次数与 Windows 端 config.py 一致（v6：附件同步 + 重复提醒）
        assertEquals(6, SyncProtocol.PROTOCOL_VERSION)
        assertEquals(600_000, SyncProtocol.GROUP_KEY_ITERATIONS)

        // 组密钥：无账户与带账户两种派生
        assertEquals(expectedKey, SyncProtocol.deriveGroupKey("TEST-PASS-1234").toHex())
        assertEquals(
            expectedAccountKey,
            SyncProtocol.deriveGroupKey("TEST-PASS-1234", "local:alice").toHex()
        )

        // 账户命名空间归一化：服务器账号优先，地址去尾斜杠/小写/去空白
        assertEquals("", SyncProtocol.accountNamespace("", "", ""))
        assertEquals("local:家庭", SyncProtocol.accountNamespace("", "", " 家庭 "))
        assertEquals(
            "server:http://host:8000#alice",
            SyncProtocol.accountNamespace("HTTP://Host:8000/", " alice ", "家庭")
        )
        assertEquals("local:家庭", SyncProtocol.accountNamespace("http://h:1", "", "家庭"))
        assertEquals("local:家庭", SyncProtocol.accountNamespace("", "alice", "家庭"))

        // 账户指纹向量与长度
        assertEquals("", SyncProtocol.accountFingerprint(""))
        assertEquals(
            expectedAccountFingerprint,
            SyncProtocol.accountFingerprint("server:http://192.168.1.8:8000#alice")
        )
        assertEquals(16, SyncProtocol.accountFingerprint("local:alice").length)
        assertNotEquals(
            SyncProtocol.accountFingerprint("local:alice"),
            SyncProtocol.accountFingerprint("local:bob")
        )

        // 账户不同 → 密钥/tag 不同；设置账户后与仅口令分组的密钥也不同
        val alice = SyncProtocol.deriveGroupKey("TEST-PASS-1234", "local:alice")
        val bob = SyncProtocol.deriveGroupKey("TEST-PASS-1234", "local:bob")
        assertNotEquals(alice.toHex(), bob.toHex())
        assertNotEquals(SyncProtocol.groupTag(alice), SyncProtocol.groupTag(bob))
        assertNotEquals(alice.toHex(), SyncProtocol.deriveGroupKey("TEST-PASS-1234").toHex())
        assertEquals(expectedTag, SyncProtocol.groupTag(SyncProtocol.deriveGroupKey("TEST-PASS-1234")))
    }

    @Test
    fun signingEnvelopeJsonAndLwwRules() {
        val key = SyncProtocol.deriveGroupKey("TEST-PASS-1234")

        // 请求签名向量与校验
        val body = """{"protocol": 1}""".toByteArray(Charsets.UTF_8)
        val signature = SyncProtocol.sign(
            key, "POST", "/api/freenote/manifest", 1_700_000_000_000L, "abc123", body
        )
        assertEquals(expectedSignature, signature)
        assertTrue(
            SyncProtocol.verifySignature(
                key, "POST", "/api/freenote/manifest", 1_700_000_000_000L, "abc123", body, signature
            )
        )
        assertFalse(
            SyncProtocol.verifySignature(
                key, "POST", "/api/freenote/manifest", 1_700_000_000_000L, "abc123",
                """{"protocol": 2}""".toByteArray(Charsets.UTF_8), signature
            )
        )

        // AES-GCM 跨语言向量（与 Python seal_with_nonce 相同输入）
        val nonce = ByteArray(12) { it.toByte() }
        val plain = """{"hello":"world"}""".toByteArray(Charsets.UTF_8)
        val sealed = SyncProtocol.sealWithNonce(key, nonce, plain)
        assertEquals(
            "000102030405060708090a0b" +
                "9e484eed60e0e9214a63d00fd77fc3609dc3b079c5b584ac81a0614ce76bac60b0",
            sealed.toHex()
        )
        assertTrue(plain.contentEquals(SyncProtocol.unseal(key, sealed)))

        // 随机 nonce / 篡改 / 密钥不符 / 数据过短
        val fresh = SyncProtocol.seal(key, plain)
        assertFalse(fresh.copyOfRange(0, 12).contentEquals(SyncProtocol.seal(key, plain).copyOfRange(0, 12)))
        assertNull(SyncProtocol.unseal(SyncProtocol.deriveGroupKey("OTHER"), fresh))
        val tampered = fresh.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 1).toByte()
        assertNull(SyncProtocol.unseal(key, tampered))
        assertNull(SyncProtocol.unseal(key, ByteArray(20)))

        // LWW（协议 v5）：新者胜 → 同刻删除优先 → 同刻同态按内容哈希小者胜
        fun entry(updatedAt: Long, deleted: Boolean, hash: String) =
            SyncProtocol.ManifestEntry(updatedAt, deleted, hash)
        assertTrue(SyncProtocol.incomingWins(null, entry(5, false, "aa")))
        assertFalse(SyncProtocol.incomingWins(entry(6, false, "aa"), entry(5, false, "aa")))
        assertTrue(SyncProtocol.incomingWins(entry(5, false, "aa"), entry(6, false, "bb")))
        assertTrue(SyncProtocol.incomingWins(entry(5, false, "aa"), entry(5, true, "zz")))
        assertFalse(SyncProtocol.incomingWins(entry(5, true, "zz"), entry(5, false, "aa")))
        // 同刻同态（含双方都已删除）：哈希小者胜，与到达顺序无关
        assertTrue(SyncProtocol.incomingWins(entry(5, false, "bb"), entry(5, false, "aa")))
        assertFalse(SyncProtocol.incomingWins(entry(5, false, "aa"), entry(5, false, "bb")))
        assertTrue(SyncProtocol.incomingWins(entry(5, true, "zz"), entry(5, true, "aa")))
        assertFalse(SyncProtocol.incomingWins(entry(5, true, "aa"), entry(5, true, "zz")))
        assertFalse(SyncProtocol.incomingWins(entry(5, false, "aa"), entry(5, false, "aa")))
        // 哈希缺失（旧清单）：保守保留本机版本
        assertFalse(SyncProtocol.incomingWins(entry(5, false, "aa"), entry(5, false, "")))
        assertFalse(SyncProtocol.incomingWins(entry(5, false, ""), entry(5, false, "aa")))

        // 内容哈希：与 Windows 端共享的向量（同毫秒平局仲裁的确定性依据）
        val hashNote = Note(
            id = "note-1",
            title = "标题",
            content = "内容",
            color = 0xFFBBDEFB,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            isPinned = true,
            tags = "a,b",
            reminderTime = null,
            isDeleted = false,
            deletedAt = null,
            isLocked = true,
            isChecklist = true
        )
        assertEquals("b2dca66e3a61816f", SyncProtocol.noteContentHash(hashNote))
        // 不含 id/updatedAt：同一内容不同 id/时间戳的哈希一致
        assertEquals(
            SyncProtocol.noteContentHash(hashNote),
            SyncProtocol.noteContentHash(hashNote.copy(id = "other", updatedAt = 9_999L))
        )
        assertNotEquals(
            SyncProtocol.noteContentHash(hashNote),
            SyncProtocol.noteContentHash(hashNote.copy(title = "改"))
        )
        // folder 参与哈希（协议 v5），与 Windows 端共享的向量
        val folderHashNote = Note(
            id = "x",
            title = "t",
            content = "c",
            color = 1L,
            createdAt = 2L,
            updatedAt = 3L,
            folder = "工作/项目A"
        )
        assertEquals("4ac050ca11394ca9", SyncProtocol.noteContentHash(folderHashNote))
        assertNotEquals(
            SyncProtocol.noteContentHash(folderHashNote),
            SyncProtocol.noteContentHash(folderHashNote.copy(folder = ""))
        )
        // 墓碑哈希只取决于 deletedAt（服务端按同一规则构造）
        assertEquals(
            "f4095cceff9ebc49",
            SyncProtocol.noteContentHash(SyncProtocol.tombstoneNote("gone", 42L))
        )
        assertEquals(
            SyncProtocol.noteContentHash(SyncProtocol.tombstoneNote("another", 42L)),
            SyncProtocol.noteContentHash(SyncProtocol.tombstoneNote("gone", 42L))
        )

        // 条目排序：与 Python 字符串排序（码点序）一致的 UTF-8 字节序
        assertTrue(SyncProtocol.compareIds("id-a", "id-b") < 0)
        assertEquals(0, SyncProtocol.compareIds("同", "同"))
        assertTrue(SyncProtocol.compareIds("a", "ab") < 0)

        // 清单解析
        val manifest = SyncProtocol.manifestFromEntries(
            SyncProtocol.entriesPayload(
                listOf(
                    SyncEntry("id-b", 20L, false, "bb-hash"),
                    SyncEntry("id-a", 10L, true, "aa-hash")
                )
            )
        )
        assertEquals(2, manifest.size)
        assertEquals(SyncProtocol.ManifestEntry(20L, false, "bb-hash"), manifest["id-b"])
        assertEquals(SyncProtocol.ManifestEntry(10L, true, "aa-hash"), manifest["id-a"])
        assertTrue(SyncProtocol.manifestFromEntries(JSONArray()).isEmpty())
        assertTrue(SyncProtocol.manifestFromEntries(null).isEmpty())
        assertTrue(SyncProtocol.manifestFromEntries(JSONArray().put(JSONObject())).isEmpty())

        // 便签 JSON 往返（可空字段）与墓碑
        val note = Note(
            id = "note-1",
            title = "标题",
            content = "内容",
            color = 0xFFBBDEFB,
            createdAt = 1_000L,
            updatedAt = 2_000L,
            isPinned = true,
            tags = "a,b",
            reminderTime = null,
            isDeleted = false,
            deletedAt = null,
            isLocked = true,
            isChecklist = true
        )
        val restored = SyncProtocol.noteFromJson(SyncProtocol.noteToJson(note))
        assertEquals(note, restored)
        assertEquals(null, restored.reminderTime)
        assertEquals(null, restored.deletedAt)

        // 文件夹路径随 JSON 往返（协议 v5）
        val folderNote = note.copy(folder = "工作/项目A")
        assertEquals(folderNote, SyncProtocol.noteFromJson(SyncProtocol.noteToJson(folderNote)))

        val tombstone = SyncProtocol.noteFromJson(
            SyncProtocol.noteToJson(SyncProtocol.tombstoneNote("gone", 42L))
        )
        assertTrue(tombstone.isDeleted)
        assertEquals(42L, tombstone.updatedAt)
        assertEquals(42L, tombstone.deletedAt)

        // 服务器地址归一化与明文 HTTP 提示
        assertEquals("http://192.168.1.8:8000", ServerUrl.normalize("192.168.1.8:8000"))
        assertEquals("http://192.168.1.8:8000", ServerUrl.normalize("http://192.168.1.8:8000/"))
        assertEquals("", ServerUrl.normalize("   "))
        assertFalse(ServerUrl.needsHttpsHint("http://192.168.1.8:8000"))
        assertFalse(ServerUrl.needsHttpsHint("http://10.0.0.5:8000"))
        assertFalse(ServerUrl.needsHttpsHint("http://localhost:8000"))
        assertFalse(ServerUrl.needsHttpsHint("https://example.com"))
        assertTrue(ServerUrl.needsHttpsHint("http://example.com"))
        assertTrue(ServerUrl.needsHttpsHint("http://8.8.8.8:8000"))
    }
}
