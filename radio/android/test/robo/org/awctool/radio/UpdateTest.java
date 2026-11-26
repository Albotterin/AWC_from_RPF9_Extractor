package org.awctool.radio;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The update check: what GitHub's list of releases says, the popup with its changelog at the start, skipping and
 * switching it off, and the button in the settings, which always answers.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34, qualifiers = "de-rDE-w393dp-h851dp-port-xhdpi")
public class UpdateTest {
    private static final String BASE = "https://github.com/Albotterin/AWC_from_RPF9_Extractor/releases/download/";
    private static final String NOTES = "⚠️ **Please read the [disclaimer](https://example.org)**.\n\n## What's new\n\n"
            + "**A heading (app)**\n- A **new** thing with a [link](https://example.org) and *italics*.\n  - Below it `code`.\n\n"
            + "## Known limits\n- Not on a phone.\n\n## Updating from v2.0.0\n- Install it over the old app.\n\n## Files\n- `AWC-Radio.apk`\n";
    private static final String GERMAN = "\n## Was ist neu\n- Etwas Neues auf Deutsch.\n\n## Aktualisieren\n- Über die alte App installieren.\n";

    private int asked;

    @Before
    public void setUp() {
        Harness.reset();
        Harness.choosePacks();
        Harness.workingPlayers();
        Harness.radio();
    }

    private static JSONObject release(String tag, String title, String notes, String apk) throws Exception {
        JSONArray assets = new JSONArray();
        if (apk != null) assets.put(new JSONObject().put("name", "AWC-Radio.apk").put("browser_download_url", apk));
        return new JSONObject().put("tag_name", tag).put("name", title).put("body", notes).put("published_at", "2026-12-0" + tag.charAt(tag.length() - 1) + "T10:00:00Z")
                .put("draft", false).put("prerelease", false).put("assets", assets);
    }

    private void online(JSONObject... releases) {
        final String json = new JSONArray(java.util.Arrays.asList(releases)).toString();
        Updates.source = url -> {
            Assert.assertEquals(Releases.LIST, url);
            asked++;
            return json;
        };
    }

    private static String installed() {
        return Updates.installed(Harness.app());
    }

    private static SharedPreferences prefs() {
        return Harness.app().getSharedPreferences(RadioService.PREFS, 0);
    }

    private static void aDayPasses() {
        prefs().edit().putLong("updates_asked", System.currentTimeMillis() - 25L * 60 * 60 * 1000).commit();
    }

