package com.indirici.app.domain.exception

import com.indirici.app.domain.model.EngineType

/**
 * yt-dlp / MediaEngine link çözümleme veya indirme başarısız olduğunda fırlatılır.
 * Ham motor hatasını ve kurulu yt-dlp sürüm bilgisini taşır.
 */
class MediaEngineException(
    message: String = "Bu link indirilemedi.",
    val rawError: String,
    val ytDlpVersion: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Doğrudan indirme motoru (DirectEngine) HTTP başlıklarını veya yönlendirmeyi (redirect)
 * incelediğinde kaynağın bir HLS (.m3u8) veya medya akışı olduğunu tespit ederse,
 * görevin doğru motora (FFMPEG_HLS / MEDIA) devredilmesi için fırlatılır.
 */
class EngineRerouteRequiredException(
    val targetEngineType: EngineType
) : Exception("İndirme görevi ${targetEngineType.name} motoruna yönlendirildi")
