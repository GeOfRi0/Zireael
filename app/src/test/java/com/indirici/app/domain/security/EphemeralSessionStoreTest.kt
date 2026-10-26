package com.indirici.app.domain.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EphemeralSessionStoreTest {

    @Test
    fun `resolveHeadersForUrl returns User-Agent Referer and Cookie only for matching site and blocks foreign domains`() {
        val store = EphemeralSessionStore()
        store.putSession(
            downloadId = 42L,
            targetUrl = "https://dosya.ornek.com/indir/master.m3u8",
            userAgent = "CustomAgent/1.0",
            cookieHeader = "cf_clearance=secret_token_123",
            referer = "https://ornek.com/izle"
        )

        // Aynı alan adı ve aynı sitenin alt alan adı -> başlıklar verilmeli
        val sameHostHeaders = store.resolveHeadersForUrl(42L, "https://dosya.ornek.com/part1.ts")
        assertEquals("CustomAgent/1.0", sameHostHeaders["User-Agent"])
        assertEquals("https://ornek.com/izle", sameHostHeaders["Referer"])
        assertEquals("cf_clearance=secret_token_123", sameHostHeaders["Cookie"])

        val siblingSubdomainHeaders = store.resolveHeadersForUrl(42L, "https://cdn1.ornek.com/part1.ts")
        assertEquals("cf_clearance=secret_token_123", siblingSubdomainHeaders["Cookie"])

        // Farklı alan adına yönlendirme -> çerez kesinlikle sızdırılmamalı
        val foreignHostHeaders = store.resolveHeadersForUrl(42L, "https://baska-cdn.net/part1.ts")
        assertTrue(foreignHostHeaders.isEmpty())

        // İndirme bittiğinde silinmeli
        store.clearSession(42L)
        assertTrue(store.resolveHeadersForUrl(42L, "https://dosya.ornek.com/part1.ts").isEmpty())
    }

    @Test
    fun `moveSession transfers pre-enqueue Cloudflare session to taskId and clears temp entry`() {
        val store = EphemeralSessionStore()
        store.putSession(
            downloadId = EphemeralSessionStore.PRE_ENQUEUE_SESSION_ID,
            targetUrl = "https://korumali.ornek.com/arac.zip",
            userAgent = "BrowserUA/2.0",
            cookieHeader = "cf_clearance=verified_999",
            referer = "https://korumali.ornek.com/"
        )

        assertTrue(store.hasSession(EphemeralSessionStore.PRE_ENQUEUE_SESSION_ID))
        store.moveSession(EphemeralSessionStore.PRE_ENQUEUE_SESSION_ID, 101L)

        assertFalse(store.hasSession(EphemeralSessionStore.PRE_ENQUEUE_SESSION_ID))
        val movedHeaders = store.resolveHeadersForUrl(101L, "https://korumali.ornek.com/arac.zip")
        assertEquals("cf_clearance=verified_999", movedHeaders["Cookie"])
    }

    @Test
    fun `DomainScopedSession toString masks sensitive cookie and userAgent`() {
        val session = DomainScopedSession(
            allowedHost = "ornek.com",
            userAgent = "MySecretUA",
            cookieHeader = "cf_clearance=super_secret",
            referer = "https://ornek.com/watch"
        )
        val text = session.toString()
        assertFalse(text.contains("super_secret"))
        assertFalse(text.contains("MySecretUA"))
        assertTrue(text.contains("[REDACTED]"))
    }

    @Test
    fun `resolveHeadersForUrl preserves headers for URLs with unencoded query parameters or missing scheme`() {
        val store = EphemeralSessionStore()
        store.putSession(
            downloadId = 77L,
            targetUrl = "https://cdn.example.com/stream/file.mp4",
            userAgent = "BrowserAgent/1.0",
            cookieHeader = "session=token123",
            referer = "https://example.com/watch"
        )

        // URL with special chars in query parameters (breaks standard java.net.URI)
        val queryUrl = "https://cdn.example.com/stream/file.mp4?token={auth_token}&name=video title"
        val queryHeaders = store.resolveHeadersForUrl(77L, queryUrl)
        assertEquals("session=token123", queryHeaders["Cookie"])

        // URL without scheme
        val schemelessUrl = "cdn.example.com/stream/file.mp4?id=123"
        val schemelessHeaders = store.resolveHeadersForUrl(77L, schemelessUrl)
        assertEquals("session=token123", schemelessHeaders["Cookie"])
    }
}
