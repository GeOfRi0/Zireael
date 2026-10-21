package com.indirici.app.domain.exception

import com.indirici.app.domain.model.EngineType

/**
 * DRM korumalı içerik tespit edildiğinde fırlatılır.
 * Kural gereği DRM aşılmaya çalışılmaz ve kullanıcıya sabit uyarı mesajı gösterilir.
 */
class DrmProtectedException : Exception(DRM_UNSUPPORTED_MESSAGE) {
    companion object {
        const val DRM_UNSUPPORTED_MESSAGE = "Bu içerik DRM korumalı, desteklenmiyor"
    }
}

/**
 * Doğrudan indirme motoru (DirectEngine) HTTP başlıklarını veya yönlendirmeyi (redirect)
 * incelediğinde kaynağın bir HLS (.m3u8) veya medya akışı olduğunu tespit ederse,
 * görevin doğru motora (FFMPEG_HLS / MEDIA) devredilmesi için fırlatılır.
 */
class EngineRerouteRequiredException(
    val targetEngineType: EngineType
) : Exception("İndirme görevi ${targetEngineType.name} motoruna yönlendirildi")
