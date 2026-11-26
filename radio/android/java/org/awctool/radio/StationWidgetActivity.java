package org.awctool.radio;

import android.app.Activity;
import android.app.Dialog;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import java.util.List;

/** Which station a tile on the home screen stands for: asked when it is put there, and again when it is changed. */
public class StationWidgetActivity extends Activity {
    private Dialog sheet;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppLog.init(this);
        final int id = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        final Intent result = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        setResult(RESULT_CANCELED, result);                   // going back adds no tile
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }
        PackStore.whenLoaded(this, () -> {
            if (isFinishing() || isDestroyed()) return;
            List<Library.Station> stations = PackStore.get(this).stations;
            if (stations.isEmpty()) {
                Toast.makeText(this, R.string.widget_no_stations, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
            sheet = Ui.stationSheet(this, getString(R.string.widget_choose), stations, Widgets.stationOf(this, id), null, name -> {
                int at = Catalog.indexOf(stations, name);
                if (at < 0) return;
                Widgets.choose(this, id, stations.get(at));
                setResult(RESULT_OK, result);
            });
            sheet.setOnDismissListener(d -> finish());
        });
    }

    @Override
    protected void onDestroy() {
        if (sheet != null && sheet.isShowing()) {
            sheet.setOnDismissListener(null);
            sheet.dismiss();
        }
        super.onDestroy();
    }
}
