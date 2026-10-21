package com.indirici.app.domain.model

enum class EngineType {
    DIRECT,     // V1: Doğrudan dosya linkleri (OkHttp çoklu parça)
    MEDIA,      // V2: Video/ses siteleri (youtubedl-android / yt-dlp)
    FFMPEG_HLS  // WebView Sniffer & HLS Akış Motoru (FFmpegKit.executeAsync)
}
