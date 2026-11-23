# AWC Radio – Fortschritt der Sender

[English version](RADIO-PROGRESS.md)

Wie viele der Songs, die die öffentliche Credits-Seite für jeden Sender aufführt, als Dateien in der `audio.rpf` des Spiels gefunden wurden.

**Stand: 2026-11-23** · 754 / 837 (90.1 %)

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
| 🟢 Cocoteo FM | 29 / 30 | 96.7 % |
| 🟢 Flash FM | 77 / 80 | 96.2 % |
| 🟢 Stockyard FM | 35 / 37 | 94.6 % |
| 🟢 Honey FM | 49 / 52 | 94.2 % |
| 🟢 Radio On-U | 15 / 16 | 93.8 % |
| 🟢 Dirty South Classics | 36 / 39 | 92.3 % |
| 🟢 Vice City Rap Radio | 23 / 25 | 92.0 % |
| 🟢 Gloriana Blues Radio | 21 / 23 | 91.3 % |
| 🟢 The Sniper Of La Perle | 20 / 22 | 90.9 % |
| 🟡 Emotion 98.3 | 34 / 38 | 89.5 % |
| 🟡 Kaleidoscope FM | 16 / 18 | 88.9 % |
| 🟡 Leonida Jazz Radio | 32 / 36 | 88.9 % |
| 🟡 Worldwide FM | 34 / 41 | 82.9 % |
| 🟡 Bodega The DJ | 36 / 45 | 80.0 % |
| 🟡 DJ Stevie J | 19 / 24 | 79.2 % |
| 🟠 Symphony FM | 9 / 17 | 52.9 % |
| 🔴 The Vault Radio | 0 / 24 | 0.0 % |
| **Gesamt** | **754 / 837** | **90.1 %** |

</details>

## Talk-Radio und Shows

Die Credits führen hier Sprecher und Sendungen, keine Songs; die Zahlen sagen daher wenig.

| Sender | Gefunden / gelistet | Anteil |
|---|---:|---:|
| 🔴 Honey FM - Liquid City | 0 / 8 | 0.0 % |
| 🔴 The Wipe Talk Radio | 0 / 40 | 0.0 % |
| 🔴 WKTT Talk Radio | 0 / 26 | 0.0 % |

## Die Lücken

- **The Vault Radio** (0 %) ist ein einziger langer DJ-Mix ohne Einzeldateien, deshalb gibt es keinen seiner 24 gelisteten Songs als Datei. **Symphony FM** steht bei 9 von 17: den anderen Stücken fehlt in der Config ein Name, sie lassen sich der Liste nicht zuordnen.
- Titel werden zuerst über den Hash gefunden, dann über die Ähnlichkeit des internen Spiel-Namens ("IM_IN_LUV" für "I'm N Luv (Wit a Stripper)"); so kommen 52 Songs dazu.
- Drei lange Mixe laufen zusätzlich als eigene Sender (Radio On-U, Worldwide FM, The Sniper Of La Perle): Der Sender eines Mixes ist über die Song-Marker darin bekannt. Drei weitere lange Mixe haben keine solche Verbindung und werden noch nicht genutzt.
- Den übrigen Sendern fehlen je wenige Songs (andere Schreibweise oder keine eigene Datei).

## So wird gemessen

- Die Credits-Seite nennt die Songs jedes Senders. Jeder Titel wird gehasht (`joaat`, so benennt das Spiel seine Streams) und mit den Stream-IDs aus dem Archiv-Scan verglichen.
- Ein Song gilt als *gefunden*, wenn genau ein Stream diese ID hat, oder wenn der interne Spiel-Name eines sonst nicht zugeordneten Streams eindeutig am besten zu ihm passt. Songs, die nicht als eigene Datei vorliegen, gelten als fehlend.
- Gezeigt werden nur Zahlen zu öffentlichen Credits-Einträgen; Spieldaten und Songdateien sind nicht Teil dieses Repositorys.
