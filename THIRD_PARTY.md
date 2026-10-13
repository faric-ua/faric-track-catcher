# Сторонній код

| Бібліотека | Версія | Ліцензія | Для чого |
|---|---|---|---|
| jump3r (`de.sciss:jump3r`) — Java-порт LAME 3.98.4 | 1.0.5 | LGPL-2.1+ | кодування MP3 |
| AndroidX Core KTX | 1.15.0 | Apache-2.0 | вставки системних смуг, сповіщення |

jump3r підключається як окрема бібліотека з Maven Central без змін; клас `de.sciss.jump3r.lowlevel.LameEncoder` не використовується (у ньому `javax.sound`, якого немає в Android) — ініціалізацію повторює `encode/Mp3Encoder.kt`.
