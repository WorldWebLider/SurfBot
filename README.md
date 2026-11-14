# SurfBot — Subway Surfers uchun telefon boti

Faqat telefonda ishlaydi (Android 8+). Internet yoki AI xizmati kerak emas.

## Nima qiladi
- Ekranni ko'radi, odamdek swipe qiladi (tasodifiy kechikish, egri harakat, har safar boshqa nuqta)
- Yutqazsa: kalit bilan davom etadi (sozlamada) → natija oynasi → PLAY → yangi o'yin
- Mystery box'larni ochadi
- Qochib bo'lmaydigan holatda skeytbord yoqadi (ikki marta tegish)
- Reklama yoki noma'lum oyna chiqsa: 5 soniya kutadi, "Orqaga" bosadi; o'yindan chiqib ketsa, qayta ochadi
- Hech qachon reklama tugmasini ("Watch", "Continue + Ad") bosmaydi
- REC rejimi: siz o'ynaysiz, bot o'rganish uchun yozib oladi

## Boshqarish
- Ovoz pasaytirish tugmasini 2 marta tez bosish — yoqish/o'chirish (strimda ko'rinmaydi)
- Suzuvchi tugma: bosish — yoqish/o'chirish; uzoq bosish — AI ↔ REC

## Reklamani kamaytirish
Sozlamalar → Ulanishlar → Boshqa ulanish sozlamalari → Shaxsiy DNS → `dns.adguard-dns.com` → Saqlash.
Qaytarish: o'sha joyda "Avtomatik".

## O'rgatish (2-versiya)
1. Suzuvchi tugmani uzoq bosing → REC (qizil). Odatdagidek o'ynang, kamida 1–2 soat.
2. Ilovada "Yozuvlarni yuborish" → Google Drive.
3. `training/SurfBot_oqitish.ipynb` faylini Google Colab'da oching, kataklarni tartib bilan ishga tushiring.
4. Tayyor `model.tflite` faylini ilovada "Modelni yuklash" orqali yuklang.
Ko'proq o'ynab, hamma zip'larni birga qayta o'qitsangiz, bot yaxshilanib boradi.

## APK yig'ish
GitHub'ga yuklang → Actions → "APK yig'ish" → tayyor bo'lgach Artifacts → SurfBot-apk.
