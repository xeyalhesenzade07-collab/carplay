# CarPlay APK — Changan Qiyuan A05 üçün

Carlinkit tipli USB CarPlay adapteri ilə işləyən sadə Android proqramı.

## Nə lazımdır
- Kompüter (Windows / Mac / Linux) + **Android Studio** (pulsuz)
- **Carlinkit adapter** (məs. CPC200-CCPA və ya oxşar, VID 0x1314)
- Test üçün: istənilən Android planşet/telefon + USB-OTG kabel (maşına qoşmazdan əvvəl)

## Addımlar
1. Android Studio-da `File → Open` ilə bu qovluğu açın, Gradle sinxronlaşmasını gözləyin.
2. `Build → Build APK(s)` — APK `app/build/outputs/apk/debug/` qovluğunda yaranır.
3. Əvvəlcə planşetdə yoxlayın: adapteri OTG ilə qoşun, iPhone-u adapterə qoşun.
4. Logları izləyin: `adb logcat -s CarPlayApk`
5. İşləyəndən sonra APK-nı maşının ekranına quraşdırın (ustaların istifadə etdiyi üsulla:
   mühəndis menyusu / USB-dən quraşdırma).

## Fayllar
- `Dongle.kt` — adapterin protokolu (mesaj başlığı, OPEN, toxunuş, heartbeat)
- `MainActivity.kt` — USB əlaqəsi, video dekodlama, toxunuş
- `usb_filter.xml` — adapter qoşulanda proqramın avtomatik açılması

## Hazır olanlar
- [x] Adapteri tapmaq və USB icazəsi
- [x] Sessiya açmaq + heartbeat
- [x] Video (H.264 → ekran)
- [x] Toxunuş

## Növbəti addımlar
- [ ] Səs: `AUDIO` mesajlarından PCM-i `AudioTrack`-ə ötürmək
- [ ] Mikrofon (Siri, zənglər)
- [ ] Simsiz qoşulma üçün adapterə Wi-Fi/Bluetooth parametrlərini göndərmək
- [ ] Qopma və yenidən qoşulma

## Mənbə
Protokol açıq mənbəli `node-carplay` layihəsinə əsaslanır (github.com/rhysmorgan134/node-carplay).
Adapterin firmware-i fərqlidirsə, sabitlər dəyişə bilər.
