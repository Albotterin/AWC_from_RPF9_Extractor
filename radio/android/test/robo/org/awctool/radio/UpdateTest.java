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
        allow(false);
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

    private static void forgetStarted(android.app.Activity a) {
        while (Shadows.shadowOf(a).getNextStartedActivity() != null) {
        }
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
        Assert.assertTrue(said, said.contains("Installieren") && said.contains("Später") && said.contains("Version überspringen"));
        ScreenshotTest.shot(c.get(), "95_update_popup", d);
        forgetStarted(c.get());
        press(d, DialogInterface.BUTTON_POSITIVE);
        Assert.assertTrue("first it must be allowed", words(popup()).contains("Update erlauben"));
        Assert.assertNull("no browser", Shadows.shadowOf(c.get()).getNextStartedActivity());
        press(popup(), DialogInterface.BUTTON_NEUTRAL);                     // the old way stays open
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
        Assert.assertTrue(words(popup()).contains("Update erlauben"));
        press(popup(), DialogInterface.BUTTON_NEGATIVE);
        Assert.assertNull(Shadows.shadowOf(a).getNextStartedActivity());
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

    // ------------------------------------------------------------------------------------------ installing
    private static final byte[] APP = new byte[100_000];
    private int loaded;

    private static String sha256(byte[] data) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(data)) hex.append(String.format("%02x", b & 255));
        return hex.toString();
    }

    /** A release whose APK GitHub lists with this length and checksum, and a download that brings APP. */
    private void offered(long size, String sha) throws Exception {
        JSONObject r = release("v1", "app 99.0", NOTES + GERMAN, BASE + "v1/AWC-Radio.apk");
        r.getJSONArray("assets").getJSONObject(0).put("size", size).put("digest", "sha256:" + sha);
        online(r);
        Installer.source = url -> {
            Assert.assertEquals(BASE + "v1/AWC-Radio.apk", url);
            loaded++;
            return new java.io.ByteArrayInputStream(APP);
        };
    }

    /** What Android reads from the downloaded file. */
    private static void fileIs(String name, int code) {
        android.content.pm.PackageInfo info = new android.content.pm.PackageInfo();
        info.packageName = name;
        info.versionName = "99.0";
        info.versionCode = code;
        Shadows.shadowOf(Harness.app().getPackageManager())
                .setPackageArchiveInfo(new java.io.File(Harness.app().getCacheDir(), "update.apk").getAbsolutePath(), info);
    }

    private static void allow(boolean on) {
        Shadows.shadowOf(Harness.app().getPackageManager()).setCanRequestPackageInstalls(on);
    }

    private static int code() throws Exception {
        return Harness.app().getPackageManager().getPackageInfo(Harness.app().getPackageName(), 0).versionCode;
    }

    private static List<android.content.pm.PackageInstaller.SessionInfo> sessions() {
        return Harness.app().getPackageManager().getPackageInstaller().getMySessions();
    }

    private static boolean said(String words) {
        Dialog d = popup();
        return d != null && words(d).contains(words);
    }

    @Test
    public void theAppInstallsItsUpdate() throws Exception {
        allow(true);
        offered(APP.length, sha256(APP));
        fileIs(Harness.app().getPackageName(), code() + 1);
        ActivityController<MainActivity> c = start();
        forgetStarted(c.get());
        press(popup(), DialogInterface.BUTTON_POSITIVE);
        Harness.settle();
        Assert.assertEquals(1, loaded);
        Assert.assertEquals("handed to Android", 1, sessions().size());
        Assert.assertEquals(Harness.app().getPackageName(), sessions().get(0).getAppPackageName());
        Assert.assertFalse(Installer.busy());
        Assert.assertNull("nothing left to say: " + (popup() == null ? "" : words(popup())), popup());
        Assert.assertFalse("the file is gone", new java.io.File(Harness.app().getCacheDir(), "update.apk").exists());
        Assert.assertNull("no browser", Shadows.shadowOf(c.get()).getNextStartedActivity());

        // Android wants the listener's word: its question is shown
        Intent question = new Intent("android.content.pm.action.CONFIRM_INSTALL");
        Intent answer = new Intent(Harness.app(), InstallReceiver.class)
                .putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, question);
        new InstallReceiver().onReceive(Harness.app(), answer);
        Assert.assertEquals(question.getAction(), Shadows.shadowOf(c.get()).getNextStartedActivity().getAction());

        // and if it refuses, the app says so and offers the browser
        answer = new Intent(Harness.app(), InstallReceiver.class).putExtra(InstallReceiver.EXTRA_PAGE, BASE + "v1/AWC-Radio.apk")
                .putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE_CONFLICT)
                .putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE, "INSTALL_FAILED_UPDATE_INCOMPATIBLE");
        new InstallReceiver().onReceive(Harness.app(), answer);
        Harness.pass(300);
        Assert.assertTrue(said("INSTALL_FAILED_UPDATE_INCOMPATIBLE"));
        ScreenshotTest.shot(c.get(), "97_update_refused", popup());
        press(popup(), DialogInterface.BUTTON_POSITIVE);
        Assert.assertEquals(BASE + "v1/AWC-Radio.apk", Shadows.shadowOf(c.get()).getNextStartedActivity().getDataString());
        c.pause().stop().destroy();
    }

    @Test
    public void allowingFirst() throws Exception {
        allow(false);
        offered(APP.length, sha256(APP));
        fileIs(Harness.app().getPackageName(), code());                     // the same build number is no step back
        ActivityController<MainActivity> c = start();
        press(popup(), DialogInterface.BUTTON_POSITIVE);
        ScreenshotTest.shot(c.get(), "98_update_allow", popup());
        press(popup(), DialogInterface.BUTTON_POSITIVE);
        Intent setting = Shadows.shadowOf(c.get()).getNextStartedActivity();
        Assert.assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, setting.getAction());
        Assert.assertEquals("package:" + Harness.app().getPackageName(), setting.getDataString());
        Assert.assertEquals(0, loaded);
        c.pause().stop();
        allow(true);
        c.start().resume();
        Harness.settle();
        Assert.assertEquals("goes on after coming back", 1, loaded);
        Assert.assertEquals(1, sessions().size());
        c.pause().stop().destroy();
    }

    @Test
    public void comingBackWithoutAllowingLoadsNothing() throws Exception {
        allow(false);
        offered(APP.length, sha256(APP));
        ActivityController<MainActivity> c = start();
        press(popup(), DialogInterface.BUTTON_POSITIVE);
        press(popup(), DialogInterface.BUTTON_POSITIVE);
        c.pause().stop().start().resume();
        Harness.settle();
        allow(true);
        c.pause().stop().start().resume();
        Harness.settle();
        Assert.assertEquals("only straight after the setting", 0, loaded);
        c.pause().stop().destroy();
    }

    @Test
    public void aRestartWhileAllowingGoesOn() throws Exception {
        allow(true);
        offered(APP.length, sha256(APP));
        fileIs(Harness.app().getPackageName(), code() + 1);
        prefs().edit().putLong("updates_waiting", System.currentTimeMillis() - 60_000)
                .putLong("updates_asked", System.currentTimeMillis()).commit();
        ActivityController<MainActivity> c = start();
        Harness.settle();
        Assert.assertEquals(1, loaded);
        Assert.assertEquals(1, sessions().size());
        c.pause().stop().destroy();
    }

    @Test
    public void onlyTheRightFileIsInstalled() throws Exception {
        allow(true);
        String[] cases = {"checksum", "shorter", "longer", "foreign", "older", "unreadable", "offline"};
        for (String wrong : cases) {
            Harness.reset();
            offered(wrong.equals("shorter") ? APP.length + 1 : wrong.equals("longer") ? APP.length - 1 : APP.length,
                    wrong.equals("checksum") ? sha256(new byte[1]) : sha256(APP));
            fileIs(wrong.equals("foreign") ? "org.somebody.else" : Harness.app().getPackageName(),
                    wrong.equals("older") ? code() - 1 : code() + 1);
            if (wrong.equals("unreadable")) {
                Shadows.shadowOf(Harness.app().getPackageManager())
                        .setPackageArchiveInfo(new java.io.File(Harness.app().getCacheDir(), "update.apk").getAbsolutePath(), null);
            }
            if (wrong.equals("offline")) {
                Installer.source = url -> {
                    throw new IOException("no route");
                };
            }
            SettingsActivity a = settings();
            press(checkIn(a), DialogInterface.BUTTON_POSITIVE);
            Harness.settle();
            Assert.assertTrue(wrong, said("Update nicht geklappt"));
            Assert.assertTrue(wrong, said(wrong.equals("shorter") || wrong.equals("offline") ? "ließ sich nicht herunterladen"
                    : "Nichts wurde installiert"));
            Assert.assertTrue(wrong, sessions().isEmpty());
            Assert.assertFalse(wrong, Installer.busy());
            Assert.assertFalse(wrong, new java.io.File(Harness.app().getCacheDir(), "update.apk").exists());
            popup().dismiss();
        }
    }

    @Test
    public void cancellingAndLeavingTheScreen() throws Exception {
        allow(true);
        offered(APP.length, sha256(APP));
        fileIs(Harness.app().getPackageName(), code() + 1);
        final java.util.concurrent.Semaphore go = new java.util.concurrent.Semaphore(0);
        Installer.Source slow = url -> {
            go.acquireUninterruptibly();
            return new java.io.ByteArrayInputStream(APP);
        };
        Installer.source = slow;
        ActivityController<MainActivity> c = start();
        press(popup(), DialogInterface.BUTTON_POSITIVE);
        Assert.assertTrue(said("Version 99.0 wird geladen"));
        ScreenshotTest.shot(c.get(), "99_update_loading", popup());
        press(popup(), DialogInterface.BUTTON_NEGATIVE);                    // cancel
        go.release();
        Harness.settle();
        Assert.assertFalse(Installer.busy());
        Assert.assertTrue(sessions().isEmpty());
        Assert.assertNull(popup());

        // loaded while the app is not on the screen: Android is asked when it is back
        Updates.check(c.get());
        Harness.settle();
        press(popup(), DialogInterface.BUTTON_POSITIVE);
        c.pause().stop();
        go.release();
        Harness.settle();
        Assert.assertTrue("waits for a screen", Installer.busy());
        Assert.assertTrue(sessions().isEmpty());
        c.start().resume();
        Harness.settle();
        Assert.assertFalse(Installer.busy());
        Assert.assertEquals(1, sessions().size());
        c.pause().stop().destroy();
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
