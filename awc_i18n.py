"""Tiny translation helper for AWC Tool.

Usage:  from awc_i18n import tr, set_lang
        tr("btn_play")                    -> "▶ Play"
        tr("st_scanning_n", n=3)          -> "Scanning 3 file(s) …"

To add a language: add an entry to LANGUAGES and a table to STRINGS (missing keys fall back to English).
"""
import json
import os

LANGUAGES = {"en": "English", "de": "Deutsch"}
DEFAULT_LANG = "en"
_lang = DEFAULT_LANG

STRINGS = {
    "en": {
        "app_title": "AWC Tool – find, play and extract music streams",
        "btn_scan_folder": "Scan folder…",
        "btn_open_files": "Open files (AWC/RPF)…",
        "btn_clear": "Clear list",
        "chk_cache": "Use scan cache",
        "btn_scan_cancel": "Cancel scan",
        "lbl_language": "Language:",
        "h_tree": "Container / AWC / Stream",
        "h_id": "Stream ID",
        "h_codec": "Codec",
        "h_ch": "Channels",
        "h_dur": "Duration",
        "h_size": "Size",
        "lf_player": "Player",
        "btn_play": "▶ Play",
        "btn_pause": "❚❚ Pause",
        "btn_stop": "■ Stop",
        "lbl_volume": "Volume",
        "lbl_no_track": "No track loaded",
        "lf_extract": "Extract",
        "lbl_format": "Format:",
        "lbl_outdir": "Output folder:",
        "btn_extract_sel": "Extract selected",
        "btn_extract_all": "Extract all streams",
        "btn_cancel": "Cancel",
        "btn_to_flac": "Selected → FLAC",
        "btn_to_wav": "Selected → WAV",
        "btn_open_out": "Open folder",
        "lbl_minsec": "Only streams longer than (sec.):",
        "fmt_opus": "Opus (lossless, original)",
        "fmt_flac": "FLAC (lossless, large)",
        "fmt_wav": "WAV (16-bit PCM, very large)",
        "fmt_mp3": "MP3 (320 kbit/s)",
        "st_ready": "Ready.",
        "stereo": "Stereo",
        "mono": "Mono",
        "n_awc": "{n} AWC",
        "count_label": "{n} streams, ~{t}",
        "scan_progress": "Scan: {p}% – {n} music AWCs",
        "dlg_ffmpeg_title": "ffmpeg missing",
        "dlg_ffmpeg_body": "ffmpeg/ffplay were not found.\nPlayback and conversion (FLAC/WAV/MP3) are disabled. "
                           "Opus extraction still works.\n\nPut ffmpeg.exe and ffplay.exe next to the program "
                           "or into your PATH.",
        "dlg_pick_folder": "Choose a folder with RPF/AWC files (subfolders are included)",
        "dlg_pick_files": "Choose AWC/RPF files",
        "ft_awc_rpf": "AWC/RPF",
        "ft_awc": "AWC audio",
        "ft_rpf": "RPF archive",
        "ft_all": "All files",
        "dlg_busy_title": "Busy",
        "dlg_busy": "Another operation is currently running.",
        "st_no_new_files": "No new .rpf/.awc files found.",
        "st_scanning_n": "Scanning {n} file(s) …",
        "st_scanning": "Scanning {p} …",
        "st_scan_error_file": "Error in {p}: {e}",
        "st_scan_cancelled": "Scan cancelled.",
        "st_scan_error": "Scan error: {e}",
        "st_scan_done": "Done: {c} container(s), {a} music AWCs, {s} streams.",
        "st_scan_none": "No music AWCs found. ({n} file(s) searched)",
        "dlg_pick_out": "Choose output folder",
        "dlg_folder_title": "Folder",
        "dlg_folder_missing": "The output folder does not exist yet.",
        "st_cancelling": "Cancelling…",
        "dlg_nothing_title": "Nothing selected",
        "dlg_nothing": "Please select streams, AWCs or containers in the list\n(or use “Extract all streams”).",
        "dlg_need_ffmpeg": "ffmpeg is required for this format.",
        "dlg_filter_title": "Filter",
        "dlg_filter_none": "No stream is at least {s} seconds long.",
        "dlg_unsupported_title": "Not supported",
        "dlg_all_unsupported": "All selected streams use an unsupported codec.",
        "dlg_outdir_title": "Output folder",
        "dlg_confirm_body": "{n} stream(s), about {dur} of audio\nEstimated output size: about {size} "
                            "(free: {free})\nFormat: {fmt}\nTarget: {out}",
        "dlg_no_space": "Not enough disk space",
        "dlg_confirm_title": "Extract?",
        "dlg_continue": "Continue?",
        "st_group": "({i}/{n}) {name} – {k} stream(s) …",
        "st_extract_done": "Done: {n} file(s) in {out}",
        "st_cancelled": "Cancelled.",
        "err_title": "Error",
        "st_error": "Error.",
        "err_ffmpeg": "ffmpeg error:\n{e}",
        "dlg_codec_play": "Codec {c} cannot be played.",
        "dlg_ffplay_title": "ffplay missing",
        "dlg_ffplay": "ffplay is required for playback.",
        "st_wait_other": "Please wait, another operation is running…",
        "st_preparing": "Preparing playback …",
        "dlg_player_title": "Player",
        "dlg_select_first": "Please select a stream first.",
        "st_playing": "Playing …",
        "st_paused": "Paused.",
        "st_ended": "End.",
        "open_error_title": "Error opening file",
        "err_not_awc": "Not an AWC file (magic 'ADAT' missing).",
        "err_header": "Invalid header.",
        "err_not_music": "Not a multichannel music container (chunks 0x48/0x55 missing).\n"
                         "This tool only supports music AWCs.",
        "err_format_chunk": "Invalid stream format chunk.",
        "err_codec": "Codec {c} is not supported.",
        "grp_label": "Group {g:02d}",
        "grp_label_named": "Group {g:02d} – {name}",
        "ctx_rename": "Rename group…",
        "ctx_clear_name": "Remove group name",
        "dlg_rename_title": "Rename group",
        "dlg_rename_prompt": "Name for group {g:02d} (e.g. the radio station).\nUsed as part of the output folder name; leave empty to remove the name.",
        "lf_names": "Names (stations & titles)",
        "btn_names_import": "Import names…",
        "btn_names_template": "Export name list…",
        "btn_names_clear": "Clear names",
        "chk_playlist": "Write playlists (.m3u8)",
        "lbl_named": "{k} of {n} streams named",
        "ctx_set_track": "Set title / artist…",
        "ctx_clear_track": "Remove title / artist",
        "dlg_track_title": "Title / artist",
        "dlg_track_prompt": "Name for stream {id}. Format: Artist - Title\n(or only the title). Leave empty to remove.",
        "dlg_names_import": "Import names (CSV, TXT or JSON)",
        "dlg_names_title": "Names",
        "dlg_names_result": "{ok} name(s) matched to streams, {skipped} entry/entries not used.\n\nEntries given by ID are always kept; entries given only as an internal name must hash to the ID of a stream that is currently loaded.",
        "dlg_names_noscan": "Scan some AWC/RPF files first.",
        "dlg_names_template": "Save name list (fill in artist/title, then import it again)",
        "dlg_names_template_done": "{n} stream(s) written to\n{path}\n\nOpen it in a spreadsheet, fill in the artist and title columns and use 'Import names…'.",
        "dlg_names_clear_q": "Remove all stored titles/artists?",
        "ft_names": "Name lists",
        "btn_names_game": "Read names from game files",
        "st_reading_names": "Reading names from the game config …",
        "st_names_read": "Names read: {k} title(s) filled in.",
        "dlg_gamenames_none": "Scan an RPF archive first (e.g. audio.rpf).",
        "dlg_gamenames_result": "{k} title(s) filled in from the game config ({kept} already had a name and were kept).\n{n} of {t} streams were not found in the config's name list.\n\n{st} station name(s) are now offered in 'Rename group…'.\n\nThe names are internal titles only - there is no artist information; edit them via right click > Set title / artist.",
        "dlg_gamenames_error": "Could not read the config of:\n{paths}",
        "btn_ok": "OK",
        "stream_label": "Stream {i}  (ID {id})",
    },
    "de": {
        "app_title": "AWC-Tool – Musikstreams suchen, abspielen, extrahieren",
        "btn_scan_folder": "Ordner scannen…",
        "btn_open_files": "Dateien öffnen (AWC/RPF)…",
        "btn_clear": "Liste leeren",
        "chk_cache": "Scan-Cache nutzen",
        "btn_scan_cancel": "Scan abbrechen",
        "lbl_language": "Sprache:",
        "h_tree": "Container / AWC / Stream",
        "h_id": "Stream-ID",
        "h_codec": "Codec",
        "h_ch": "Kanäle",
        "h_dur": "Dauer",
        "h_size": "Größe",
        "lf_player": "Player",
        "btn_play": "▶ Abspielen",
        "btn_pause": "❚❚ Pause",
        "btn_stop": "■ Stop",
        "lbl_volume": "Lautstärke",
        "lbl_no_track": "Kein Titel geladen",
        "lf_extract": "Extrahieren",
        "lbl_format": "Format:",
        "lbl_outdir": "Zielordner:",
        "btn_extract_sel": "Markierte extrahieren",
        "btn_extract_all": "Alle Streams extrahieren",
        "btn_cancel": "Abbrechen",
        "btn_to_flac": "Markierte → FLAC",
        "btn_to_wav": "Markierte → WAV",
        "btn_open_out": "Ordner öffnen",
        "lbl_minsec": "Nur Streams ab (Sek.):",
        "fmt_opus": "Opus (verlustfrei, original)",
        "fmt_flac": "FLAC (verlustfrei, groß)",
        "fmt_wav": "WAV (16 Bit PCM, sehr groß)",
        "fmt_mp3": "MP3 (320 kbit/s)",
        "st_ready": "Bereit.",
        "stereo": "Stereo",
        "mono": "Mono",
        "n_awc": "{n} AWC",
        "count_label": "{n} Streams, ~{t}",
        "scan_progress": "Scan: {p}% – {n} Musik-AWCs",
        "dlg_ffmpeg_title": "ffmpeg fehlt",
        "dlg_ffmpeg_body": "ffmpeg/ffplay wurden nicht gefunden.\nAbspielen und Konvertieren (FLAC/WAV/MP3) "
                           "sind deaktiviert. Opus-Extraktion funktioniert trotzdem.\n\n"
                           "Lege ffmpeg.exe und ffplay.exe neben das Programm oder in den PATH.",
        "dlg_pick_folder": "Ordner mit RPF-/AWC-Dateien wählen (inkl. Unterordner)",
        "dlg_pick_files": "AWC-/RPF-Dateien wählen",
        "ft_awc_rpf": "AWC/RPF",
        "ft_awc": "AWC-Audio",
        "ft_rpf": "RPF-Archiv",
        "ft_all": "Alle Dateien",
        "dlg_busy_title": "Beschäftigt",
        "dlg_busy": "Es läuft gerade ein anderer Vorgang.",
        "st_no_new_files": "Keine neuen .rpf/.awc-Dateien gefunden.",
        "st_scanning_n": "Scanne {n} Datei(en) …",
        "st_scanning": "Scanne {p} …",
        "st_scan_error_file": "Fehler bei {p}: {e}",
        "st_scan_cancelled": "Scan abgebrochen.",
        "st_scan_error": "Scan-Fehler: {e}",
        "st_scan_done": "Fertig: {c} Container, {a} Musik-AWCs, {s} Streams.",
        "st_scan_none": "Keine Musik-AWCs gefunden. ({n} Datei(en) durchsucht)",
        "dlg_pick_out": "Zielordner wählen",
        "dlg_folder_title": "Ordner",
        "dlg_folder_missing": "Der Zielordner existiert noch nicht.",
        "st_cancelling": "Abbruch…",
        "dlg_nothing_title": "Nichts ausgewählt",
        "dlg_nothing": "Bitte Streams, AWCs oder Container in der Liste markieren\n"
                       "(oder „Alle Streams extrahieren“ wählen).",
        "dlg_need_ffmpeg": "Für dieses Format wird ffmpeg benötigt.",
        "dlg_filter_title": "Filter",
        "dlg_filter_none": "Kein Stream ist mindestens {s} Sekunden lang.",
        "dlg_unsupported_title": "Nicht unterstützt",
        "dlg_all_unsupported": "Alle gewählten Streams haben einen nicht unterstützten Codec.",
        "dlg_outdir_title": "Zielordner",
        "dlg_confirm_body": "{n} Stream(s), ca. {dur} Audio\nGeschätzte Ausgabegröße: ca. {size} "
                            "(frei: {free})\nFormat: {fmt}\nZiel: {out}",
        "dlg_no_space": "Zu wenig Speicherplatz",
        "dlg_confirm_title": "Extrahieren?",
        "dlg_continue": "Fortfahren?",
        "st_group": "({i}/{n}) {name} – {k} Stream(s) …",
        "st_extract_done": "Fertig: {n} Datei(en) in {out}",
        "st_cancelled": "Abgebrochen.",
        "err_title": "Fehler",
        "st_error": "Fehler.",
        "err_ffmpeg": "ffmpeg-Fehler:\n{e}",
        "dlg_codec_play": "Codec {c} kann nicht abgespielt werden.",
        "dlg_ffplay_title": "ffplay fehlt",
        "dlg_ffplay": "Zum Abspielen wird ffplay benötigt.",
        "st_wait_other": "Bitte warten, ein anderer Vorgang läuft…",
        "st_preparing": "Bereite Wiedergabe vor …",
        "dlg_player_title": "Player",
        "dlg_select_first": "Bitte erst einen Stream auswählen.",
        "st_playing": "Spielt …",
        "st_paused": "Pausiert.",
        "st_ended": "Ende.",
        "open_error_title": "Fehler beim Öffnen",
        "err_not_awc": "Keine AWC-Datei (Magic 'ADAT' fehlt).",
        "err_header": "Ungültiger Header.",
        "err_not_music": "Kein Mehrkanal-Musik-Container (Chunks 0x48/0x55 fehlen).\n"
                         "Dieses Tool unterstützt nur Musik-AWCs.",
        "err_format_chunk": "Ungültiger Stream-Format-Chunk.",
        "err_codec": "Codec {c} wird nicht unterstützt.",
        "grp_label": "Gruppe {g:02d}",
        "grp_label_named": "Gruppe {g:02d} – {name}",
        "ctx_rename": "Gruppe umbenennen…",
        "ctx_clear_name": "Gruppennamen entfernen",
        "dlg_rename_title": "Gruppe umbenennen",
        "dlg_rename_prompt": "Name für Gruppe {g:02d} (z. B. der Radiosender).\nWird Teil des Ausgabe-Ordnernamens; leer lassen, um den Namen zu entfernen.",
        "lf_names": "Namen (Sender & Titel)",
        "btn_names_import": "Namen importieren…",
        "btn_names_template": "Namensliste exportieren…",
        "btn_names_clear": "Namen löschen",
        "chk_playlist": "Playlists schreiben (.m3u8)",
        "lbl_named": "{k} von {n} Streams benannt",
        "ctx_set_track": "Titel / Interpret setzen…",
        "ctx_clear_track": "Titel / Interpret entfernen",
        "dlg_track_title": "Titel / Interpret",
        "dlg_track_prompt": "Name für Stream {id}. Format: Interpret - Titel\n(oder nur der Titel). Leer lassen zum Entfernen.",
        "dlg_names_import": "Namen importieren (CSV, TXT oder JSON)",
        "dlg_names_title": "Namen",
        "dlg_names_result": "{ok} Name(n) Streams zugeordnet, {skipped} Eintrag/Einträge nicht verwendet.\n\nEinträge mit ID werden immer übernommen; Einträge nur mit internem Namen müssen auf die ID eines aktuell geladenen Streams hashen.",
        "dlg_names_noscan": "Bitte zuerst AWC-/RPF-Dateien scannen.",
        "dlg_names_template": "Namensliste speichern (Interpret/Titel ausfüllen, dann wieder importieren)",
        "dlg_names_template_done": "{n} Stream(s) geschrieben nach\n{path}\n\nIn einer Tabellenkalkulation öffnen, Spalten artist und title ausfüllen und mit 'Namen importieren…' einlesen.",
        "dlg_names_clear_q": "Alle gespeicherten Titel/Interpreten entfernen?",
        "ft_names": "Namenslisten",
        "btn_names_game": "Namen aus Spieldateien lesen",
        "st_reading_names": "Lese Namen aus der Spiel-Konfiguration …",
        "st_names_read": "Namen gelesen: {k} Titel eingetragen.",
        "dlg_gamenames_none": "Bitte zuerst ein RPF-Archiv scannen (z. B. audio.rpf).",
        "dlg_gamenames_result": "{k} Titel aus der Spiel-Konfiguration eingetragen ({kept} hatten schon einen Namen und blieben unverändert).\n{n} von {t} Streams stehen nicht in der Namensliste der Konfiguration.\n\n{st} Stationsname(n) werden jetzt bei 'Gruppe umbenennen…' vorgeschlagen.\n\nEs sind nur interne Titel, ohne Interpreten - per Rechtsklick > Titel / Interpret setzen lassen sie sich ändern.",
        "dlg_gamenames_error": "Konnte die Konfiguration nicht lesen:\n{paths}",
        "btn_ok": "OK",
        "stream_label": "Stream {i}  (ID {id})",
    },
}


def _settings_path():
    d = os.path.join(os.environ.get("APPDATA") or os.path.expanduser("~"), "AWC-Tool")
    return os.path.join(d, "settings.json")


def load_saved_lang():
    """Return the saved language code (or the default) and make it active."""
    try:
        with open(_settings_path(), "r", encoding="utf-8") as f:
            code = json.load(f).get("lang")
        if code in LANGUAGES:
            set_lang(code)
            return code
    except (OSError, ValueError, AttributeError):
        pass
    set_lang(DEFAULT_LANG)
    return DEFAULT_LANG


def save_lang(code):
    try:
        p = _settings_path()
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, "w", encoding="utf-8") as f:
            json.dump({"lang": code}, f)
    except OSError:
        pass


def set_lang(code):
    global _lang
    if code in LANGUAGES:
        _lang = code


def get_lang():
    return _lang


def tr(key, **kw):
    s = STRINGS.get(_lang, {}).get(key) or STRINGS["en"].get(key) or key
    return s.format(**kw) if kw else s
