# Fullscreen Magnifier Accessibility v3

Android 16 için AccessibilityService tabanlı tam ekran büyütme yardımcı uygulaması.

## v3 davranışı

- Erişilebilirlik düğmesine basılır.
- İlk ekran dokunuşu büyütmenin merkezi olur ve sistemin FULLSCREEN magnification modu 3x açılır.
- Büyütme açıkken **tek parmak sürükleme yalnızca büyütülmüş görüntüyü taşır (pan)**.
- Uygulama alanındaki tek parmak dokunma/sürüklemeleri alttaki uygulamaya iletilmez.
- Alt navigasyon bölgesine dokunuşlar geçici olarak serbest bırakılıp yeniden gönderilir; böylece navigasyon düğmeleri ve erişilebilirlik düğmesi kullanılabilir kalır.
- Erişilebilirlik düğmesine tekrar basıldığında büyütme kapanır.

## Log

```sh
su -c 'logcat -d | grep FSMagService'
```

Beklenen bazı kayıtlar:

- `ARMED: waiting for first touchscreen tap`
- `Fullscreen magnification ON`
- `One-finger panning capture ENABLED`
- `Relaying navigation-area tap`
