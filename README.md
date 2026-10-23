# Zireael v2.0.0 — Android İndirme Yöneticisi 🗡️⚡

Zireael v2.0.0, Android 8.0+ (API 26+) cihazlar için geliştirilmiş çok parçalı (multi-chunk), arka plan servisli ve akış yakalayıcılı (WebView Sniffer + `yt-dlp` + `FFmpegKit`) modern bir indirme yöneticisidir.

## 🌟 v2.0.0 Yenilikleri ve Mimari

1. **Çoklu Parça Doğrudan İndirme Motoru (`DirectEngine`):**
   - Dosyaları varsayılan **4 parçaya** bölerek aynı anda indirir, duraklat/devam et ve eşzamanlı indirme kuyruğu (varsayılan **2 dosya**) desteği sunar.
   - Tüm ayarlar uygulama içinden değiştirilebilir.

2. **Video & Ses Platformları Motoru (`MediaEngine` — `youtubedl-android`):**
   - Video ve ses sitelerindeki linkleri çözerek kalite seçeneklerini sunar:
     - **Video:** `1080p`, `720p`, `480p` (`MP4`), `H.265 / HEVC`, `MKV`, `WebM`, `MOV`, `AVI`, `WMV`, `MPEG-TS (.ts)`, `fMP4 (.m4s)`
     - **Ses:** `MP3`, `M4A`, `AAC (HLS)`, `WAV`, `OGG`, `FLAC`, `OPUS`

3. **HLS Akış Motoru (`FfmpegHlsEngine` — `FFmpegKit.executeWithArgumentsAsync`):**
   - `.m3u8` (H.265 HLS, AAC HLS, TS, fMP4) akışlarını `Array<String>` argüman yapısıyla tırnaklama/ayrıştırma hatası olmadan indirir ve `MediaStore` üzerinden cihazın **İndirilenler / Galeri** klasörüne aktarır.

4. **Uygulama İçi Mini Tarayıcı & Güvenli Oturum Yönetimi (`VideoSnifferWebViewClient` & `EphemeralSessionStore`):**
   - Sayfadaki `.m3u8` ve medya akışlarını otomatik yakalar ve alt panelde **"Videoyu İndir"** seçeneği sunar.
   - Cloudflare doğrulaması geçildiğinde oluşan `Cookie`, `Referer` ve `User-Agent` bilgileri **yalnızca RAM'de**, **yalnızca ilgili site (domain)** ve **yalnızca ilgili indirme süresince** tutulur; diske/veritabanına yazılmaz, loglanmaz ve indirme bittiğinde silinir.

