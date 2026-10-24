package dev.vidultra.proto;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Everything the user can change, persisted. Options that need a new camera / encoder setup
 * (resolution, aspect, frame rate, bitrate, codec) take effect through a pipeline restart;
 * the rest apply live.
 */
final class Settings {
    static final String[] RES_NAMES = {"4K", "2.7K", "1080p", "720p"};
    static final int[] RES_W = {3840, 2704, 1920, 1280};   // 16:9 width
    static final int[] RES_H = {2160, 1520, 1080, 720};
    static final String[] ASPECT_NAMES = {"16:9", "17:9", "2:1", "2.39:1", "4:3", "1:1"};
    static final int[] FPS = {24, 25, 30};
    static final String[] BITRATE_NAMES = {"Light", "Standard", "High"};
    static final double[] BITS_PER_PIXEL = {0.25, 0.5, 0.75};   // per frame; Standard = 100 Mbps at 4K24
    static final String[] CODEC_NAMES = {"Auto", "HEVC 10-bit", "H.264 8-bit"};
    static final String[] GRAIN_NAMES = {"Natural", "Fine", "Clean"};
    static final float[] GRAIN = {0.35f, 0.2f, 0.08f};
    static final String[] FLICKER_NAMES = {"50 Hz", "60 Hz"};

    // restart-level
    int res, aspect, fps, bitrate = 1, codec;
    // live
    boolean log, nr = true, eis = true, tone = true, filmShutter = true, live, monitor709 = true, grid,
            histogram = true, audioMeter = true;
    int logProfile, gamut = -1, grain, flicker;
    String camera = "";   // lens (camera id); "" = automatic (the main back camera)

    private final SharedPreferences prefs;

    Settings(Context c) {
        prefs = c.getSharedPreferences("vidultra", Context.MODE_PRIVATE);
        res = clamp(prefs.getInt("res", 0), RES_NAMES.length);
        aspect = clamp(prefs.getInt("aspect", 0), ASPECT_NAMES.length);
        fps = clamp(prefs.getInt("fps", 0), FPS.length);
        bitrate = clamp(prefs.getInt("bitrate", 1), BITRATE_NAMES.length);
        codec = clamp(prefs.getInt("codec", 0), CODEC_NAMES.length);
        log = prefs.getBoolean("log", false);
        nr = prefs.getBoolean("nr", true);
        eis = prefs.getBoolean("eis", true);
        tone = prefs.getBoolean("tone", true);
        filmShutter = prefs.getBoolean("film", true);
        live = prefs.getBoolean("live", false);
        monitor709 = prefs.getBoolean("monitor709", true);
        grid = prefs.getBoolean("grid", false);
        histogram = prefs.getBoolean("histogram", true);
        audioMeter = prefs.getBoolean("audioMeter", true);
        logProfile = clamp(prefs.getInt("logProfile", 0), ColorSpaces.LOG_PROFILES.length);
        gamut = Math.max(-1, Math.min(ColorSpaces.GAMUTS.length - 1, prefs.getInt("gamut", -1)));
        grain = clamp(prefs.getInt("grain", 0), GRAIN.length);
        flicker = clamp(prefs.getInt("flicker", defaultFlicker()), FLICKER_NAMES.length);
        camera = prefs.getString("camera", "");
    }

    void save() {
        prefs.edit().putInt("res", res).putInt("aspect", aspect).putInt("fps", fps).putInt("bitrate", bitrate)
                .putInt("codec", codec).putBoolean("log", log).putBoolean("nr", nr).putBoolean("eis", eis)
                .putBoolean("tone", tone).putBoolean("film", filmShutter).putBoolean("live", live)
                .putBoolean("monitor709", monitor709).putBoolean("grid", grid)
                .putBoolean("histogram", histogram).putBoolean("audioMeter", audioMeter).putInt("logProfile", logProfile)
                .putInt("gamut", gamut).putInt("grain", grain).putInt("flicker", flicker)
                .putString("camera", camera).apply();
    }

    private static int clamp(int v, int n) {
        return Math.max(0, Math.min(n - 1, v));
    }

    /** 60 Hz mains in the Americas, Taiwan, Philippines, Korea, Saudi Arabia; 50 Hz elsewhere. */
    private static int defaultFlicker() {
        String c = java.util.Locale.getDefault().getCountry();
        String sixty = "US CA MX BR CO VE PE EC TW PH KR SA CU DO GT HN NI PA SV CR PR JM BS BZ BO";
        return !c.isEmpty() && sixty.contains(c) ? 1 : 0;
    }

    /** Output size for a resolution and aspect: width x height (width a multiple of 8, height even). */
    static int[] size(int res, int aspect) {
        int w = RES_W[res], h = RES_H[res];
        switch (aspect) {
            case 1: w = Math.round(h * 256f / 135f); break;   // 17:9 DCI
            case 2: h = Math.round(w / 2f); break;
            case 3: h = Math.round(w / 2.39f); break;
            case 4: w = Math.round(h * 4f / 3f); break;
            case 5: w = h; break;
            default: break;
        }
        return new int[]{w & ~7, h & ~1};   // 1080 stays 1080 (encoders pad internally)
    }

    int[] size() {
        return size(res, aspect);
    }

    int fpsValue() {
        return FPS[fps];
    }

    ColorSpaces.Profile profile() {
        return ColorSpaces.LOG_PROFILES[logProfile];
    }

    /** Gamut actually recorded in log: the profile's own unless overridden. */
    int gamutIndex() {
        return gamut >= 0 ? gamut : profile().gamut;
    }

    /** Short label for the viewfinder pill: "Rec.709" or e.g. "S-Log3". */
    String lookLabel() {
        return log ? profile().shortName : "Rec.709";
    }
}
