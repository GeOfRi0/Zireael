package com.example.data.engine

import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * MagnetInfo:
 * Structured data representation of a parsed Magnet URI.
 */
data class MagnetInfo(
    val uri: String,
    val xt: String,
    val infoHash: String,
    val displayName: String?,
    val trackers: List<String>,
    val exactLength: Long? = null
)

/**
 * MagnetUriParser:
 * Lightweight and high-performance parser for BitTorrent Magnet URIs (`magnet:?xt=urn:btih:...`).
 * Extracts topic hash (BTIH / BTMH), display title (`dn`), tracker announces (`tr`),
 * and exact file length (`xl`) safely without external dependencies.
 */
object MagnetUriParser {

    /**
     * Checks if a given string represents a BitTorrent Magnet URI.
     */
    fun isMagnet(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val trimmed = url.trim()
        return trimmed.startsWith("magnet:", ignoreCase = true)
    }

    /**
     * Parses a Magnet URI into a structured [MagnetInfo] object.
     * Returns null if the URI is malformed or missing the exact topic hash (xt).
     */
    fun parse(magnetUri: String?): MagnetInfo? {
        if (magnetUri.isNullOrBlank()) return null
        val trimmed = magnetUri.trim()
        if (!isMagnet(trimmed)) return null

        val query = trimmed.substringAfter('?', "")
        if (query.isEmpty()) return null

        val params = query.split('&')
        var xt = ""
        var displayName: String? = null
        val trackers = mutableListOf<String>()
        var exactLength: Long? = null

        for (param in params) {
            val key = param.substringBefore('=').lowercase()
            val rawValue = param.substringAfter('=', "")
            if (rawValue.isBlank()) continue

            val decodedValue = try {
                URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
            } catch (_: Exception) {
                rawValue
            }

            when (key) {
                "xt" -> xt = decodedValue
                "dn" -> displayName = decodedValue
                "tr" -> if (decodedValue.isNotBlank()) trackers.add(decodedValue)
                "xl" -> exactLength = decodedValue.toLongOrNull()
            }
        }

        if (xt.isBlank()) return null

        val infoHash = when {
            xt.contains("urn:btih:", ignoreCase = true) -> xt.substringAfter("urn:btih:").substringBefore('&').trim()
            xt.contains("urn:btmh:", ignoreCase = true) -> xt.substringAfter("urn:btmh:").substringBefore('&').trim()
            else -> xt.substringAfterLast(':').trim()
        }

        if (infoHash.isBlank()) return null

        return MagnetInfo(
            uri = trimmed,
            xt = xt,
            infoHash = infoHash,
            displayName = displayName,
            trackers = trackers.distinct(),
            exactLength = exactLength
        )
    }
}
