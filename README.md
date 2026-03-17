# Offline Transkript APK

Bu proje OpenAI kullanmadan, cihaz üzerinde offline transkript üretmek için Vosk kullanır.

## Önemli
Bu pakette Vosk Türkçe model dosyası **yer almıyor**. Boyut nedeniyle ayrıca eklenmelidir.

## Türkçe model
Uygun bir Türkçe Vosk modeli indirip klasör adını `model-tr` yapın.

Örnek kaynak:
- https://alphacephei.com/vosk/models

## Android Studio ile kullanım
1. Projeyi Android Studio'da açın.
2. İndirdiğiniz model klasörünü `app/src/main/assets/model-tr` yoluna koyun.
3. Uygulamayı derleyin.

## GitHub Actions ile APK
Workflow APK üretir, ancak model klasörü eklenmeden uygulama açıldığında model bulunamadı uyarısı verir.

## Desteklenen sesler
Kod tarafı Android `MediaExtractor` / `MediaCodec` ile yaygın ses formatlarını çözmeyi dener.
`m4a`, `mp3`, `aac` gibi dosyalar çoğu cihazda çalışır.
