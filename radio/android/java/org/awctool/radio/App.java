package org.awctool.radio;

import android.app.Application;

/** Starts the log and writes crashes into it before the app dies. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.init(this);
        AppLog.d("--- app started, version " + version() + ", Android " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + "), " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            AppLog.e("CRASH in thread " + thread.getName(), ex);
            if (previous != null) previous.uncaughtException(thread, ex);
        });
    }

    String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
