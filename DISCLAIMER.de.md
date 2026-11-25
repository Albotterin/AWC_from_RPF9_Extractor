# Disclaimer / Haftungsausschluss

🇬🇧 [English version](DISCLAIMER.md) · [← Projektübersicht](README.de.md)

**Bitte lies das, bevor du etwas aus diesem Repository herunterlädst, baust oder benutzt.**

## Kurz gesagt

* Das ist ein **Player und Datei-Werkzeug** für Spieldaten, die **du bereits besitzt**.
* **Es werden keine Spieldaten mitgeliefert.** Ohne deine eigenen Spieldateien tun die Programme nichts.
* **Nichts wird entschlüsselt**, und kein Kopierschutz wird umgangen.
* **Nichts wird verändert:** weder das Spiel noch seine Dateien noch eine Konsole.
* Alles, was die Programme aus deinen Dateien erzeugen, bleibt **bei dir, für deinen privaten Gebrauch**.

## 1. Was dieses Projekt ist

Das Repository enthält den Quellcode und die Dokumentation von drei Programmen:

| Programm | Was es tut |
|---|---|
| AWC Audio Extractor (PC) | findet die Audio-Container in einem Archiv, das du auswählst, spielt sie ab und kann sie in Audiodateien kopieren |
| AWC Radio (PC) | spielt die Radiosender deiner Dateien als Live-Programm und schreibt Sender- und Podcast-Packs für dein Handy |
| AWC-Radio-App (Android) | spielt diese Packs ab, mit Radiowecker |

Es ist ein privates, nicht-kommerzielles Hobbyprojekt. Es ist kein Spiel, kein Mod-Loader und kein Werkzeug, um ein Spiel
auszuführen, zu portieren oder neu zu kompilieren.

## 2. Was in diesem Repository ist – und was nicht

**Enthalten:** Quellcode, Dokumentation, eigene Grafiken (App-Symbol, das „Clubmixes“-Bild) und die Schrift *Poiret One*
unter der SIL Open Font License.

**Nicht enthalten und auch nicht hinzuzufügen:** Spieldateien oder Archive, Programme oder Bibliotheken des Spiels oder
seiner Plattform, Audio, Texturen oder Logo-Dateien aus dem Spiel, Sender-Packs, Verschlüsselungs-Schlüssel.

Zwei Dinge werden hier offen benannt:

* Die **Screenshots** in der Dokumentation zeigen Senderlogos und Songtitel so, wie die Programme sie aus den eigenen
  Dateien eines Nutzers anzeigen. Sie dienen nur dazu, zu veranschaulichen, was die Software tut.
* Quellcode und Dokumentation enthalten **Namen und Zahlen-Kennungen** (zum Beispiel Sendernamen, Hash-Werte und
  Positionen in Dateiformaten). Sie werden gebraucht, um Daten in den eigenen Dateien des Nutzers zu finden, und
  enthalten weder Audio noch Bilder.

## 3. Keine Entschlüsselung, keine Umgehung

* Die Programme lesen nur Daten, die **unverschlüsselt** gespeichert sind: Audio-Container, die durch Durchsuchen
  gefunden werden, sowie Konfigurations- und Texturdaten in gewöhnlicher, offen dokumentierter Kompression (zlib).
* Teile des Archivs, die verschlüsselt oder verschleiert erscheinen, etwa sein Inhaltsverzeichnis, werden **nicht
  gelesen**.
* Das Repository enthält **keine Schlüssel** und keinen Code, um sie zu beschaffen, abzuleiten oder anzuwenden.
* Die Programme umgehen weder Kopierschutz noch digitale Rechteverwaltung noch sonstige Sicherheitsfunktionen einer
  Konsole oder eines Spiels.
* Daten in Formaten, die die Programme nicht lesen können, werden übersprungen.

Anfragen nach Hilfe beim Entschlüsseln, beim Verändern von Konsolen oder beim Umgehen von Schutzmaßnahmen werden nicht
beantwortet.

## 4. Nur lesender Zugriff

