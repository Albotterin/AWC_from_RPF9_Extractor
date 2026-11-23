# AWC Radio – Fortschritt der Sender

[English version](RADIO-PROGRESS.md)

Wie viele der Songs, die die öffentliche Credits-Seite für jeden Sender aufführt, als Dateien in der `audio.rpf` des Spiels gefunden wurden.

**Stand: 2026-11-24** · 766 / 837 (91.5 %)

🟢 ab 90 % · 🟡 70–89 % · 🟠 30–69 % · 🔴 unter 30 %

## Musiksender und DJ-Sets

![AWC Radio – Fortschritt der Sender](radio-progress.svg)

<details><summary>Tabelle</summary>

| Sender | Gefunden / gelistet | Anteil |
|---|---:|---:|
| 🟢 Afrobank FM | 31 / 31 | 100.0 % |
| 🟢 Back Country Radio | 24 / 24 | 100.0 % |
| 🟢 Circoloco Records Radio | 27 / 27 | 100.0 % |
| 🟢 Radio La Ola | 25 / 25 | 100.0 % |
| 🟢 The Chamber 106.6 | 22 / 22 | 100.0 % |
| 🟢 V-Rock | 70 / 70 | 100.0 % |
| 🟢 Vice City FM | 70 / 71 | 98.6 % |
| 🟢 Flash FM | 78 / 80 | 97.5 % |
| 🟢 Cocoteo FM | 29 / 30 | 96.7 % |
| 🟢 Dirty South Classics | 37 / 39 | 94.9 % |
| 🟢 Stockyard FM | 35 / 37 | 94.6 % |
| 🟢 Kaleidoscope FM | 17 / 18 | 94.4 % |
| 🟢 Honey FM | 49 / 52 | 94.2 % |
| 🟢 Radio On-U | 15 / 16 | 93.8 % |
| 🟢 Emotion 98.3 | 35 / 38 | 92.1 % |
| 🟢 Vice City Rap Radio | 23 / 25 | 92.0 % |
| 🟢 Gloriana Blues Radio | 21 / 23 | 91.3 % |
| 🟢 The Sniper Of La Perle | 20 / 22 | 90.9 % |
| 🟡 Leonida Jazz Radio | 32 / 36 | 88.9 % |
| 🟡 Symphony FM | 15 / 17 | 88.2 % |
| 🟡 Worldwide FM | 36 / 41 | 87.8 % |
| 🟡 Bodega The DJ | 36 / 45 | 80.0 % |
| 🟡 DJ Stevie J | 19 / 24 | 79.2 % |
| 🔴 The Vault Radio | 0 / 24 | 0.0 % |
| **Gesamt** | **766 / 837** | **91.5 %** |

</details>

## Talk-Radio und Shows

Die Credits führen hier Sprecher und Sendungen, keine Songs; die Zahlen sagen daher wenig.

| Sender | Gefunden / gelistet | Anteil |
|---|---:|---:|
| 🔴 Honey FM - Liquid City | 0 / 8 | 0.0 % |
| 🔴 The Wipe Talk Radio | 0 / 40 | 0.0 % |
| 🔴 WKTT Talk Radio | 0 / 26 | 0.0 % |

## Die Lücken

- **The Vault Radio** (0 %) ist ein einziger langer DJ-Mix ohne Einzeldateien, deshalb gibt es keinen seiner 24 gelisteten Songs als Datei. Der Mix selbst läuft als Sender. **Symphony FM** steht bei 15 von 17: die Namen stehen in der Config mit einem Längenbyte davor, das jetzt abgeschnitten wird; das Stück von Beethoven hat keine eigene Datei.
- Titel werden zuerst über den Hash gefunden, dann über die Ähnlichkeit des internen Spiel-Namens ("IM_IN_LUV" für "I'm N Luv (Wit a Stripper)"); so kommen 64 Songs dazu.
- Lange DJ-Mixe laufen als Sender: The Vault Radio, und zusätzliche „(Mix)“-Sender für Radio On-U, Worldwide FM und The Sniper Of La Perle. Der Sender eines Mixes ergibt sich aus den Song-Markern darin oder (The Vault) aus der Ordnerreihenfolge im Archiv. Während ein Mix läuft, wird der Titel des aktuellen Abschnitts angezeigt (20 von 22, 27 von 41 und 10 von 24 Abschnitten haben einen Namen). Die übrigen langen Mixe (Club- und DJ-Musik) laufen zusammen als Sender „Clubmixes“, bis sie einem Sender zugeordnet werden können.
- Den übrigen Sendern fehlen je wenige Songs (andere Schreibweise oder keine eigene Datei).

## So wird gemessen

- Die Credits-Seite nennt die Songs jedes Senders. Jeder Titel wird gehasht (`joaat`, so benennt das Spiel seine Streams) und mit den Stream-IDs aus dem Archiv-Scan verglichen.
- Ein Song gilt als *gefunden*, wenn genau ein Stream diese ID hat, oder wenn der interne Spiel-Name eines sonst nicht zugeordneten Streams eindeutig am besten zu ihm passt. Songs, die nicht als eigene Datei vorliegen, gelten als fehlend.
- Gezeigt werden nur Zahlen zu öffentlichen Credits-Einträgen; Spieldaten und Songdateien sind nicht Teil dieses Repositorys.
