package org.awctool.radio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

/** Station logos from the packs; a coloured square with the initials where a station has none (like the PC version). */
final class Logos {
    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    private static final Map<String, StationColor> COLORS = new HashMap<>();

    private Logos() {
    }

    static synchronized Bitmap get(Library.Station st, int size) {
        Pack pack = st.pack;
        String key = st.name + "@" + size + "@" + System.identityHashCode(pack);
        Bitmap b = CACHE.get(key);
        if (b != null) return b;
        if (pack != null && !st.logo.isEmpty()) {
            try {
                byte[] png = pack.read(st.logo);
                Bitmap raw = BitmapFactory.decodeByteArray(png, 0, png.length);
                if (raw != null) b = raw.getWidth() == size ? raw : Bitmap.createScaledBitmap(raw, size, size, true);
            } catch (Exception ignored) {
            }
        }
        if (b == null) b = initials(st.name, size);
        CACHE.put(key, b);
        return b;
    }

    static synchronized void clear() {
        CACHE.clear();
        COLORS.clear();
    }

    /** The colour of the station's logo (or of the drawn one, for a station without). */
    static synchronized StationColor color(Library.Station st) {
        String key = st.name + "@" + System.identityHashCode(st.pack);
        StationColor c = COLORS.get(key);
        if (c != null) return c;
        Bitmap small = Bitmap.createScaledBitmap(get(st, 256), 48, 48, true);
        int[] pixels = new int[48 * 48];
        small.getPixels(pixels, 0, 48, 0, 0, 48, 48);
        c = StationColor.of(pixels);
        COLORS.put(key, c);
        return c;
    }

    private static Bitmap initials(String name, int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        CRC32 crc = new CRC32();
        crc.update(name.getBytes());
        float hue = crc.getValue() % 360;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // an evening sky in the station's own hue, the initials in the lettering of the app
        p.setShader(new LinearGradient(0, 0, 0, size, StationColor.hsl(hue, 0.55f, 0.30f),
                StationColor.hsl((hue + 50) % 360, 0.65f, 0.52f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, p);
        p.setShader(null);
        StringBuilder sb = new StringBuilder();
        for (String w : name.replace("-", " ").replace("(", " ").split(" ")) {
            if (!w.isEmpty() && Character.isLetterOrDigit(w.charAt(0)) && sb.length() < 3) sb.append(Character.toUpperCase(w.charAt(0)));
        }
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Ui.displayFont != null ? Ui.displayFont : Typeface.DEFAULT);
        p.setFakeBoldText(true);                       // the display face is very thin
        p.setTextSize(size / 2.7f);
        c.drawText(sb.toString(), size / 2f, size / 2f + size / 8f, p);
        return bmp;
    }
}
