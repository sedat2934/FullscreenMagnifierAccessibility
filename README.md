# Fullscreen Magnifier Accessibility v4

Android 13+ (Android 16 hedefli) AccessibilityService tabanlı tam ekran büyüteç.

## Davranış
- Erişilebilirlik (♿) düğmesine basınca ilk dokunuşu bekler.
- İlk dokunulan noktada 3x tam ekran büyütme açılır.
- Büyütme açıkken **tek parmak sürükleme** yalnızca büyütülmüş görüntüyü taşır (panning); alttaki uygulama hareketi almaz.
- Büyütme açıkken **iki parmak pinch-out / pinch-in** ile zoom artırılır / azaltılır.
- Zoom aralığı: **1.5x–8x**.
- Pinch sırasında iki parmağın orta noktası mümkün olduğunca zoom odağı olarak korunur.
- Erişilebilirlik düğmesine tekrar basınca büyütme kapanır.
- Navigasyon alanına tek dokunma, Home/Back/erişilebilirlik tuşlarını kullanılabilir tutmak için geçici olarak aktarılır.

## Log
```sh
su -c 'logcat -d | grep FSMagService'
```

## Derleme
Repo GitHub'a yüklendiğinde `.github/workflows/build-apk.yml` ile APK derlenebilir.
