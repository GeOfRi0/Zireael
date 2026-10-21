package com.indirici.app.domain.exception

/**
 * DRM korumalı içerik tespit edildiğinde fırlatılır.
 * Kural gereği DRM aşılmaya çalışılmaz ve kullanıcıya sabit uyarı mesajı gösterilir.
 */
class DrmProtectedException : Exception(DRM_UNSUPPORTED_MESSAGE) {
    companion object {
        const val DRM_UNSUPPORTED_MESSAGE = "Bu içerik DRM korumalı, desteklenmiyor"
    }
}