Die Spieldateien werden **ausschließlich zum Lesen** geöffnet. Die Programme verändern sie nicht, installieren nichts auf
einer Konsole, bieten keine Cheats, greifen nicht in ein laufendes Spiel ein und verbinden sich nicht mit Diensten des
Spiels oder der Plattform.

Der einzige Netzwerkzugriff der PC-Programme ist der Download der **öffentlichen Credits-Webseite des Publishers**, um
Songs zu benennen. Die Android-App hat keine Internet-Berechtigung.

## 5. Deine eigenen Dateien

* Alle Spieldaten musst du selbst bereitstellen, aus einem **Exemplar des Spiels, das du rechtmäßig besitzt**.
* Wie du Dateien aus deinem Exemplar gewinnst, liegt in deiner Verantwortung. Dieses Projekt stellt keine Spieldateien
  bereit, verlinkt keine Quellen dafür und hilft nicht bei der Beschaffung.
* **Verwende keine geleakten, gestohlenen oder sonst unrechtmäßig erlangten Daten.** Das Projekt unterstützt das in
  keiner Weise.

## 6. Umgang mit den erzeugten Dateien

Audiodateien, Logos und Sender-Packs aus deinen Dateien bleiben durch das **Urheberrecht ihrer Inhaber** geschützt – des
Publishers, der Interpreten, Komponisten, Labels und weiterer Rechteinhaber.

* Nutze sie **nur privat** und nur, soweit das Recht an deinem Wohnort das erlaubt.
* **Teile, verbreite, verkaufe, streame oder spiele sie nicht öffentlich ab.** Das gilt auch für die Sender- und Podcast-Packs.
* Die Android-App selbst enthält keine Spieldaten; ein Pack auf deinem Handy schon.

## 7. Lizenzbedingungen und Recht vor Ort

Die Lizenz- oder Nutzungsbedingungen deines Spiels oder deiner Plattform können das Auslesen oder Untersuchen der Dateien
einschränken. Das Urheberrecht, einschließlich der Regeln zur Privatkopie und zum Reverse Engineering, **ist von Land zu
Land verschieden**. Du bist selbst dafür verantwortlich zu prüfen, ob deine Nutzung erlaubt ist. Nichts in diesem
Repository ist Rechtsberatung, und nichts hier sichert zu, dass eine bestimmte Nutzung bei dir rechtmäßig ist.

## 8. Keine Verbindung, Marken

Dieses Projekt steht in **keiner Verbindung** zu Rockstar Games, Take-Two Interactive Software, Microsoft, Sony, Google,
einem Radiosender, Interpreten, Label oder anderen Rechteinhabern und wird von diesen weder genehmigt noch gesponsert
oder unterstützt. *Grand Theft Auto* und alle weiteren Namen, Logos und Marken gehören ihren jeweiligen Inhabern. Sie
werden nur genannt, um zu beschreiben, womit die Software arbeitet.

## 9. Keine Gewährleistung, keine Haftung

Die Software wird **„wie sie ist“** bereitgestellt, ohne jede Gewährleistung, wie in der [MIT-Lizenz](LICENSE) beschrieben.
Soweit gesetzlich zulässig, haften die Autoren nicht für Schäden oder Ansprüche, die aus der Software oder ihrer Nutzung
entstehen. Verlass dich insbesondere beim Radiowecker nicht auf ihn, ohne ihn auf deinem eigenen Gerät getestet zu haben.

Die MIT-Lizenz gilt **nur für den Code dieses Repositorys** (Teile Dritter behalten ihre eigenen Lizenzen). Sie räumt
keinerlei Rechte an Spieldaten, Musik, Grafiken oder Marken ein.

## 10. Für Rechteinhaber

Wenn du der Ansicht bist, dass etwas in diesem Repository deine Rechte verletzt,
[eröffne bitte ein Issue](https://github.com/Albotterin/AWC_from_RPF9_Extractor/issues) und nenne die Datei und das
betroffene Recht. Das wird zügig geprüft, und Material, das hier nicht sein sollte, wird entfernt.
