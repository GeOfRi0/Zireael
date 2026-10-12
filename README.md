# Zireael for Android 🗡️⚡

Yüksek performanslı, çok parçalı (multi-segment parallel), ağ kesintilerine dayanıklı ve doğadan ilham alan dokunsal (tactile & nature-inspired) arayüze sahip modern Android dosya indirme yöneticisi.

---

## 🌟 Temel Özellikler

### 1. 🚀 Çok Parçalı Turbo İndirme Motoru (Multi-Segment Engine)
- **HTTP Range & Parallel Streaming:** Büyük dosyaları eşzamanlı parçalara (`chunk`) bölerek indirme hızını maksimuma çıkarır.
- **Disk Alanı Tahsisi (Pre-allocation):** İndirme başlamadan önce `Content-Length` başlığı okunur ve `RandomAccessFile.setLength()` ile diskte yer ayrılır. Yetersiz depolama alanında işlem başlamadan kullanıcı uyarılır.
- **Doğrudan Akış (Direct Streaming):** OkHttp `InputStream` tamponu 64KB boyutunda optimize edilmiştir. Bellekte veri biriktirilmeden doğrudan depolama alanına yazılır.

### 2. ⚡ Bellek & I/O Optimizasyonu
- **Akıllı Room DB Kaydı:** İlerleme verileri her an diske yazılmaz; sadece her **%5'lik değişimde**, uygulama arka plana atıldığında (`flushToDatabase`) veya kullanıcı indirmeyi duraklattığında diske kaydedilir.
- **Tekil OkHttpClient (Singleton ConnectionPool):** Tüm parçalar ve istekler `OkHttpProvider` üzerindeki yüksek eşzamanlı limitli (Dispatcher) tek bir istemciyi kullanır.
- **UI Debounce/Throttle:** StateFlow indirme hızı ve yüzdeleri 500ms aralıklarla arayüze aktarılır; Compose tarafında `derivedStateOf` ile gereksiz çizimlerin önüne geçilir.

### 3. 🌐 Akıllı Ağ Yönetimi & Hız Kontrolü
- **ConnectivityManager NetworkCallback:** Ağ koptuğunda indirmeleri güvenli bir şekilde duraklatır; internet bağlantısı sağlandığında otomatik olarak kaldığı yerden sürdürür.
- **Sadece Wi-Fi Üzerinden İndir:** Kullanıcı tercihi ile mobil veri tüketimini engelleyen ağ filtresi.
- **Maksimum İndirme Hızı Limiti:** Kullanıcının belirlediği hız sınırına (`KB/s` / `MB/s`) göre okuma sürelerini denetleyen ve `delay()` ile veri çekme hızını dengeleyen akıllı fren mekanizması.

### 4. 🔒 Güvenlik & Dosya Doğrulama
- **MD5 / SHA-256 Hash Doğrulama:** İndirilen dosyaların bütünlüğünü doğrulamak için kullanıcı girdi alanı ve arka planda otomatik hash hesaplama.
- **Network Security Config:** Şifrelenmemiş düz metin (Cleartext HTTP) trafiği tamamen engellenmiştir.
- **Sıkılaştırılmış Manifest:** Dışarıya açılması gerekmeyen tüm bileşenler `exported="false"` yapılandırılmıştır.

### 5. 📂 Otomatik Kategori Sınıflandırma
- Sunucudan gelen MIME tipi ve dosya uzantılarına göre indirmeler otomatik olarak ayrıştırılır:
  - 📱 **Uygulamalar (APK)**: `.apk`, `.xapk`, `.apks`
  - 🎬 **Medya (MP4, MP3)**: Video ve ses dosyaları
  - 📄 **Belgeler**: `.pdf`, `.docx`, `.xlsx`, `.epub`
  - 📦 **Arşivler**: `.zip`, `.rar`, `.7z`, `.tar`, `.gz`

