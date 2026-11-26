package org.awctool.radio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The releases as GitHub lists them: which version of the app each one brings, its changelog and its APK. No Android
 * classes.
 *
 * The app's version of a release is read from a hidden line of its notes, "&lt;!-- awc-app: 1.19 --&gt;", else from its
 * title ("..., app 1.17"); a release that names none brings no app. The changelog is the part of the notes under "What's
 * new" and "Updating ...", for a German phone under "Was ist neu" and "Aktualisieren" where the notes have those.
 */
final class Releases {
    static final String REPOSITORY = "Albotterin/AWC_from_RPF9_Extractor";
    static final String LIST = "https://api.github.com/repos/" + REPOSITORY + "/releases?per_page=30";
    static final String PAGE = "https://github.com/" + REPOSITORY + "/releases/tag/";
    private static final String DOWNLOADS = "https://github.com/" + REPOSITORY + "/releases/download/";
    private static final String APK = "AWC-Radio.apk";
    private static final Pattern MARKER = Pattern.compile("<!--\\s*awc-app:\\s*([0-9]+(?:\\.[0-9]+)+)\\s*-->");
    private static final Pattern TITLE = Pattern.compile("(?i)\\bapp\\s+([0-9]+(?:\\.[0-9]+)+)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern ITALIC = Pattern.compile("(?<![*\\w])\\*(?!\\s)([^*]+?)\\*(?![*\\w])");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*] +(.*)$");

    static final class Release {
        final String app, tag, published, notes;
        final String apk;                                    // where its APK is loaded from, "" if it has none

        Release(String app, String tag, String published, String notes, String apk) {
            this.app = app;
            this.tag = tag;
            this.published = published;
            this.notes = notes;
            this.apk = apk;
        }

        /** The day it was published, "2026-11-26". */
        String day() {
            return published.length() >= 10 ? published.substring(0, 10) : published;
        }

        /** What a tap on "download" opens: the APK, else the release's page. */
        String download() {
            return apk.isEmpty() ? PAGE + tag : apk;
        }
    }

    /** One line of a changelog. */
    static final class Line {
        final boolean heading;
        final String text;

        Line(boolean heading, String text) {
            this.heading = heading;
            this.text = text;
        }
    }

    private Releases() {
    }

    /** The published releases that bring an app, the newest app first. */
    static List<Release> parse(String json) throws JSONException {
        JSONArray list = new JSONArray(json);
        List<Release> out = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            if (o.optBoolean("draft") || o.optBoolean("prerelease")) continue;
            String notes = o.isNull("body") ? "" : o.optString("body"), title = o.isNull("name") ? "" : o.optString("name");
            Matcher found = MARKER.matcher(notes);
            if (!found.find()) {
                found = TITLE.matcher(title);
                if (!found.find()) continue;
            }
            String apk = "";
            JSONArray assets = o.optJSONArray("assets");
            for (int k = 0; assets != null && k < assets.length(); k++) {
                JSONObject a = assets.getJSONObject(k);
                String url = a.optString("browser_download_url");
                if (APK.equals(a.optString("name")) && url.startsWith(DOWNLOADS)) apk = url;       // only from there
            }
            String tag = o.optString("tag_name");
            if (!tag.matches("[A-Za-z0-9._-]+")) continue;
            out.add(new Release(found.group(1), tag, o.optString("published_at"), notes, apk));
        }
        Collections.sort(out, (a, b) -> {
            int by = compare(b.app, a.app);
            return by != 0 ? by : b.published.compareTo(a.published);
        });
        return out;
    }

    /** Versions like "1.9" and "1.17", number by number. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int by = Integer.compare(number(x, i), number(y, i));
            if (by != 0) return by;
        }
        return 0;
    }

    private static int number(String[] parts, int i) {
        if (i >= parts.length) return 0;
        String digits = parts[i].replaceAll("[^0-9].*$", "");
        return digits.isEmpty() || digits.length() > 9 ? 0 : Integer.parseInt(digits);
    }

    static List<Release> newer(List<Release> all, String installed) {
        List<Release> out = new ArrayList<>();
        for (Release r : all) if (compare(r.app, installed) > 0) out.add(r);
        return out;
    }

    static List<Release> of(List<Release> all, String version) {
        List<Release> out = new ArrayList<>();
        for (Release r : all) if (compare(r.app, version) == 0) out.add(r);
        return out;
    }

    /** The changelog in a release's notes, as lines for the screen. */
    static List<Line> changelog(String notes, boolean german) {
        List<String> headings = new ArrayList<>();
        List<List<String>> sections = new ArrayList<>();
        headings.add("");
        sections.add(new ArrayList<>());
        for (String line : notes.replace("\r", "").replaceAll("(?s)<!--.*?-->", "").split("\n")) {
            if (line.startsWith("## ")) {
                headings.add(line.substring(3).trim());
                sections.add(new ArrayList<>());
            } else {
                sections.get(sections.size() - 1).add(line);
            }
        }
        boolean own = german && starts(headings, "Was ist neu");
        String[] wanted = own ? new String[]{"Was ist neu", "Aktualisieren"} : new String[]{"What's new", "Updating"};
        boolean found = starts(headings, wanted[0]);
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            String heading = headings.get(i);
            // notes without such parts are shown whole, but for the list of files and the pointer to the disclaimer
            boolean take = found ? heading.startsWith(wanted[0]) || heading.startsWith(wanted[1])
                    : !heading.startsWith("Files") && !heading.startsWith("Dateien");
            if (!take) continue;
            List<Line> body = new ArrayList<>();
            for (String line : sections.get(i)) {
                if (line.trim().isEmpty() || !found && line.toLowerCase(java.util.Locale.ROOT).contains("disclaimer")) continue;
                String t = line.trim();
                if (t.startsWith("### ")) {
                    body.add(new Line(true, plain(t.substring(4))));
                } else if (t.startsWith("**") && t.endsWith("**") && t.length() > 4 && t.indexOf("**", 2) == t.length() - 2) {
                    body.add(new Line(true, plain(t)));
                } else {
                    Matcher b = BULLET.matcher(line);
                    if (b.matches()) body.add(new Line(false, (b.group(1).length() >= 2 ? "    – " : "• ") + plain(b.group(2))));
                    else body.add(new Line(false, plain(t)));
                }
            }
            if (body.isEmpty()) continue;
            if (!heading.isEmpty()) out.add(new Line(true, plain(heading)));
            out.addAll(body);
        }
        return out;
    }

    private static boolean starts(List<String> headings, String with) {
        for (String h : headings) if (h.startsWith(with)) return true;
        return false;
    }

    /** Markdown without its marks. */
    private static String plain(String s) {
        s = LINK.matcher(s).replaceAll("$1").replace("**", "").replace("`", "");
        return ITALIC.matcher(s).replaceAll("$1").trim();
    }
}