    private static List<TextView> texts(View v) {
        List<TextView> out = new ArrayList<>();
        if (v instanceof TextView) out.add((TextView) v);
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) out.addAll(texts(((ViewGroup) v).getChildAt(i)));
        return out;
    }

    private static String words(Dialog d) {
        StringBuilder s = new StringBuilder();
        for (TextView t : texts(d.getWindow().getDecorView())) if (t.getVisibility() == View.VISIBLE) s.append(t.getText()).append('\n');
        return s.toString();
    }

    private static Dialog popup() {
        Dialog d = ShadowDialog.getLatestDialog();
        return d != null && d.isShowing() ? d : null;
    }

    private ActivityController<MainActivity> start() {
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup();
        Harness.settle();
        return c;
    }

    private static void press(Dialog d, int button) {
        ((AlertDialog) d).getButton(button).performClick();
        Harness.pass(300);
    }

    @Test
    public void whatTheListSays() throws Exception {
        JSONObject draft = release("v9", "app 9.9", NOTES, null).put("draft", true);
        JSONObject beta = release("v8", "app 8.8", NOTES, null).put("prerelease", true);
        JSONObject foreign = release("v3", "AWC Tool v3, app 1.30", NOTES, "https://example.org/AWC-Radio.apk");
        JSONObject marked = release("v2", "AWC Tool v2", "<!-- awc-app: 1.100 -->\n" + NOTES + GERMAN, BASE + "v2/AWC-Radio.apk");
        JSONObject pcOnly = release("v4", "AWC Tool v4, the PC program only", NOTES, null);
        JSONObject old = release("v1", "AWC Tool v1.0, app 1.9", "plain old notes with a disclaimer line\nWhat came.", null);
        List<Releases.Release> all = Releases.parse(new JSONArray(java.util.Arrays.asList(draft, beta, foreign, old, marked, pcOnly)).toString());
        Assert.assertEquals(3, all.size());
        Assert.assertEquals("1.100", all.get(0).app);
        Assert.assertEquals("1.30", all.get(1).app);
        Assert.assertEquals("an APK from elsewhere is not taken", "", all.get(1).apk);
        Assert.assertEquals(Releases.PAGE + "v3", all.get(1).download());
        Assert.assertEquals(BASE + "v2/AWC-Radio.apk", all.get(0).download());
        Assert.assertTrue(Releases.compare("1.9", "1.17") < 0 && Releases.compare("1.19", "1.19") == 0 && Releases.compare("2", "1.99") > 0);
        Assert.assertEquals(2, Releases.newer(all, "1.19").size());

        StringBuilder english = new StringBuilder();
        for (Releases.Line l : Releases.changelog(all.get(0).notes, false)) english.append(l.heading ? "# " : "").append(l.text).append('\n');
        Assert.assertEquals("# What's new\n# A heading (app)\n• A new thing with a link and italics.\n    – Below it code.\n"
                + "# Updating from v2.0.0\n• Install it over the old app.\n", english.toString());
        List<Releases.Line> german = Releases.changelog(all.get(0).notes, true);
        Assert.assertEquals("Was ist neu", german.get(0).text);
        Assert.assertEquals("• Etwas Neues auf Deutsch.", german.get(1).text);
        Assert.assertEquals(4, german.size());
        Assert.assertEquals("without German notes the English ones", "What's new", Releases.changelog(all.get(1).notes, true).get(0).text);
        List<Releases.Line> plain = Releases.changelog(all.get(2).notes, true);
        Assert.assertEquals(1, plain.size());
        Assert.assertEquals("What came.", plain.get(0).text);
    }

    @Test
    public void theStartProposesANewerApp() throws Exception {
        online(release("v2", "AWC Tool v9, app 99.1", NOTES + GERMAN, BASE + "v2/AWC-Radio.apk"),
                release("v1", "AWC Tool v8, app 99.0", NOTES, BASE + "v1/AWC-Radio.apk"));
        ActivityController<MainActivity> c = start();
        Dialog d = popup();
        Assert.assertNotNull("a popup", d);
        String said = words(d);
        Assert.assertTrue(said, said.contains("Update verfügbar: Version 99.1"));
        Assert.assertTrue(said, said.contains("Installiert: Version " + installed()));
        Assert.assertTrue("the changelog of both", said.contains("Etwas Neues auf Deutsch.") && said.contains("Version 99.0")
                && said.contains("A new thing with a link and italics."));
        Assert.assertTrue(said, said.contains("Herunterladen") && said.contains("Später") && said.contains("Version überspringen"));
        ScreenshotTest.shot(c.get(), "95_update_popup", d);
        Shadows.shadowOf(Harness.application()).clearStartedServices();
        press(d, DialogInterface.BUTTON_POSITIVE);
        Intent browser = Shadows.shadowOf(c.get()).getNextStartedActivity();
        Assert.assertEquals(Intent.ACTION_VIEW, browser.getAction());
        Assert.assertEquals(BASE + "v2/AWC-Radio.apk", browser.getDataString());
        c.pause().stop().destroy();
    }

    @Test
    public void onceADaySkippingAndSwitchingOff() throws Exception {
        online(release("v1", "app 99.0", NOTES, BASE + "v1/AWC-Radio.apk"));
        ActivityController<MainActivity> c = start();
        press(popup(), DialogInterface.BUTTON_NEGATIVE);                    // later
        Assert.assertEquals(1, asked);
        c.stop().start();
        Harness.settle();
        Assert.assertEquals("not twice a day", 1, asked);
        aDayPasses();
        c.stop().start();
        Harness.settle();
        Assert.assertEquals(2, asked);
        press(popup(), DialogInterface.BUTTON_NEUTRAL);                     // skip this version
        aDayPasses();
        c.stop().start();
        Harness.settle();
        Assert.assertEquals(3, asked);
        Assert.assertNull("skipped", popup());
        online(release("v2", "app 99.1", NOTES, BASE + "v2/AWC-Radio.apk"), release("v1", "app 99.0", NOTES, null));
        aDayPasses();
        c.stop().start();
        Harness.settle();
        Assert.assertNotNull("a newer one is proposed again", popup());
        popup().dismiss();
        Updates.setAutomatic(Harness.app(), false);
        aDayPasses();
        asked = 0;
        c.stop().start();
        Harness.settle();
        Assert.assertEquals("switched off", 0, asked);
    }

    @Test
    public void silentWithoutAConnectionAndWhenUpToDate() throws Exception {
        ActivityController<MainActivity> c = start();                       // offline
        Assert.assertNull(popup());
        Assert.assertEquals("asked again next time", 0L, prefs().getLong("updates_asked", 0));
        online(release("v1", "app " + installed(), NOTES, null));
        c.stop().start();
        Harness.settle();
        Assert.assertEquals(1, asked);
        Assert.assertNull("nothing newer, nothing said", popup());
    }

    private SettingsActivity settings() {
        SettingsActivity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        Harness.settle();
        return a;
    }

    private Dialog checkIn(SettingsActivity a) {
        Assert.assertTrue(ScreenshotTest.tap(a.getWindow().getDecorView(), "Auf Updates prüfen"));
        Harness.settle();
        Dialog d = popup();
        Assert.assertNotNull("the button always answers", d);
        return d;
    }

    @Test
    public void theButtonInTheSettings() throws Exception {
        SettingsActivity a = settings();
        Dialog d = checkIn(a);
        Assert.assertTrue(words(d), words(d).contains("Prüfen nicht möglich") && words(d).contains("Ist das Handy online?"));
        d.dismiss();
        Updates.source = url -> {
            throw new IOException("HTTP 403");
        };
        d = checkIn(a);
        Assert.assertTrue(words(d), words(d).contains("keine weiteren Anfragen"));
        d.dismiss();

        online(release("v2", "app " + installed(), NOTES + GERMAN, null), release("v1", "app 1.0", NOTES, null));
        d = checkIn(a);
        String said = words(d);
        Assert.assertTrue(said, said.contains("Aktuell: Version " + installed()) && said.contains("Etwas Neues auf Deutsch."));
        Assert.assertFalse("only the changelog of this version", said.contains("Version 1.0 "));
        d.dismiss();

        online(release("v1", "app 1.0", NOTES, null));                      // not published yet
        d = checkIn(a);
        said = words(d);
        Assert.assertTrue(said, said.contains("neuer als die zuletzt veröffentlichte (1.0)") && said.contains("A new thing"));
        d.dismiss();

        online(release("v1", "app 99.0", NOTES, BASE + "v1/AWC-Radio.apk"));
        prefs().edit().putString("updates_skip", "99.0").commit();
        d = checkIn(a);                                                    // asked for: also a skipped one
        said = words(d);
        Assert.assertTrue(said, said.contains("Update verfügbar: Version 99.0") && said.contains("Schließen"));
        Assert.assertFalse(said, said.contains("Version überspringen"));
        press(d, DialogInterface.BUTTON_POSITIVE);
        Assert.assertEquals(BASE + "v1/AWC-Radio.apk", Shadows.shadowOf(a).getNextStartedActivity().getDataString());
        for (View v : ScreenshotTest.all(a.getWindow().getDecorView())) {
            if (v instanceof android.widget.ScrollView) v.scrollTo(0, 100_000);
        }
        Harness.pass(300);
        for (View v : ScreenshotTest.all(a.getWindow().getDecorView())) {
            if (v instanceof android.widget.ScrollView) {
                View content = ((ViewGroup) v).getChildAt(0);
                v.scrollTo(0, Math.max(0, content.getHeight() - v.getHeight()));
            }
        }
        Harness.pass(300);
        ScreenshotTest.shot(a, "96_update_settings", null);
    }

    @Test
    public void aScreenThatIsGoneGetsNoAnswer() throws Exception {
        final java.util.concurrent.CountDownLatch wait = new java.util.concurrent.CountDownLatch(1);
        Updates.source = url -> {
            try {
                wait.await();
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            return "[]";
        };
        ActivityController<SettingsActivity> c = Robolectric.buildActivity(SettingsActivity.class).setup();
        Harness.settle();
        Updates.check(c.get());
        c.pause().stop().destroy();
        wait.countDown();
        Harness.settle();
        Assert.assertNull(popup());
    }
}
