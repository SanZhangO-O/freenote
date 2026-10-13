package com.zqr.freenote.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ServerUrl 主机分类与协议补全：必须与 Windows `config.py` 的
 * `is_local_host` / `is_plaintext_public_url` 判定保持一致，
 * 否则同一地址在两端会得到不同的默认协议或 WebDAV 明文阻断结果。
 */
class ServerUrlTest {

    @Test
    fun classifiesPrivateHosts() {
        assertTrue(ServerUrl.isPrivateHost("localhost"))
        assertTrue(ServerUrl.isPrivateHost("127.0.0.1"))
        assertTrue(ServerUrl.isPrivateHost("127.8.8.8"))
        assertTrue(ServerUrl.isPrivateHost("10.0.0.5"))
        assertTrue(ServerUrl.isPrivateHost("192.168.1.8"))
        assertTrue(ServerUrl.isPrivateHost("172.16.3.4"))
        assertTrue(ServerUrl.isPrivateHost("172.31.255.254"))
        assertTrue(ServerUrl.isPrivateHost("169.254.1.1"))
        assertTrue(ServerUrl.isPrivateHost("100.64.0.1"))
        assertTrue(ServerUrl.isPrivateHost("nas.local"))
        // IPv6：环回（含展开式）、IPv4 映射、链路本地整段、唯一本地
        assertTrue(ServerUrl.isPrivateHost("::1"))
        assertTrue(ServerUrl.isPrivateHost("0:0:0:0:0:0:0:1"))
        assertTrue(ServerUrl.isPrivateHost("::ffff:192.168.1.8"))
        assertTrue(ServerUrl.isPrivateHost("fe80::1"))
        assertTrue(ServerUrl.isPrivateHost("fe90::1"))
        assertTrue(ServerUrl.isPrivateHost("fc00::1"))
        assertTrue(ServerUrl.isPrivateHost("fd00::1"))
    }

    @Test
    fun classifiesPublicHosts() {
        assertFalse(ServerUrl.isPrivateHost("example.com"))
        assertFalse(ServerUrl.isPrivateHost("8.8.8.8"))
        assertFalse(ServerUrl.isPrivateHost("2001:4860:4860::8888"))
        // 域名以 fc/fd/fe80: 开头不是 IPv6 字面量，不得判成内网
        assertFalse(ServerUrl.isPrivateHost("fcc.gov"))
        assertFalse(ServerUrl.isPrivateHost("fdroid.org"))
        assertFalse(ServerUrl.isPrivateHost("fd.example.com"))
        // 前导零 IPv4 非法，与 Python ipaddress 一致
        assertFalse(ServerUrl.isPrivateHost("127.000.000.001"))
        assertFalse(ServerUrl.isPrivateHost("192.168.001.001"))
        assertFalse(ServerUrl.isPrivateHost("192.168.1.300"))
    }

    @Test
    fun normalizesSchemeByHost() {
        assertEquals("http://192.168.1.8:8000", ServerUrl.normalize("192.168.1.8:8000"))
        assertEquals("http://localhost:8000", ServerUrl.normalize("localhost:8000"))
        assertEquals("http://[fe90::1]:8000", ServerUrl.normalize("[fe90::1]:8000"))
        assertEquals("https://example.com:8000", ServerUrl.normalize("example.com:8000"))
        assertEquals("https://fdroid.org/dav", ServerUrl.normalize("fdroid.org/dav"))
        assertEquals("http://a.b/c", ServerUrl.normalize("http://a.b/c/"))
        assertEquals("", ServerUrl.normalize("  "))
    }

    @Test
    fun blocksOnlyPlaintextPublicUrls() {
        assertFalse(ServerUrl.isPlaintextPublic("http://192.168.1.8/dav"))
        assertFalse(ServerUrl.isPlaintextPublic("https://dav.example.com/x"))
        assertTrue(ServerUrl.isPlaintextPublic("http://dav.example.com/x"))
        // fc/fd 开头的公网域名不得绕过明文上传阻断
        assertTrue(ServerUrl.isPlaintextPublic("http://fdroid.org/dav"))
        assertTrue(ServerUrl.isPlaintextPublic("http://fcc.gov/dav"))
    }
}
