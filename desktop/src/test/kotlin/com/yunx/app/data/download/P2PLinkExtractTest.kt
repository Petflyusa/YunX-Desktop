package com.yunx.app.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class P2PLinkExtractTest {

    private val hash = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun bareMagnetIsExtractedAsIs() {
        val m = "magnet:?xt=urn:btih:$hash&dn=Some.File.2026.mkv"
        assertEquals(m, P2PLink.extract(m))
        assertEquals("Some.File.2026.mkv", P2PLink.displayName(m))
    }

    @Test
    fun magnetWithTildeAndDotsInNameIsKept() {
        val m = "magnet:?xt=urn:btih:$hash&dn=A.B.4K.HEVC.10bit.Dolby.TrueHD.7.1~GRP.mkv"
        assertEquals(m, P2PLink.extract(m))
    }

    @Test
    fun magnetInsideSurroundingTextIsFound() {
        val m = "magnet:?xt=urn:btih:$hash&dn=x&tr=udp%3A%2F%2Ftracker.example%3A80"
        assertEquals(m, P2PLink.extract("资源如下：$m 。请自取"))
        assertEquals(m, P2PLink.extract("链接（$m）"))
    }

    @Test
    fun uppercaseSchemeAndBase32HashAccepted() {
        val m = "MAGNET:?xt=urn:btih:ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        assertEquals(m, P2PLink.extract("  $m  "))
    }

    @Test
    fun magnetWithoutInfoHashIsRejected() {
        assertNull(P2PLink.extract("magnet:?dn=only-a-name"))
        assertNull(P2PLink.extract("magnet:"))
    }

    @Test
    fun ed2kFileLinkIsExtracted() {
        val e = "ed2k://|file|Demo.File.iso|1048576|0123456789ABCDEF0123456789ABCDEF|/"
        assertEquals(e, P2PLink.extract("下载：$e 完"))
    }

    @Test
    fun ordinaryShareLinkIsNotP2P() {
        assertNull(P2PLink.extract("https://pan.quark.cn/s/abcdef 提取码：1234"))
    }
}
