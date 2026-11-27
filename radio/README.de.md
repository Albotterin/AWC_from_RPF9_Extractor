# AWC Radio

**Die Radiosender des Spiels als Live-Webradio – am PC und auf Android.**

[← Projektübersicht](../README.de.md) · 🇬🇧 [English version](README.md) · 📊 [Fortschritt der Sender](docs/RADIO-PROGRESS.de.md) · 📄 [Sender-Notizen (englisch)](docs/STATIONS.md) · ⬇️ [**Download (Releases)**](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/latest) · 🎛 [Audio Extractor](../extractor/README.de.md)

![AWC Radio](docs/screenshots/06-radio.png)

---

## Inhalt

1. [Start](#start)
2. [Woher die Sender kommen](#woher-die-sender-kommen)
3. [Hören](#hören)
4. [Podcasts](#podcasts)
5. [On-Demand](#on-demand)
6. [Sender und Fortschritt](#sender-und-fortschritt)
7. [AWC Radio für Android](#awc-radio-für-android)
8. [Selbst bauen](#selbst-bauen)
9. [Projektaufbau](#projektaufbau)
10. [Rechtlicher Hinweis](#rechtlicher-hinweis)
11. [Credits und Lizenz](#credits-und-lizenz)

## Start

`awc_radio.py` (oder `Start AWC-Radio.bat` / `AWC-Radio.exe`) ist ein kleiner Webradio-Player auf Basis des
[Extractor](../extractor/README.de.md)-Codes. Beim ersten Start wählst du die `audio.rpf` des Spiels und die Textur-Datei (`data\ttdb\ui\ttdb_ui.ttpf`);
die `data.rpf` im Ordner der `audio.rpf` liefert die Titel.
Die App liest das Archiv einmal ein (derselbe Scan-Cache wie beim Extractor; beim ersten Mal mehrere Minuten) und
zeigt dann jeden Sender mit seinem Logo.

* **Windows-Programm:** das [Release](https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/latest) entpacken, `ffplay.exe` neben `AWC-Radio.exe` (oder in den `PATH`) legen und starten.
* **Aus dem Quellcode:** Python 3 mit tkinter, Pillow für die Logos (`pip install pillow`). Die Ordner `radio/` und
  `extractor/` nebeneinander lassen, dann `python radio/awc_radio.py` starten oder `Start AWC-Radio.bat` doppelklicken.

![AWC Radio Einrichtung](docs/screenshots/07-radio-setup.png)

## Woher die Sender kommen

Die Audio-Konfiguration des Spiels (der zlib-Teil am Anfang der `audio.rpf`) beschreibt
jeden Radiosender mit seiner kompletten Playlist samt Titeln und Interpreten. Die Musiksender und die beiden
**Talk-Radio**-Sender (**WKTT** und **The Wipe**) werden direkt daraus gelesen. Die Songliste der öffentlichen
Credits-Seite liefert die Schreibweise der Namen sowie die DJ-Sets von Bodega The DJ und DJ Stevie J und die Mixe, die
über ihre Namen zugeordnet werden.

Die Konfiguration nennt auch, was einige **Orte im Spiel** spielen: **Jack of Hearts** (ein 100-Minuten-Set), **VYB**
(45 Minuten in vier Teilen) und **The Vault (Club)** bekommen die DJ-Sets dieser Clubs, der **Thrillbilly Mud Club** seine
Playlist mit 20 Songs. Siehe [docs/STATIONS.md](docs/STATIONS.md) (englisch).

## Hören

Ein Klick auf einen Sender schaltet ihn ein. Das Programm wird aus der **Uhrzeit** berechnet (jeden UTC-Tag eine neue
Reihenfolge), jeder Sender „läuft“ also im Hintergrund weiter: Wer zu einem anderen Sender und zurück wechselt, landet
dort, wo der erste Sender inzwischen ist. Alle mit denselben Spieldateien, derselben Uhrzeit und Zeitzone hören dasselbe zur selben Sekunde (auf etwa eine Sekunde genau; was über Mitternacht UTC laufen
würde, wird abgeschnitten). Es gibt keinen Überspringen-Knopf – wie bei einem echten Radio; nach einer Pause geht es live
weiter. Spätere Starts gehen sofort. Braucht wie der Extractor `ffplay`.

**Das Programm des Spiels.** Die Audio-Konfiguration nennt nicht nur die Aufnahmen eines Senders, sondern auch, wie er
zusammengesetzt wird: was worauf folgen darf und wie wahrscheinlich – abhängig davon, wie lange eine Art von Beitrag nicht
zu hören war, wie viele davon nacheinander liefen und welche Uhrzeit ist. AWC Radio liest diese Regeln aus deiner eigenen
Datei und folgt ihnen ([wie sie gespeichert sind](docs/STATIONS.md#programme-rules), englisch). So unterscheiden sich die
Sender wie im Spiel:

* **Werbeblöcke** mit drei Spots (Flash FM vier, Symphony FM einer), der nächste frühestens 8 Minuten nach dem letzten,
  danach eine Senderkennung – bei Flash FM meldet sich auch der DJ zurück.
* **Nachrichten** höchstens einmal pro Stunde, aus 130 Meldungen, die sich 18 Musiksender teilen.
* **DJ-Beiträge** und **Senderkennungen** zwischen den Songs, jeweils erst einige Minuten nach dem letzten.
* **Honey FM** nach Einbruch der Nacht: ab 20 Uhr die Nachtshow **Liquid City** mit eigenen Songs, Anmoderation, Talk und
  Abmoderation; am Morgen eröffnet die Tagessendung.
* **Leonida Jazz Radio** übergibt für ein Set an einen zweiten DJ, **Vice City Rap Radio** hat morgens (7–11 Uhr)
  Wortbeiträge, **Circoloco** und **Emotion** ändern sich nachts.
* **Talk-Radio** (WKTT, The Wipe), wie das Spiel es aneinanderreiht: die Teile der Sendungen in ihrer Reihenfolge,
  dazwischen Senderkennungen und Werbung.

Clubs, Orte und lange Mixe haben keine solchen Regeln: Ihre Songs laufen gemischt, wo es Jingles gibt, mit einem nach
jeweils 2–4 Songs.

Wie die Regeln gelesen werden, ist eine Auslegung der Daten, nicht der Code des Spiels: Es zählt die Uhrzeit **deiner Uhr**
in halben Stunden (die Spieluhr läuft viel schneller als eine echte); Schalter, die vom Spielstand abhängen, gelten als
nicht gesetzt; ein Gewicht „nach Anzahl in Folge“ wird als eines für den 1., 2., 3. Beitrag gelesen. In verschiedenen
Zeitzonen weicht das Programm rund um Tag und Nacht voneinander ab.

**Was zwischen den Songs läuft.** Fünf Schalter, am PC unter **Inhalte** oben im Fenster, in der App im Player und auf
der Einstellungsseite:

* **Werbung** – die Werbeblöcke.
* **Nachrichten** – die Meldungen, bei den Sendern, die Nachrichten senden.
* **DJ-Beiträge** – die Moderation zwischen den Songs.
* **Talk und Sendungen** – Wortbeiträge, Eröffnungen und Schlüsse von Sendungen, die gesprochenen Teile der Nachtshow.
* **Jingles** – Jingles und Senderkennungen.

Von Haus aus sind DJ-Beiträge und Jingles an. Das Programm wird nach denselben Regeln ausgewählt, Abgeschaltetes wird
übersprungen. Songs laufen immer, ebenso die Sendungen eines Talk-Senders. Dass der DJ aus der Werbung oder den
Nachrichten zurückkommt, hört man nur, wenn dieser Teil an ist. PC und Handy spielen nur mit denselben Schaltern dasselbe.

**Übergänge wie im Spiel.** Die Audio-Konfiguration sagt auch, wie ein Beitrag an den nächsten übergibt, abhängig
davon, was endet und was folgt: wie viel früher der nächste beginnt (meist 0,5 bis 3 Sekunden), ob einer von beiden
blendet, in welcher Form, und wo ein Song eingestartet wird ([wie das gespeichert ist](docs/STATIONS.md#mixing),
englisch). AWC Radio folgt diesen Regeln auf den 18 Musiksendern; die Talk-Sender haben keine, ihre Teile folgen direkt
aufeinander. Unter dem DJ läuft auf 16 Sendern eine der **Hintergrundmusiken** des Senders, wo die Aufnahme selbst
zwischen den Worten leise ist.

Wie die Zeiten gelesen werden, ist eine Auslegung: Sie zählen vom Ende einer Aufnahme (die Songs markieren auch einen
„Outro“-Punkt ein paar Sekunden davor), welche Aufnahmen eine Hintergrundmusik bekommen und wie laut sie ist, entscheidet
dieses Programm, und die Tiefpass-Verläufe mancher Regeln fehlen.

**Überblenden.** Für die Clubs und Mixe, die keine solchen Regeln haben: ein Song in den nächsten (Standard: 3
Sekunden), aus oder 1–12 Sekunden – am PC mit **Überblenden** oben im Fenster, in der App auf der Einstellungsseite. Die
Überblendung ist Teil des Zeitplans: PC und Handy laufen nur mit derselben Einstellung gleich.

**Der DJ über den Songs.** Am PC **DJ über Songs** anhaken oder in den Einstellungen der App einschalten (von Haus aus aus). Die Songdateien
markieren, wo der DJ über Anfang und Ende sprechen darf; die Sprach-Container des Senders halten Ansagen für diese Stellen,
die meisten genau zu diesem Song. Über einem Song spricht der DJ dann jedes zweite Mal das Intro und jedes dritte Mal das
Outro, und vor einem Werbeblock oder den Nachrichten leitet er über. Die Musik tritt zurück, solange er spricht. Welche
Ansage kommt, folgt derselben Uhr wie alles andere; der Zeitplan ist mit und ohne DJ gleich. 18 Sender haben solche
Ansagen. Am PC wird die Ansage vorher in eine Kopie des Songs gemischt (braucht neben `ffplay` auch `ffmpeg`).

## Podcasts

Die Audio-Konfiguration führt fünf Podcasts mit ihren Folgen in fester Reihenfolge: **Drunk Kings** (5), **Final Act** (4),
**Fuck Marry Murder** (6), **The Safe Trap** (3) und **Vice City True Stories** (2) – dieselben fünf, die die Credits
nennen. Alle 20 Folgen liegen in der `audio.rpf`, unabhängig vom Spielfortschritt. Sie laufen auf Abruf statt nach der Uhr, **in der Android-App** und im PC-Radio unter den Sendern: Folge wählen, pausieren, spulen, 15 Sekunden zurück oder 30 vor; die Stelle
in jeder Folge wird gemerkt. Die Titel der Folgen stammen aus der Tabelle des Spiels in `data.rpf`; ohne diese Datei sind sie nummeriert.
[Alle Folgen mit Länge](docs/RADIO-PROGRESS.de.md#podcasts).

![Podcasts in der App](docs/screenshots/08-app-podcasts.png)

## On-Demand

In der App arbeitet der Reiter **On-Demand** wie eine Musik-App über den Songs aller Packs: eine **Suche** nach Songs und
Interpreten, **alle Songs**, die **Interpreten** und eine **Playlist für jeden Sender** mit dessen Logo. Ein angetippter
Song spielt, und seine Liste läuft von dort weiter; **Zufall** und **Wiederholen** stehen im Player, und Android Auto zeigt
die Playlisten. Gelistet sind nur echte Songs: kein Talk, keine Werbung, kein Score und keine DJ-Mixe.

Die **Soundtrack-Playlist** enthält die Titel von *Grand Theft Auto VI: The Album (Deluxe Edition)*, die in deinen eigenen
Spieldateien liegen, 54 von 57, in der Reihenfolge des Albums. Die meisten laufen auf den Sendern; einige liegen ohne
Sender und ohne Namen im Archiv (welche Aufnahme was ist, wurde durch Klangvergleich mit den öffentlichen Hörproben des
Albums ermittelt, die nicht zum Projekt gehören), einer nur im Mix von The Vault. **Android-Packs…** schreibt sie als
`.playlistpak`; das Cover wird dabei von der Webseite des Herausgebers geladen und ist nicht Teil dieses Repositorys.

![On-Demand in der App](docs/screenshots/11-app-ondemand-de.png)

## Sender und Fortschritt

794 von 809 Songs, die die Credits für die Musiksender nennen (98,1 %), sind gefunden, dazu alle
Talk-Sendungen; alle Sender in [Fortschritt der Sender](docs/RADIO-PROGRESS.de.md).
Lange DJ-Mixe laufen auch als Sender: **The Vault Radio**, zusätzliche „(Mix)“-Sender für Radio On-U, Worldwide FM und The Sniper Of La Perle sowie **Clubmixes** (der eine Club-Mix, der keinem Ort zugeordnet werden kann). Während ein Mix läuft, wird der Titel des aktuellen Abschnitts angezeigt (aus den Markern im Mix, benannt über die Titeltabelle des Spiels in `data.rpf`; ohne diese Datei nur die Abschnitte, die es auch als einzelnen Song gibt).

Alle Zahlen je Sender: [Fortschritt der Sender](docs/RADIO-PROGRESS.de.md).

Die aktuelle Liste aller Sender mit Inhalt und Länge: [alle Sender](docs/RADIO-PROGRESS.de.md#alle-sender).

## AWC Radio für Android

Die Handy-App spielt dasselbe Live-Programm wie der PC: gleiche Uhr und Zeitzone, gleiche Reihenfolge, gleiche
Übergänge.
Dazu hat sie einen eigenen Bereich für die [Podcasts](#podcasts). Zur App gehören die Packs:

* **`AWC-Radio.apk`** - die App (Version 1.20), für alle dieselbe Datei, ohne Spieldaten. Android 7.0 oder neuer, Deutsch und
  Englisch. Senderraster, kleiner Player und großer Player in der Farbe des Senderlogos; Wiedergabe im Hintergrund mit
  Medien-Benachrichtigung, Sperrbildschirm- und Kopfhörertasten; Einschlaftimer; Schalter für das, was zwischen den Songs läuft; **Android Auto** (vorher in dessen
  Entwicklereinstellungen „Unbekannte Quellen“ aktivieren, weil die App nicht aus dem Play Store kommt).
* **On-Demand-Reiter** – Suche, Interpreten und Playlisten über die Songs aller Packs, siehe [On-Demand](#on-demand).
* **Einstellungsseite** - Überblenden ein/aus und Dauer (Clubs und Mixe), Inhalte zwischen den Songs, DJ über Songs, Pack-Ordner und neu
  laden, Updates, Akku-Einstellungen, Info und Protokoll; zu öffnen über das Drei-Punkte-Menü.
* **Score-Reiter** – der Score des Spiels aus den `.scorepak`-Dateien: fünf Gruppen (die fünfte, **Musik an Orten**,
  enthält die fertigen Aufnahmen von Fahrstühlen, Hotellobbys, einer Minigolfanlage und anderen Orten),
  darin Untergruppen (eine Mission, ein Gebiet) und Stücke; ein Stück läuft von vorn, danach geht die Gruppe weiter. Die
  Namen sind Arbeitsnamen aus den Spieldateien und können die Handlung verraten. Wie die Stücke entstehen, steht beim
  [Extractor](../extractor/README.de.md#der-score). Ein Suchfeld oben durchsucht den ganzen Score: Missionen und Orte nach
  ihrem Namen, Stücke nach Titel und Mission; Zurück leert es.
* **Radiowecker** - mehrere Wecker mit Wochentagen, Sender, Lautstärke, sanftem Start, Schlummern und Vibration. Er klingelt
  mit der Weckerlautstärke des Handys und zeigt einen Bildschirm über dem Sperrbildschirm. Ist vom Radio nach 10 Sekunden
  nichts zu hören (Pack fehlt, Player-Fehler, Handy nach einem Neustart noch nicht entsperrt), klingelt der Weckton des
  Handys. Nach 30 Minuten hört er von selbst auf.
* **Widgets** – lange auf den Startbildschirm drücken und *Widgets*, dann AWC Radio wählen. **Player**: Logo, Titel und
  Interpret in der Farbe des Senders mit Zurück, Pause und Weiter; in der Größe anpassbar, von Logo mit Pause bis zu zwei
  Zeilen. Ist das Radio aus, zeigt er den zuletzt gehörten Sender, und Play schaltet ihn ein. **Sender-Kachel**: einen
  Sender wählen; ein Tipp spielt ihn, ein weiterer pausiert.
* **Updates** – beim Start, höchstens einmal am Tag, fragt die App bei github.com nach der Liste der Releases. Eine neuere
  Version wird mit ihrem Changelog vorgeschlagen (herunterladen, später oder überspringen); der Download öffnet sich im
  Browser, ein Tipp auf die Datei installiert sie über die App. **Auf Updates prüfen** in den Einstellungen fragt jederzeit
  und zeigt immer einen Changelog; die automatische Prüfung ist dort abschaltbar. Sonst nutzt die App das Internet nicht.
* **Packs** - eine Datei je Sender (`.radiopak`), je Podcast (`.podpak`), eine mit den Nachrichten (`news.newspak`), eine für
  das Soundtrack-Album (`.playlistpak`) und eine je Score-Gruppe (`.scorepak`), am PC erzeugt: in AWC Radio auf **Android-Packs…** klicken, einen
  Ordner wählen und schreiben. Ein Sender-Pack enthält die Songs des Senders in der Originalqualität des Spiels (ca. 128 kbit/s, nicht
  neu kodiert), sein Logo und die Programmdaten; etwa 5,2 GB für alle 34 Sender (mit den DJ-Ansagen) und 0,2 GB für die
  Nachrichten. Ein Podcast-Pack enthält die Folgen und das Cover (zusammen 0,2 GB); die fünf Score-Packs mit den Stücken ab
  30 Sekunden werden am PC gemischt (1,8 GB, braucht FFmpeg). Alle in denselben Ordner auf dem Handy kopieren und diesen in
  der App wählen. Neue oder geänderte Packs werden von selbst übernommen. Programm und Übergänge wie im Spiel brauchen
  Sender-Packs, die Version 2.11.0 geschrieben hat.

Die Packs enthalten Spiel-Audio und Bilder: nur für den eigenen Gebrauch, bitte nicht weitergeben. Ohne Pack ist die App leer.
`python awc_radio.py --export-pack <Ordner> [--stations "Name" ...]` schreibt Packs ohne Fenster.

Quelltext und Build-Skripte der App liegen in `android/`:
`python android\build_apk.py <Ordner des Android SDK>` baut die APK (Android SDK und JDK nötig).
`python android\test\run_tests.py [Pack-Ordner]` testet Wiedergabe-, Wecker- und Pack-Logik am PC (JDK und `json.jar`, siehe
Skript). `python android\test\run_robo.py <Pack-Ordner>` führt Bildschirme und Wecker am PC mit
[Robolectric](https://robolectric.org/) aus und speichert Screenshots (welche Bibliotheken es braucht, steht im Skript).

## Selbst bauen

`build_exe.bat` (in diesem Ordner) installiert bei Bedarf PyInstaller und erzeugt `dist\AWC-Radio.exe`.

## Projektaufbau

```
radio/
  awc_radio.py          Das Radio-Programm (Fenster, Programmplan, Wiedergabe)
  awc_album.py          Das Soundtrack-Album: welche Aufnahmen seine Titel enthalten, und sein Cover
  awc_android.py        Schreibt die Packs für die Android-App (Sender, Podcasts, Nachrichten, Score), samt Dialog
  android/              Die Android-App: Java-Quelltext, Ressourcen, build_apk.py, make_logo.py, Tests
  Start AWC-Radio.bat   Starter
  build_exe.bat         Baut dist\AWC-Radio.exe mit PyInstaller
  docs/STATIONS.md      Wie die Sender in der Audio-Konfiguration gespeichert sind (englisch)
  docs/RADIO-PROGRESS.de.md  Gefundene Songs und Sendungen je Sender (auch englisch)
  docs/screenshots/     Bilder dieser Anleitung
```

Archiv-Scan, AWC-Leser, Credits-Liste und Logos sind die Module des [Extractor](../extractor/README.de.md).

## Rechtlicher Hinweis

**Bitte lies den ausführlichen [Disclaimer](../DISCLAIMER.de.md).** Kurz gesagt:

* Dieses Repository enthält **nur Quellcode und Dokumentation**. Es enthält **keine Spieldateien, keine Archive und
  keine extrahierten Audiodaten** – und wird das auch nie.
* Du brauchst deine eigenen Dateien aus einem Spiel, das du rechtmäßig besitzt. Für extrahiertes Audio bist du selbst
  verantwortlich – es unterliegt dem Urheberrecht der Rechteinhaber. Private, nicht-kommerzielle Analyse und
  Archivierung ist eine Sache, die Weitergabe in der Regel nicht erlaubt.
* Das Projekt steht in **keiner Verbindung** zu Rockstar Games, Take-Two oder anderen Publishern und wird von
  diesen nicht unterstützt. Alle Marken gehören ihren jeweiligen Inhabern.

## Credits und Lizenz

Das Layout der Audio-Konfiguration folgt den Notizen des Projekts **Leonida** (ItsNotRexed); dessen Textur-Leser hat
auch geholfen, den Pool mit den Sender-Logos zu verstehen (`awc_logos.py`).
Dass sich die Programmregeln des Spiels aus der Audio-Konfiguration lesen lassen, zeigte das Projekt
[SixFM](https://github.com/jaylikesbunda/SixFM) (jaylikesbunda); der Leser hier ist unabhängig geschrieben und nutzt weder
dessen Code noch dessen Daten. Welche Aufnahmen die Soundtrack-Titel ohne Sender enthalten, wurde mit den öffentlichen 30-Sekunden-Hörproben des
Albums ermittelt. Das Startbild-Symbol der Android-App wurde mit der
Fan-Schrift *GTA Art Deco* gezeichnet (nicht Teil dieses Repositorys). Überschriften und Uhrzeiten in der App nutzen
[Poiret One](https://github.com/alexeiva/poiretone) (SIL Open Font License, Text in `android/assets/`). Die Wiedergabe übernimmt
[FFmpeg](https://ffmpeg.org/).

[MIT](../LICENSE) – gilt nur für den Quellcode, nicht für Spieldaten.
