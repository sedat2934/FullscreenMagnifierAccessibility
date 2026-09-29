# Fullscreen Magnifier Accessibility Service v2

Eski tip tam ekran büyütme davranışını taklit eder:

1. Erişilebilirlik düğmesine basın.
2. Servis ilk ekran dokunuşunu en fazla 8 saniye bekler.
3. Dokunduğunuz noktayı merkez alarak Android'in kendi tam ekran büyütmesini 3x açar.
4. Büyütme açıkken erişilebilirlik düğmesine tekrar basarsanız büyütme kapanır.

İlk dokunuş yalnızca büyütmeyi başlatmak için tüketilir. Sonraki dokunmalar normal çalışır.

Log kontrolü:

```sh
su -c 'logcat -d | grep FSMagService'
```
