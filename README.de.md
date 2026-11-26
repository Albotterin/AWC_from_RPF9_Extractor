# AWC from RPF9 Extractor & AWC Radio

**Musik aus RAGE-Engine-`audio.rpf`-Archiven: das Audio extrahieren oder die Radiosender des Spiels hören – am PC und auf Android.**

🇬🇧 [English version](README.md) · ⬇️ [**Download (Releases)**](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/latest) · 📱 [Android-App](#-die-awc-radio-app-für-android) · 📊 [Fortschritt des Radios](radio/docs/RADIO-PROGRESS.de.md)

| 🎛 AWC Audio Extractor | 📻 AWC Radio |
|:---:|:---:|
| ![AWC Audio Extractor](extractor/docs/screenshots/04-german.png) | ![AWC Radio](radio/docs/screenshots/06-radio.png) |

| 📱 AWC Radio auf Android – Sender | 📱 AWC Radio auf Android – Player |
|:---:|:---:|
| <img src="radio/docs/screenshots/09-app-radio-de.png" width="300" alt="Sender"> | <img src="radio/docs/screenshots/10-app-player-de.png" width="300" alt="Player"> |

> ⚠️ **Bitte lies den [Disclaimer](DISCLAIMER.de.md).** Es sind keine Spieldaten enthalten: Du brauchst dein eigenes
> Exemplar des Spiels. Nichts wird entschlüsselt, kein Kopierschutz umgangen und nichts am Spiel verändert.

## Zwei Projekte in einem Repository

| | 🎛 AWC Audio Extractor | 📻 AWC Radio |
|---|---|---|
| **Wofür** | Jeden Musik-Stream finden, abspielen und extrahieren | Die Sender hören wie ein echtes Radio |
| **Ordner** | [`extractor/`](extractor/README.de.md) | [`radio/`](radio/README.de.md) |
| **Programme** | `AWC-Tool.exe` | `AWC-Radio.exe` + `AWC-Radio.apk` (Android) |
| **Anleitung** | [Extractor-Anleitung](extractor/README.de.md) | [Radio-Anleitung](radio/README.de.md) |
| **Technische Notizen** (englisch) | [AWC-/RPF9-Format](extractor/docs/FORMAT.md) | [Senderdefinitionen](radio/docs/STATIONS.md) |

Der Extractor ist die Basis: Er liest das Archiv und die AWC-Dateien. AWC Radio baut auf seinen Modulen auf – beim
Start aus dem Quellcode müssen deshalb beide Ordner nebeneinander liegen.

## Schnellstart

1. `AWC-Tool-…-win64.zip` aus dem [neuesten Release](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/latest) laden und entpacken. Darin liegen beide Windows-Programme.
2. `ffmpeg.exe` und `ffplay.exe` ([ffmpeg.org](https://ffmpeg.org/download.html)) daneben oder in den `PATH` legen.
3. **`AWC-Tool.exe`** zum Extrahieren oder **`AWC-Radio.exe`** zum Hören starten und die `audio.rpf` deines eigenen Spiels
   wählen. Das Radio fragt zusätzlich nach der Textur-Datei `data\ttdb\ui\ttdb_ui.ttpf` (Sender-Logos). Liegt die `data.rpf` des
   Spiels im selben Ordner, bekommen auch die Podcast-Folgen und die Teile der Mixe ihre Titel.
4. **Aufs Handy:** die Android-App installieren, in AWC Radio mit **Android-Packs…** die Sender- und Podcast-Packs schreiben, sie in
   einen Ordner auf dem Handy kopieren und diesen Ordner in der App wählen.

Aus dem Quellcode (Python 3 mit tkinter; Pillow für die Logos):

```bash
git clone https://github.com/Albotterin/AWC_from_RPF9_Extractor.git
cd AWC_from_RPF9_Extractor
python extractor/awc_tool.py     # der Extractor
python radio/awc_radio.py        # das Radio
```

## 🎛 AWC Audio Extractor

* Durchsucht `.rpf`-Archive jeder Größe nach Audio-Containern – ohne Inhaltsverzeichnis.
* Schreibt die originalen Opus-Pakete 1:1 in `.opus`-Dateien (keine Neukodierung); optional FLAC, WAV oder MP3.
* Spielt jeden Stream direkt aus dem Archiv.
* Zeigt das Archiv **nach Radiosendern**: Songs, DJ-Intros und -Outros, Werbespots, Jingles und Talk, mit Senderlogo – dazu die **Podcasts** und der **Score** in eigenen Gruppen.
* Liest den **Score** des Spiels (die interaktive Musik): Stücke aus seinen Instrumentenspuren gemischt, gruppiert in offene
  Welt, Missionen, Stützpunkte, Aktivitäten und Musik an Orten – zum Hören, Speichern und fürs Handy (braucht FFmpeg).
* Benennt Streams aus der Spiel-Konfiguration, von der öffentlichen Credits-Seite oder aus eigenen Listen; Sender-Logos optional.
* Oberfläche auf Deutsch und Englisch.

➡️ [**Extractor-Anleitung**](extractor/README.de.md)

## 📻 AWC Radio

* **34 Sender**, darunter die Talk-Sender **WKTT** und **The Wipe** und Orte aus dem Spiel (**Jack of Hearts**, **VYB**,
  **The Vault**, **Thrillbilly Mud Club**), samt Playlists aus der Audio-Konfiguration des Spiels gelesen.
* Ein Live-Programm nach der Uhr: Jeder Sender läuft weiter, alle mit derselben Uhrzeit und Zeitzone hören dasselbe.
* **Die Programmregeln des Spiels**, aus deiner Audio-Konfiguration gelesen: wie oft Werbung, Nachrichten, DJ-Beiträge und
  Senderkennungen kommen, was sich nachts ändert, wie die Talk-Sender ihre Sendungen aneinanderreihen.
* **Übergänge wie im Spiel**: Jingles, Werbung, Songs und Talk überlappen und blenden so, wie es die Misch-Regeln des
  Spiels sagen, und unter dem DJ läuft die Hintergrundmusik des Senders.
* Lange DJ-Mixe zeigen den Titel des aktuellen Abschnitts.
* **Der DJ**, wenn man ihn einschaltet: Ansagen über den Anfang und das Ende der Songs, in den Fenstern, die die Songdateien
  dafür markieren (von Haus aus aus).
* **Du bestimmst, was zwischen den Songs läuft**: Werbung, Nachrichten, DJ-Beiträge, Talk und Sendungen (mit der
  Nachtshow **Liquid City** auf Honey FM) und Jingles, jeweils mit eigenem Schalter. Von Haus aus sind DJ-Beiträge und
  Jingles an.
* **Podcasts**: die 5 Podcasts des Spiels mit ihren 20 Folgen und deren Titeln, abspielbar in der Android-App und am PC.
* **On-Demand** (App): Songs und Interpreten suchen wie in einer Musik-App, mit einer Playlist für jeden Sender und
  einer für das Soundtrack-Album des Spiels.
* Eine [**Android-App**](#-die-awc-radio-app-für-android) spielt dasselbe Programm auf dem Handy und im Auto – und weckt dich damit.

➡️ [**Radio-Anleitung**](radio/README.de.md)

## 📱 Die AWC-Radio-App für Android

Das Radio für die Hosentasche, fürs Auto und für den Nachttisch. **`AWC-Radio.apk`** (Version 1.19, auf einem echten Android-Handy getestet) spielt dasselbe
Live-Programm wie der PC: gleiche Uhr, gleiche Reihenfolge, gleiche Übergänge – mit Sender-Packs derselben Version
spielen Handy und PC zur selben Zeit dasselbe.

| | |
|---|---|
| 🎧 **Hören** | Senderlogos als Raster, ein kleiner und ein großer Player in der Farbe des Senders; Wischen über das Logo wechselt den Sender. Spielt im Hintergrund weiter, mit Medien-Benachrichtigung, Sperrbildschirm-, Kopfhörer- und Bluetooth-Tasten |
| 🔎 **On-Demand** | Ein Reiter wie eine Musik-App: Songs und Interpreten suchen, alle Songs, die Interpreten, eine Playlist für jeden Sender und eine für das Soundtrack-Album mit seinem Cover; Zufall und Wiederholen |
| 🎙 **Podcasts** | Ein eigener Bereich neben dem Radio: Cover, Folgenliste, Player mit Positionsleiste, 15 Sekunden zurück und 30 vor; die Stelle in jeder Folge wird gemerkt, die nächste folgt von selbst |
| 🗣 **DJ über Songs** | Ansagen über den Songs, in den Einstellungen einzuschalten; die Musik tritt zurück, solange der DJ spricht |
| 🎼 **Score** | Ein eigener Reiter: offene Welt, Missionen, Stützpunkte, Aktivitäten und Musik an Orten (Fahrstühle, Hotellobbys, Minigolf), jeweils mit Untergruppen und Stücken |
| ⏰ **Radiowecker** | Mehrere Wecker mit Wochentagen, Sender, Lautstärke, sanftem Start und Schlummern; ein Sonnenaufgang-Bildschirm über dem Sperrbildschirm. Ist das Radio nicht zu hören, klingelt der Weckton des Handys; Wecker bleiben nach einem Neustart erhalten |
| 🚗 **Android Auto** | Die Sender als Raster mit ihren Logos, Song und Sender auf dem Player-Bildschirm, Sprachbefehle wie *„Spiele Radio On-U auf AWC Radio“* |
| 😴 **Einschlaftimer** | Stoppt das Radio nach der gewählten Zeit |
| 🧩 **Widgets** | Ein Player für den Startbildschirm (Logo, Titel, Zurück, Pause, Weiter; in der Größe anpassbar) und je Sender eine Kachel, die ihn mit einem Tipp spielt |
| 🔄 **Updates** | Eine neuere Version wird mit ihrem Changelog vorgeschlagen, beim Start (höchstens einmal am Tag) oder per Knopf in den Einstellungen; der Download öffnet sich im Browser |
| 📢 **Zwischen den Songs** | Je ein Schalter für Werbung, Nachrichten, DJ-Beiträge, Talk und Sendungen sowie Jingles, im Player und auf der Einstellungsseite; ein Sender bietet an, was er hat |
| ⚙️ **Einstellungen** | Eine Einstellungsseite: Überblenden ein/aus und 1–12 s (nur für Clubs und Mixe; die anderen Sender werden wie im Spiel gemischt), Inhalte zwischen den Songs, DJ über Songs, Pack-Ordner, Updates, Akku-Einstellungen, Info und Protokoll |
| 📦 **Packs** | Eine Datei je Sender (`.radiopak`), je Podcast (`.podpak`), eine mit den Nachrichten (`.newspak`), eine für das Soundtrack-Album (`.playlistpak`) und je Score-Gruppe (`.scorepak`), alle in einem Ordner auf dem Handy: einzeln hinzufügen oder aktualisieren, ohne alles neu zu kopieren |
| 🔒 **Offline** | Alles, was sie spielt, kommt aus den Packs auf dem Handy. Das Internet braucht sie nur für eines: bei github.com nach der Liste der Releases fragen (abschaltbar) |
| 🌍 **Sprachen** | Deutsch und Englisch |
| 📋 **Voraussetzung** | Android 7.0 oder neuer |

**Einrichten**

1. `AWC-Radio.apk` installieren (aus dem [neuesten Release](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/latest) oder selbst gebaut aus [`radio/android/`](radio/README.de.md#awc-radio-für-android)). Benachrichtigungen erlauben: Der Wecker braucht sie.
2. Am PC in AWC Radio auf **Android-Packs…** klicken, einen Ordner wählen und die Packs schreiben (Originalqualität, etwa 5,4 GB für alle 34 Sender samt Nachrichten, 0,2 GB für die Podcasts und 0,15 GB für das Soundtrack-Album). Später werden nur neue oder geänderte Packs geschrieben. Die Score-Packs (etwa 1,8 GB) werden dort angeboten, sobald der Score im AWC-Tool gelesen wurde (Knopf **Score …**), oder aus dessen Score-Fenster geschrieben.
3. Die `.radiopak`-, `.newspak`-, `.playlistpak`-, `.podpak`- und `.scorepak`-Dateien in denselben Ordner auf dem Handy kopieren, die App starten und diesen Ordner einmal wählen.
4. Für Android Auto: in den Einstellungen von Android Auto etwa zehnmal auf *Version* tippen, im Drei-Punkte-Menü die Entwicklereinstellungen öffnen und *Unbekannte Quellen* aktivieren.

**Gut zu wissen**

* Die APK enthält **keine Spieldaten** und ist für alle dieselbe Datei. Sender- und Podcast-Packs enthalten Audio und Bilder aus deinem eigenen Spiel: nur für den eigenen Gebrauch, bitte nicht weitergeben.
* Das einzelne `.dat`-Pack früherer Versionen wird nicht mehr gelesen.
* Teste einen Wecker auf deinem Handy, bevor du dich darauf verlässt: Die Akkusparfunktionen mancher Hersteller beenden Apps im Hintergrund.
* Eine neuere APK lässt sich über die alte installieren; ab Version 1.19 schlägt die App sie selbst vor.

## Fortschritt

Stand **2026-11-26** (v2.13.0), gemessen an einer 28,5 GB großen `audio.rpf`.

| Bereich | Stand | |
|---|---|---|
| Vom Scan gefundene Musik-Container | 3.008 mit 3.116 Streams (rund 101 Stunden), alle Opus und extrahierbar | ✅ |
| Songs, die die Credits für die Musiksender nennen | **794 von 809** gefunden (98,1 %) | 🟢 |
| Talk-Radio | alle 5 in den Credits genannten Sendungen, Teil für Teil in der Reihenfolge des Spiels | ✅ |
| Programm | die Regeln des Spiels für 20 Sender (18 Musik, 2 Talk) | ✅ |
| Mischung | die Übergänge des Spiels auf 18 Musiksendern, seine Hintergrundmusik unter dem DJ auf 16 | ✅ |
| Soundtrack-Album | **54 von 57** Titeln der Deluxe Edition gefunden, als Playlist in der App | 🟢 |
| On-Demand (App) | jeder benannte Song: eine Playlist je Sender, Interpreten, Suche | ✅ |
| Nachrichten und Nachtshow | 130 Meldungen auf 18 Sendern; abends Liquid City auf Honey FM | ✅ |
| Sender in AWC Radio | 34 (darunter 3 „(Mix)“-Sender, 4 Orte aus dem Spiel und „Clubmixes“) | ✅ |
| Orte aus dem Spiel | Jack of Hearts (100 Min. DJ-Set), VYB (45 Min.), The Vault (Club, 84 Min.), Thrillbilly Mud Club (20 Songs) | ✅ |
| Werbung | 239 Spots, wahlweise im Programm von 18 Musiksendern; die Talk-Sender haben eigene | ✅ |
| DJ-Ansagen über den Songs | 714 Songs mit Intro, 687 mit Outro, 2.221 Ansagen auf 18 Sendern | ✅ |
| Extractor nach Sendern | 28 Sender und 5 Podcasts mit Logo, 3.417 DJ-Zeilen | ✅ |
| Podcasts | alle 5, die die Credits nennen, mit 20 Folgen samt Titeln (3 Std. 36 Min.), in der App und am PC | ✅ |
| Score | 2.543 Stücke (rund 33 Std.) und 113 Aufnahmen von Orten (4,8 Std.), im Extractor in einem eigenen Ordner; 1.078 ab 30 Sekunden (31 Std.) kommen in die App-Packs | ✅ |
| Titel aus `data.rpf` | 839 der 865 Titel-Nummern, die die Aufnahmen tragen, haben in der Tabelle des Spiels Titel und Interpret | ✅ |
| Android-App | Version 1.19: Radio, On-Demand, Podcasts, Score, DJ, Inhalts-Schalter, Radiowecker, Widgets, Update-Prüfung, Android Auto; läuft auf einem echten Android-Handy | ✅ |
| The Vault Radio | läuft als ein langer Mix; 23 seiner 24 gelisteten Songs sind als Teile darin benannt | 🎚 |

![AWC Radio – Fortschritt der Sender](radio/docs/radio-progress.svg)

Details zu jedem Sender: [Fortschritt des Radios](radio/docs/RADIO-PROGRESS.de.md).

### Noch offen

- [ ] 3 Titel des Soundtrack-Albums stecken, soweit durchsucht, in keiner Aufnahme des Archivs: *Starting From Zero*
      (Justice), *Burn This Shit Down* (Travis Scott) und *Touch & Go* (Anitta). *Windsor Drop* und *Banshee* sind im
      Spiel kürzer als auf dem Album. *Starting From Zero* steht in den Untertiteltexten des Spiels; der Ton der Videos in
      `movies.rpf` ist nicht durchsucht, weil FFmpeg ihre neuere Bink-Variante nicht dekodieren kann
- [ ] 15 gelistete Songs ohne eigene Datei bei ihrem Sender (die Club-DJ-Sets und ein Jingle von Worldwide FM):
      6 laufen auf Vice City Rap Radio und Dirty South Classics; 5 wurden am Klang in den Club-Sets gefunden
      (*O Let's Do It*, *Wipe Me Down*, *My Neck, My Back* und *B.M.F.* bei Jack of Hearts, *Love Me* bei VYB), ohne
      Marken, die ihren Beginn nennen; 4 fehlen weiterhin (*Faneto*, *Dreams and Nightmares*, *Can't Fuck wit Me*, der
      Jingle *Yu Done Know*), ebenso ein Titel von The Vault Radio (*FTH*)
- [ ] Die 8 eigens geschriebenen Songs von „Honey FM – Liquid City“: Die Sendung selbst läuft, und in einigen ihrer
      Aufnahmen lässt sich Musik messen (dieselben ersten Sekunden in allen neun Eröffnungen, eine Passage in zwei
      Eröffnungen, gleichmäßige Takte in manchen Talk-Teilen). Welche Songs das sind, ist nicht geprüft: Keine Datei trägt
      ihre Namen, und Hörproben waren nicht zu finden
- [x] Programm und Mischung nach den Spieldaten sind per Ohr mit dem Spiel verglichen; die Tiefpass-Verläufe
      mancher Misch-Regeln und Regeln, die vom Spielstand abhängen, bleiben weggelassen
- [x] Die Überlappungen der Beiträge (die Misch-Regeln des Spiels) und die Musik unter dem DJ sind übernommen
- [x] Der bisher namenlose Titel von Radio On-U ist *Ras Menilik Congo (Harp)* von Augustus Pablo
- [x] Die Podcast-Folgen heißen wie im Spiel
- [ ] Ein 61 Minuten langer Club-Mix bleibt in „Clubmixes“: Keine Titelliste und kein Ort der Konfiguration spielt ihn,
      und er hat keine Titelmarken
- [x] AWC Radio am PC spielt die Podcasts
- [x] Das Radio spricht die DJ-Intros und -Outros über die Songs (PC und App)
- [x] Die Android-App läuft auf einem echten Handy
- [x] Score und DJ der App sind auf einem echten Handy getestet
- [x] App 1.19 läuft auf einem echten Handy
- [ ] Extractor: nur Opus; das RPF9-Inhaltsverzeichnis wird nicht gelesen, es gibt also keine Dateinamen

### Meilensteine

| Version | Datum | Neu dazugekommen |
|---|---|---|
| [v1.0.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v1.0.0) | 2026-11-22 | 🎛 Archiv-Scan, Player, verlustfreie Extraktion |
| [v1.1.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v1.1.0) | 2026-11-22 | 🎛 Gruppen nach Lage im Archiv |
| [v1.7.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v1.7.0) | 2026-11-23 | 🎛 Songnamen und Sender-Logos · 📻 erstes AWC Radio |
| [v1.8.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v1.8.0) | 2026-11-23 | 📻 Programm nach der UTC-Uhr |
| [v1.9.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v1.9.0) | 2026-11-23 | 📻 Überblendungen |
| [v2.0.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.0.0) | 2026-11-23 | 📻 Android-App und Sender-Packs |
| [v2.1.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.1.0) | 2026-11-24 | 📻 Mehr Sender und Songs, Mix-Sender |
| [v2.2.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.2.0) | 2026-11-24 | 📻 The Vault Radio, Clubmixes, Titel innerhalb der Mixe |
| [v2.3.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.3.0) | 2026-11-24 | 📻 Android Auto, neues App-Design |
| [v2.4.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.4.0) | 2026-11-25 | 📻 Talk-Radio und vollständige Sender-Playlists |
| [v2.4.1](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.4.1) | 2026-11-25 | 🎛 📻 Eigener Leser für die Sender-Logos, kein Download nötig |
| [v2.5.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.5.0) | 2026-11-25 | 📻 Ein Pack je Sender, App 1.5 mit Radiowecker und neuem Design, Sender für Orte aus dem Spiel |
| [v2.6.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.6.0) | 2026-11-25 | 📻 Werbung wahlweise (App 1.6) · 🎛 Ansicht nach Radiosendern mit DJ-Intros, Outros und Werbung |
| [v2.7.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.7.0) | 2026-11-25 | 🎙 Podcasts in App (1.8, eigener Bereich, `.podpak`) und Extractor |
| [v2.8.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.8.0) | 2026-11-25 | 🎼 Der Score des Spiels (AWC-Tool und App 1.11, `.scorepak`) · 📻 DJ-Ansagen, Podcasts am PC, Überblend-Einstellung · 🎛 40 weitere Musik-Container |
| [v2.9.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.9.0) | 2026-11-26 | 🏷 Titel aus der Tabelle des Spiels: alle 20 Podcast-Folgen, die Teile der Mixe, 5 korrigierte und 2 ergänzte Songnamen · 🎼 der Score als Gruppen im AWC-Tool · App 1.12 |
| [v2.10.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.10.0) | 2026-11-26 | 📻 Die Programmregeln des Spiels, Nachrichten, Talk, die Nachtshow Liquid City, Talk-Radio Teil für Teil · 🎼 Musik an Orten · App 1.14 |
| [v2.11.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.11.0) | 2026-11-26 | 📻 Übergänge wie im Spiel mit Musik unter dem DJ · 🔎 On-Demand mit Suche, Playlisten und Soundtrack-Album · App 1.16 |
| [v2.12.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.12.0) | 2026-11-26 | 📻 Eigene Schalter für Werbung, Nachrichten, DJ-Beiträge, Talk und Jingles (PC und App) · App 1.17 |
| [v2.13.0](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/tag/v2.13.0) | 2026-11-26 | 📱 Widgets für den Startbildschirm und eine Update-Prüfung mit Changelog · App 1.19 |

## Aufbau des Repositorys

```
extractor/     AWC Audio Extractor: Programm, gemeinsame Module, Format-Notizen, Screenshots
radio/         AWC Radio: Programm, Sender-Leser, Fortschritt, Screenshots
  android/     die Android-App (Quelltext, Build-Skript, Tests)
DISCLAIMER.md  was die Programme tun und nicht tun, und deine Pflichten (auf Deutsch: DISCLAIMER.de.md)
LICENSE        MIT
README.md      diese Übersicht auf Englisch (README.de.md auf Deutsch)
```

## Rechtlicher Hinweis

**Der vollständige Text steht im [Disclaimer](DISCLAIMER.de.md).** Kurz gesagt:

* Dieses Repository enthält **nur Quellcode und Dokumentation**. Es enthält **keine Spieldateien, keine Archive und
  keine extrahierten Audiodaten** – und wird das auch nie.
* Du brauchst deine eigenen Dateien aus einem Spiel, das du rechtmäßig besitzt. Für extrahiertes Audio bist du selbst
  verantwortlich – es unterliegt dem Urheberrecht der Rechteinhaber. Private, nicht-kommerzielle Analyse und
  Archivierung ist eine Sache, die Weitergabe in der Regel nicht erlaubt. Ein Pack enthält Spiel-Audio: bitte
  nicht weitergeben.
* Die Screenshots zeigen Senderlogos und Songtitel aus dem Spiel nur, um zu illustrieren, was die Programme aus deinen
  eigenen Dateien lesen.
* Das Projekt steht in **keiner Verbindung** zu Rockstar Games, Take-Two oder anderen Publishern und wird von
  diesen nicht unterstützt. Alle Marken gehören ihren jeweiligen Inhabern.

## Credits und Lizenz

Das AWC-Block-Layout wurde mit Hilfe von [**vgmstream**](https://github.com/vgmstream/vgmstream) erarbeitet; allgemeines
AWC/RPF-Wissen stammt aus der Community, v. a. von [**CodeWalker**](https://github.com/dexyfex/CodeWalker). Das Layout der
Audio-Konfiguration folgt den Notizen des Projekts **Leonida** (ItsNotRexed). Abspielen und
Konvertieren übernimmt [FFmpeg](https://ffmpeg.org/).

[MIT](LICENSE) – gilt nur für den Quellcode, nicht für Spieldaten.
