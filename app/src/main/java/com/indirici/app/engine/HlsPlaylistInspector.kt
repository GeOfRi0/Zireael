package com.indirici.app.engine

import java.net.URL

/**
 * Bir HLS (.m3u8) çalma listesinin özeti.
 * - Ana liste (master): kalite varyantları ve ayrı ses/altyazı listeleri [childPlaylistUris] içinde.
 * - Medya listesi (media): gerçek video/ses parçaları [segmentUris] içinde.
 */
data class HlsPlaylistSummary(
    val isMaster: Boolean,
    val variantCount: Int,
    val maxHeight: Int?,
    val bestVariantUri: String?,
    val childPlaylistUris: List<String>,
    val segmentUris: List<String>,
    val totalDurationSec: Double,
    val isEndList: Boolean
)

/**
 * Sniffer'ın m3u8 içeriğini tanıması için hafif, bağımlılıksız ayrıştırıcı.
 * Ana liste / alt liste ayrımı sayesinde kullanıcıya tek tek parça veya sessiz
 * alt varyant listeleri yerine birleştirilebilir ana akış sunulur.
 */
object HlsPlaylistInspector {

    private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
    private val BANDWIDTH_REGEX = Regex("""(?:^|[,:])BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
    private val URI_ATTR_REGEX = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE)

    fun isPlaylistContent(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        return text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")
    }

    fun parse(baseUrl: String, text: String): HlsPlaylistSummary {
        var isMaster = false
        var variantCount = 0
        var maxHeight: Int? = null
        var bestScore = -1L
        var bestVariant: String? = null
        var pendingVariantScore: Long? = null
        val children = LinkedHashSet<String>()
        val segments = ArrayList<String>()
        var duration = 0.0
        var endList = false

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim { it.isWhitespace() || it == '\uFEFF' }
            if (line.isEmpty()) continue
            val upper = line.uppercase()
            when {
                upper.startsWith("#EXT-X-STREAM-INF") -> {
                    isMaster = true
                    variantCount++
                    val height = RESOLUTION_REGEX.find(line)?.groupValues?.get(1)?.toIntOrNull()
                    if (height != null && (maxHeight == null || height > maxHeight)) maxHeight = height
                    val bandwidth = BANDWIDTH_REGEX.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    pendingVariantScore = (height ?: 0).toLong() * 1_000_000_000L + bandwidth
                }
                upper.startsWith("#EXT-X-MEDIA:") || upper.startsWith("#EXT-X-I-FRAME-STREAM-INF") -> {
                    isMaster = true
                    URI_ATTR_REGEX.find(line)?.groupValues?.get(1)?.let { resolve(baseUrl, it) }?.let { children.add(it) }
                }
                upper.startsWith("#EXT-X-MAP") -> {
                    URI_ATTR_REGEX.find(line)?.groupValues?.get(1)?.let { resolve(baseUrl, it) }?.let { segments.add(it) }
                }
                upper.startsWith("#EXTINF:") -> {
                    duration += line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                }
                upper.startsWith("#EXT-X-ENDLIST") -> endList = true
                line.startsWith("#") -> Unit
                else -> {
                    val resolved = resolve(baseUrl, line) ?: continue
                    val score = pendingVariantScore
                    if (score != null) {
                        children.add(resolved)
                        if (score > bestScore) {
                            bestScore = score
                            bestVariant = resolved
                        }
                        pendingVariantScore = null
                    } else {
                        segments.add(resolved)
                    }
                }
            }
        }

        return HlsPlaylistSummary(
            isMaster = isMaster,
            variantCount = variantCount,
            maxHeight = maxHeight,
            bestVariantUri = bestVariant,
            childPlaylistUris = children.toList(),
            segmentUris = if (isMaster) emptyList() else segments,
            totalDurationSec = duration,
            isEndList = endList
        )
    }

    /** Göreli (relative) URI'yi baz URL'ye göre çözer; java.net.URI'nin reddettiği karakterlere toleranslıdır. */
    fun resolve(baseUrl: String, reference: String): String? {
        val ref = reference.trim()
        if (ref.isEmpty()) return null
        return runCatching { URL(URL(baseUrl), ref).toString() }.getOrNull()
            ?: ref.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    /** URL'nin dizin kısmını (son '/' dahil, sorgu hariç) döndürür. */
    fun directoryOf(url: String): String {
        val noQuery = url.substringBefore('#').substringBefore('?')
        return noQuery.substringBeforeLast('/', noQuery) + "/"
    }
}