### 6. 🌿 Doğadan İlham Alan Dokunsal Arayüz (Tactile & Nature-Inspired Design)
- **Kum Beji & Adaçayı Yeşili Paleti:** Göz yormayan, organik tonlar (`#FDFBF7`, Adaçayı Yeşili, Orman Yeşili ve Toprak tonları).
- **Yumuşak Gölgeler & Squircle Hatlar:** Sert köşelerden uzak, dokunsal ve fiziksel hissettiren derinlikli kart ve butonlar.
- **Organik İlerleme Çubuğu:** Fiziksel bir akışkanlık hissi veren animasyonlu göstergeler.

---

## 🏗 Mimari Yapı

```
com.example/
├── data/
│   ├── engine/
│   │   ├── TurboDownloadEngine.kt        # Çok parçalı akış motoru & hız kısıtlayıcı
│   │   ├── OkHttpProvider.kt             # Singleton OkHttpClient & ConnectionPool
│   │   ├── NetworkConnectivityMonitor.kt # ConnectivityManager NetworkCallback
│   │   ├── DownloadQueueManager.kt       # İndirme sırası ve Wi-Fi filtresi
│   │   ├── MediaSniffer.kt               # Web medyası yakalama motoru
│   │   └── SystemDownloadNotificationHelper.kt
│   ├── local/
│   │   ├── AppDatabase.kt                # Room Veritabanı
│   │   ├── DownloadDao.kt                # İndirme sorguları & optimize güncellemeler
│   │   └── DownloadEntity.kt             # İndirme veri modeli & hash alanları
│   ├── model/
│   │   ├── DownloadModels.kt             # Durumlar & FileCategory sınıflandırıcı
│   │   └── ChromeHeaderConfig.kt
│   ├── repository/
│   │   └── DownloadRepository.kt         # Veri deposu & arka plan flush köprüsü
│   └── service/
│       └── DownloadForegroundService.kt  # Arka plan ön plan servisi
├── ui/
│   ├── components/                       # Dokunsal kartlar ve bildirim bileşenleri
│   ├── screens/
│   │   ├── ActiveDownloadsScreen.kt      # Karşılama, URL girişi ve indirmeler
│   │   ├── DownloadsLibraryScreen.kt     # Kategori sekmeleri & Hash doğrulama
│   │   ├── TurboEngineScreen.kt          # Motor ayarları & Hız limiti kontrolü
│   │   ├── DashboardScreen.kt            # Ana gezinti çubuğu
│   │   └── MediaPreviewDialog.kt         # Medya önizleme ve SHA-256 doğrulama
│   ├── theme/                            # Doğal renk paleti ve tipografi
│   └── viewmodel/
│       └── DownloadViewModel.kt          # Debounce UI StateFlow yönetimi
└── MainActivity.kt                       # Intent filtresi ve yaşam döngüsü
```

---

## 🛠 Yerel Derleme & Çalıştırma (Local Build)

Projeyi yerel ortamınızda derlemek için:

```bash
# Debug APK Derleme
./gradlew assembleDebug

# Birim & Entegrasyon Testlerini Çalıştırma
./gradlew testDebugUnitTest
```

---

## 🧪 Test Kapsamı

Proje 3 temel test kümesiyle doğrulanmaktadır:
1. **`ChunkCalculationMathTest`**: 0 byte, 1 byte, tek/çift parça ve devasa dosya boyutları için matematiksel sınır durumları (edge cases).
2. **`NetworkIntegrationMockServerTest`**: MockWebServer ile ani ağ kopması (`DISCONNECT_AT_START`), yavaş sunucu yanıtı ve HTTP 403 Forbidden senaryoları.
3. **`DownloadProgressComposeUITest`**: Compose StateFlow reaktif yüzdelerinin arayüzde doğru yansımasının doğrulanması.

---

## 📄 Lisans

Bu proje [Apache License 2.0](LICENSE) altında lisanslanmıştır.
