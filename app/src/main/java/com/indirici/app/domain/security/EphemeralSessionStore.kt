package com.indirici.app.domain.security

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * V3 (WebView / Cloudflare) Geçici Oturum Deposu:
 * Kullanıcının mini tarayıcıda Cloudflare doğrulamasını geçtikten sonra oluşan
 * Cookie, Referer ve User-Agent bilgilerini SADECE RAM'de, SADECE ilgili indirme süresi boyunca
 * ve SADECE ilgili site (domain) için tutar.
 *
 * Güvenlik kuralları:
 * 1. Veritabanına (Room), SharedPreferences'a veya diske kesinlikle yazılmaz.
 * 2. Loglanmaması için toString() çıktısı [REDACTED] ile maskelenmiştir.
 * 3. Farklı bir alan adına yönlendirme (redirect) olursa çerez kesinlikle gönderilmez.
 * 4. İndirme tamamlandığında, hata aldığında veya iptal edildiğinde bellekten hemen silinir.
 */
data class DomainScopedSession(
    val allowedHost: String,
    val userAgent: String,
    val cookieHeader: String,
    val referer: String = ""
) {
    override fun toString(): String {
        return "DomainScopedSession(allowedHost=$allowedHost, referer=$referer, userAgent=[REDACTED], cookieHeader=[REDACTED])"
    }
}

class EphemeralSessionStore {
    private val sessionsByDownloadId = ConcurrentHashMap<Long, DomainScopedSession>()

    fun putSession(
        downloadId: Long,
        targetUrl: String,
        userAgent: String,
        cookieHeader: String,
        referer: String = ""
    ) {
        val host = extractNormalizedHost(targetUrl)
            ?: extractNormalizedHost(referer)
            ?: return
        if (cookieHeader.isBlank() && userAgent.isBlank() && referer.isBlank()) return
        sessionsByDownloadId[downloadId] = DomainScopedSession(
            allowedHost = host,
            userAgent = userAgent.trim(),
            cookieHeader = cookieHeader.trim(),
            referer = referer.trim()
        )
    }

    /**
     * Ön analiz (analyzeUrl) sırasında kullanılan geçici oturumu gerçek indirme görevine (taskId) taşır
     * ve geçici kaydı siler.
     */
    fun moveSession(fromId: Long, toDownloadId: Long) {
        val existing = sessionsByDownloadId.remove(fromId) ?: return
        sessionsByDownloadId[toDownloadId] = existing
    }

    /**
     * İstek yapılacak URL, oturumun oluşturulduğu host ile birebir eşleşiyorsa (veya aynı sitenin alt alan adıysa)
     * başlıkları döndürür; aksi halde (farklı bir üçüncü parti siteye yönlendirme varsa) Cookie sızdırmaz.
     */
    fun resolveHeadersForUrl(downloadId: Long, requestUrl: String): Map<String, String> {
        val session = sessionsByDownloadId[downloadId] ?: return emptyMap()
        val requestHost = extractNormalizedHost(requestUrl) ?: return emptyMap()

        if (!isSameSiteHost(requestHost, session.allowedHost)) {
            return emptyMap()
        }

        val headers = LinkedHashMap<String, String>()
        if (session.userAgent.isNotEmpty()) {
            headers["User-Agent"] = session.userAgent
        }
        if (session.referer.isNotEmpty()) {
            headers["Referer"] = session.referer
        }
        if (session.cookieHeader.isNotEmpty()) {
            headers["Cookie"] = session.cookieHeader
        }
        return headers
    }

    fun hasSession(downloadId: Long): Boolean {
        return sessionsByDownloadId.containsKey(downloadId)
    }

    fun clearSession(downloadId: Long) {
        sessionsByDownloadId.remove(downloadId)
    }

    fun clearAll() {
        sessionsByDownloadId.clear()
    }

    private fun extractNormalizedHost(url: String): String? {
        if (url.isBlank()) return null
        val clean = url.trim().substringBefore('?').substringBefore('#')
        val withScheme = if (clean.contains("://")) clean else "https://$clean"
        val fromUri = try {
            URI(withScheme).host
        } catch (_: Exception) {
            null
        }
        val host = fromUri ?: Regex("""^[a-zA-Z]+://([^/?#\s:@]+)""").find(withScheme)?.groupValues?.get(1)
        return host?.lowercase()?.removePrefix("www.")
    }

    private fun isSameSiteHost(requestHost: String, allowedHost: String): Boolean {
        if (requestHost == allowedHost) return true
        if (requestHost.endsWith(".$allowedHost") || allowedHost.endsWith(".$requestHost")) return true

        // Örn: dl1.ornek.com ile files.ornek.com aynı kök alan adına (ornek.com) sahipse izin ver
        val reqRoot = extractBaseDomain(requestHost)
        val allowedRoot = extractBaseDomain(allowedHost)
        return reqRoot != null && reqRoot == allowedRoot
    }

    private fun extractBaseDomain(host: String): String? {
        val parts = host.split('.').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        // com.tr, co.uk gibi çift parçalı ülke uzantılarını dikkate al
        val lastTwo = "${parts[parts.size - 2]}.${parts.last()}"
        return if (parts.size >= 3 && parts[parts.size - 2].length <= 3 && parts.last().length == 2) {
            "${parts[parts.size - 3]}.$lastTwo"
        } else {
            lastTwo
        }
    }

    companion object {
        const val PRE_ENQUEUE_SESSION_ID = -999L
    }
}
