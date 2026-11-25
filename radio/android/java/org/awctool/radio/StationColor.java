package org.awctool.radio;

/**
 * The colour of a station, taken from its logo: the player takes it on while the station is playing. No Android classes
 * (works on ARGB pixels), so that it is tested on the PC.
 */
final class StationColor {
    /** For a station without a logo or with a black-and-white one. */
    static final int PLAIN_HUE = 250;
    private static final int BUCKETS = 24;

    final int tint;          // dark: a background that white text is readable on
    final int light;         // bright: lines and small highlights on the dark app

    private StationColor(float hue, float saturation) {
        this.tint = readableUnderWhite(hue, Math.min(0.62f, saturation));
        this.light = hsl(hue, saturation < 0.3f ? saturation : Math.max(0.75f, saturation), 0.72f);
    }

    static StationColor plain() {
        return new StationColor(PLAIN_HUE, 0.36f);
    }

    /** The strongest colour of the picture (large and saturated areas count most; white, black and grey do not). */
    static StationColor of(int[] pixels) {
        double[] weight = new double[BUCKETS], r = new double[BUCKETS], g = new double[BUCKETS], b = new double[BUCKETS];
        int seen = 0;
        for (int p : pixels) {
            if ((p >>> 24) < 200) continue;
            seen++;
            float[] c = toHsl(p);
            double chroma = c[1] * (1 - Math.abs(2 * c[2] - 1));
            if (chroma < 0.22) continue;
            int k = (int) (c[0] / 360f * BUCKETS) % BUCKETS;
            double w = chroma * chroma;
            if (c[0] >= 22 && c[0] < 70) w *= 0.55;                // yellow and orange turn brown as a dark background
            weight[k] += w;
            r[k] += w * ((p >> 16) & 255);
            g[k] += w * ((p >> 8) & 255);
            b[k] += w * (p & 255);
        }
        int best = -1;
        double bestSum = 0, total = 0;
        for (int k = 0; k < BUCKETS; k++) {
            double sum = weight[k] + 0.6 * (weight[(k + 1) % BUCKETS] + weight[(k + BUCKETS - 1) % BUCKETS]);
            total += weight[k];
            if (weight[k] > 0 && sum > bestSum) {
                best = k;
                bestSum = sum;
            }
        }
        if (best < 0 || total < 0.012 * seen) return plain();
        int mean = 0xFF000000 | (int) (r[best] / weight[best]) << 16 | (int) (g[best] / weight[best]) << 8 | (int) (b[best] / weight[best]);
        float[] c = toHsl(mean);
        return new StationColor(c[0], c[1]);
    }

    /** Yellow and green are much brighter than blue at the same lightness: darken until white text is easy to read. */
    private static int readableUnderWhite(float hue, float saturation) {
        for (float l = 0.30f; l > 0.08f; l -= 0.01f) {
            int c = hsl(hue, saturation, l);
            if (contrast(0xFFFFFFFF, c) >= 8.0) return c;
        }
        return hsl(hue, saturation, 0.08f);
    }

    static double contrast(int a, int b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    private static double luminance(int c) {
        return 0.2126 * linear((c >> 16) & 255) + 0.7152 * linear((c >> 8) & 255) + 0.0722 * linear(c & 255);
    }

    private static double linear(int v) {
        double s = v / 255.0;
        return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    /** {hue 0..360, saturation 0..1, lightness 0..1} */
    static float[] toHsl(int color) {
        float r = ((color >> 16) & 255) / 255f, g = ((color >> 8) & 255) / 255f, b = (color & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float l = (max + min) / 2, h = 0, s = 0;
        if (d > 0) {
            s = d / (1 - Math.abs(2 * l - 1));
            if (max == r) h = 60 * (((g - b) / d) % 6);
            else if (max == g) h = 60 * ((b - r) / d + 2);
            else h = 60 * ((r - g) / d + 4);
            if (h < 0) h += 360;
        }
        return new float[]{h, Math.min(1f, s), l};
    }

    static int hsl(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s;
        float x = c * (1 - Math.abs((h / 60f) % 2 - 1));
        float m = l - c / 2;
        float r = 0, g = 0, b = 0;
        if (h < 60) {
            r = c;
            g = x;
        } else if (h < 120) {
            r = x;
            g = c;
        } else if (h < 180) {
            g = c;
            b = x;
        } else if (h < 240) {
            g = x;
            b = c;
        } else if (h < 300) {
            r = x;
            b = c;
        } else {
            r = c;
            b = x;
        }
        return 0xFF000000 | Math.round((r + m) * 255) << 16 | Math.round((g + m) * 255) << 8 | Math.round((b + m) * 255);
    }
}
