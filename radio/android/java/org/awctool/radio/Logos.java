package org.awctool.radio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

/** Station logos from the pack; a coloured square with the initials where a station has none (like the PC version). */
final class Logos {
    private static final Map<String, Bitmap> CACHE = new HashMap<>();

    private Logos() {
    }

    static synchronized Bitmap get(Pack pack, Library.Station st, int size) {
        String key = st.name + "@" + size;
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
    }

    private static Bitmap initials(String name, int size) {
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        CRC32 crc = new CRC32();
        crc.update(name.getBytes());
        long h = crc.getValue();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(60 + (int) (h & 0x7F), 60 + (int) ((h >> 8) & 0x7F), 80 + (int) ((h >> 16) & 0x7F)));
        c.drawRect(0, 0, size, size, p);
        StringBuilder sb = new StringBuilder();
        for (String w : name.replace("-", " ").split(" ")) {
            if (!w.isEmpty() && sb.length() < 3) sb.append(Character.toUpperCase(w.charAt(0)));
        }
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(size / 3f);
        c.drawText(sb.toString(), size / 2f, size / 2f + size / 9f, p);
        return bmp;
    }
}
