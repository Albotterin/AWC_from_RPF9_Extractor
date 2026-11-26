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
        if (b == null) b = st.score != null ? scoreCover(st.score, size) : initials(st.name, size);
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

    /** A group of the score: the bars of a level meter on an evening sky in the group's hue. */
    private static Bitmap scoreCover(String group, int size) {
        float hue = "openworld".equals(group) ? 165 : "mission".equals(group) ? 225 : "compound".equals(group) ? 12 : "places".equals(group) ? 48 : 285;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, size, StationColor.hsl(hue, 0.60f, 0.24f),
                StationColor.hsl((hue + 35) % 360, 0.68f, 0.48f), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, p);
        p.setShader(null);
        p.setColor(Color.WHITE);
        float[] bars = {0.22f, 0.44f, 0.64f, 0.36f, 0.52f, 0.28f};
        float width = size * 0.07f, gap = size * 0.045f;
        float x = (size - bars.length * width - (bars.length - 1) * gap) / 2f;
        for (float h : bars) {
            float half = size * h / 2f;
            c.drawRoundRect(x, size / 2f - half, x + width, size / 2f + half, width / 2f, width / 2f, p);
            x += width + gap;
        }
        return bmp;
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
