# Katkıda Bulunma Rehberi (Contributing Guidelines)

Açık kaynak Turbo Downloader projesine katkıda bulunmak istediğiniz için teşekkür ederiz! Aşağıdaki yönergeler projenin kalitesini, mimarisini ve sürdürülebilirliğini korumaya yardımcı olur.

## 🛠 Geliştirme Ortamı & Ön Gereksinimler

- **Android Studio:** Ladybug / Meerkat veya üzeri
- **JDK:** 17+
- **Gradle:** 8.9+ (Kotlin DSL `.gradle.kts`)
- **Minimum SDK:** 26 (Android 8.0 Oreo)
- **Hedef SDK:** 34 (Android 14)

## 🏗 Mimari & Kod Standartları

1. **Jetpack Compose & Material 3:** Tüm arayüz Compose bileşenleri ile yazılır. Keskin köşeler yerine doğadan ilham alan yuvarlak ve dokunsal tasarım prensipleri korunur.
2. **Coroutines & Flow:** Asenkron I/O işlemleri `Dispatchers.IO` üzerinde çalıştırılır, UI güncellemeleri `StateFlow` ile iletilir.
3. **Bellek & Disk Optimizasyonu:**
   - Veriler bellekte biriktirilmeden `RandomAccessFile` ile doğrudan diske stream edilir.
   - İndirme ilerlemesi her an Room'a yazılmaz; sadece her %5'lik değişimde, duraklatmada veya uygulama arka plana geçtiğinde kaydedilir.
   - Tüm ağ istekleri `OkHttpProvider` tekil (Singleton) istemcisi üzerinden yapılır.
4. **Güvenlik:** API anahtarları, sertifikalar veya şifreler asla kod deposuna eklenmez.

## 🧪 Testler

Yapılan her değişiklik için birim testlerinin başarıyla geçtiğinden emin olun:
```bash
./gradlew testDebugUnitTest
```

Eklenen yeni algoritmalar için:
- HTTP Range ve parça matematiği için JUnit testleri ekleyin.
- Ağ kesintileri ve sunucu yanıtları için `MockWebServer` entegrasyon testlerini güncelleyin.
- Compose arayüzü için Robolectric testleri yazın.

## 🚀 Pull Request Süreci

1. Projeyi çatallayın (Fork).
2. Yeni bir özellik veya hata düzeltme dalı oluşturun: `git checkout -b feature/harika-ozellik`
3. Kodlama standartlarına ve testlere uygun olarak geliştirmenizi tamamlayın.
4. Anlaşılır commit mesajları ile commit atın.
5. Dalınızı GitHub'a itin ve ana depoya Pull Request (PR) açın.

---
Lisans: Apache 2.0
