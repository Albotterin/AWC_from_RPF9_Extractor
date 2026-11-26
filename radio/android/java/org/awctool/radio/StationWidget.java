package org.awctool.radio;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;

/** A tile on the home screen that plays one station with a tap (see Widgets). */
public class StationWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        AppLog.init(context);
        Widgets.update(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        Widgets.update(context);                              // it was resized
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        Widgets.forget(context, ids);
    }
}
