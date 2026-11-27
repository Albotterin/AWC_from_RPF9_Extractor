package org.awctool.radio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Hears from Android's package installer about the update Installer handed to it. Not exported: only Android answers. */
public class InstallReceiver extends BroadcastReceiver {
    static final String EXTRA_PAGE = "page";           // where the browser would get the update from

    @Override
    public void onReceive(Context c, Intent answer) {
        AppLog.init(c);
        Installer.answered(c, answer, answer.getStringExtra(EXTRA_PAGE));
    }
}
