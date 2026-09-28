# Fullscreen Magnifier Accessibility Service

Android 13+ için minimal AccessibilityService.

- Navigasyon çubuğundaki Accessibility button callback'ini kullanır.
- Android'in kendi MagnificationController API'si ile `MAGNIFICATION_MODE_FULLSCREEN` açar.
- Tekrar basıldığında `reset(true)` ile büyütmeyi kapatır.
- Varsayılan büyütme: 3.0x.
- Root / LSPosed gerekmez.

## Kurulum sonrası
1. Ayarlar > Erişilebilirlik > Fullscreen Magnifier bölümünden hizmeti etkinleştirin.
2. Kısayol olarak "Erişilebilirlik düğmesi"ni seçin.
3. Navigasyon çubuğundaki erişilebilirlik düğmesine basın.

## Log kontrolü
`adb logcat | grep FSMagService`
veya rootlu cihazda:
`su -c 'logcat -d | grep FSMagService'`
