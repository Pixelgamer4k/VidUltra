package dev.vidultra.proto;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Diagnostics for support: everything this app's process logs (its own messages and the
 * system's camera / codec / GPU messages on its behalf) is kept in a rolling file, so it survives
 * a crash. Java crashes add their stack trace. If the previous run did not end cleanly (killed,
 * or a native crash) its last lines are included as such. {@link #report} builds the text the
 * user copies or shares, scrubbed of anything personal.
 */
final class DiagLog {
    private static final String TAG = "VIDULTRA";
    private static final int KEEP_LINES = 3000;
    private static final long ROTATE_BYTES = 768 * 1024;

    private static final ArrayDeque<String> ring = new ArrayDeque<>();
    private static File dir;
    private static boolean started;
    private static String previousSession;   // tail of a run that did not end cleanly
    private static FileWriter out;
    private static long written;

    private DiagLog() {}

    static synchronized void start(Context c) {
        if (started) return;
        started = true;
        dir = new File(c.getFilesDir(), "diag");
        dir.mkdirs();
        File running = new File(dir, "running");
        File session = new File(dir, "session.log");
        if (running.exists() && session.exists()) {
            previousSession = tail(session, 400);
        }
        try {
            File prev = new File(dir, "previous.log");
            if (session.exists()) {
                prev.delete();
                session.renameTo(prev);
            }
            running.createNewFile();
            out = new FileWriter(session, false);
        } catch (IOException e) {
            Log.w(TAG, "diag log: " + e);
        }
        Thread.UncaughtExceptionHandler chain = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                ex.printStackTrace(new PrintWriter(sw));
                String crash = "CRASH in thread \"" + t.getName() + "\" at " + now() + "\n" + sw;
                Files.write(new File(dir, "last_crash.txt").toPath(), crash.getBytes());
                line(crash);
            } catch (Throwable ignored) {
            }
            if (chain != null) chain.uncaughtException(t, ex);
        });
        Thread reader = new Thread(DiagLog::readLogcat, "diag-log");
        reader.setDaemon(true);
        reader.start();
    }

    /** In the foreground again: dying from here on counts as unexpected. */
    static synchronized void markRunning() {
        if (dir == null) return;
        try {
            new File(dir, "running").createNewFile();
        } catch (IOException ignored) {
        }
    }

    /**
     * Backgrounded with the camera released, or closing: Android may kill the process from here
     * without it being a fault, so the next start will not report this run as unexpected.
     */
    static synchronized void cleanExit() {
        if (dir != null) new File(dir, "running").delete();
        try {
            if (out != null) out.flush();
        } catch (IOException ignored) {
        }
    }

    /** Follows this process's own log (apps may read only their own). */
    private static void readLogcat() {
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "time",
                    "--pid=" + Process.myPid(), "*:I"});
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String s;
                while ((s = r.readLine()) != null) line(s);
            }
        } catch (IOException e) {
            line("diag: logcat unavailable: " + e);
        }
    }

    private static synchronized void line(String s) {
        ring.addLast(s);
        while (ring.size() > KEEP_LINES) ring.removeFirst();
        try {
            if (out != null) {
                out.write(s);
                out.write('\n');
                written += s.length() + 1;
                if (written > ROTATE_BYTES) {
                    // keep the file small: restart it from the in-memory tail
                    out.close();
                    out = new FileWriter(new File(dir, "session.log"), false);
                    written = 0;
                    int skip = Math.max(0, ring.size() - KEEP_LINES / 3), k = 0;
                    for (String x : ring) if (k++ >= skip) {
                        out.write(x);
                        out.write('\n');
                        written += x.length() + 1;
                    }
                }
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static String tail(File f, int lines) {
        try {
            java.util.List<String> all = Files.readAllLines(f.toPath());
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return null;
        }
    }

    private static String now() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new java.util.Date());
    }

    /**
     * The support report: a header (app, phone, Android, camera / encoder setup, settings,
     * heat, battery, storage), the last crash, the previous run if it ended unexpectedly, and
     * this run's log - all scrubbed of personal data.
     */
    static synchronized String report(Context c, Settings st, DeviceSetup setup) {
        StringBuilder b = new StringBuilder();
        b.append("=== VidUltra diagnostic log ===\n");
        b.append("Created: ").append(now()).append('\n');
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            b.append("App: ").append(pi.versionName).append(" (").append(pi.getLongVersionCode()).append(")\n");
        } catch (Exception ignored) {
        }
        b.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(", ").append(Build.SOC_MODEL).append(")\n");
        b.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        if (setup != null) {
            b.append(String.format(Locale.US, "Camera: id %s%s, RAW %s %dx%d, %d fps -> %dx%d %s %s, %d Mbps\n",
                    setup.cameraId, setup.front ? " (front)" : "", DeviceSetup.fmtName(setup.rawFormat),
                    setup.rawSize.getWidth(), setup.rawSize.getHeight(), setup.fps, setup.outW, setup.outH,
                    setup.encoderName, setup.tenBit ? "10-bit" : "8-bit", Math.round(setup.bitrate / 1e6)));
        }
        if (st != null) {
            int[] sz = st.size();
            b.append(String.format(Locale.US,
                    "Settings: %s, %dx%d %d fps, %s bitrate, codec %s, NR %s grain %s, tone %s, stab %s, "
                            + "180deg %s, flicker %s, live %s, lens %s\n",
                    st.log ? st.profile().name + " / " + ColorSpaces.GAMUTS[st.gamutIndex()].name : "Rec.709",
                    sz[0], sz[1], st.fpsValue(), Settings.BITRATE_NAMES[st.bitrate], Settings.CODEC_NAMES[st.codec],
                    st.nr, Settings.GRAIN_NAMES[st.grain], st.tone, st.eis, st.filmShutter,
                    Settings.FLICKER_NAMES[st.flicker], st.live, st.camera.isEmpty() ? "auto" : st.camera));
        }
        try {
            android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
            android.os.BatteryManager bm = c.getSystemService(android.os.BatteryManager.class);
            long free = new android.os.StatFs(c.getFilesDir().getPath()).getAvailableBytes();
            b.append(String.format(Locale.US, "State: heat %s, battery %d%%%s, free storage ~%.1f GB\n",
                    pm == null ? "?" : thermalName(pm.getCurrentThermalStatus()),
                    bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY),
                    bm != null && bm.isCharging() ? " (charging)" : "", free / 1e9));
        } catch (Exception ignored) {
        }
        File crash = dir == null ? null : new File(dir, "last_crash.txt");
        if (crash != null && crash.exists()
                && System.currentTimeMillis() - crash.lastModified() > 14L * 24 * 3600 * 1000) {
            crash.delete();   // old news
        }
        if (crash != null && crash.exists()) {
            b.append("\n=== Last crash ===\n").append(tail(crash, 120)).append('\n');
        }
        if (previousSession != null) {
            b.append("\n=== Previous run (ended unexpectedly) - last lines ===\n").append(previousSession).append('\n');
        }
        b.append("\n=== This run ===\n");
        // newest lines, up to ~200 KB so it fits a clipboard, a share sheet and a chat message
        java.util.ArrayList<String> lines = new java.util.ArrayList<>(ring);
        int from = lines.size(), size = 0;
        while (from > 0 && size + lines.get(from - 1).length() < 200_000) size += lines.get(--from).length() + 1;
        if (from > 0) b.append("(").append(from).append(" earlier lines omitted)\n");
        for (int k = from; k < lines.size(); k++) b.append(lines.get(k)).append('\n');
        return redact(b.toString());
    }

    private static String thermalName(int s) {
        String[] n = {"none", "light", "moderate", "severe", "critical", "emergency", "shutdown"};
        return s >= 0 && s < n.length ? n[s] : String.valueOf(s);
    }

    // anything that could identify a person or device, removed before the text leaves the app
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern MAC = Pattern.compile("\\b([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b");
    private static final Pattern IPV4 = Pattern.compile("(?<![\\w.])\\d{1,3}(\\.\\d{1,3}){3}(?![\\w.])");
    private static final Pattern PHONE = Pattern.compile("\\+\\d{1,3}[ -]?\\d[\\d -]{7,}\\d");
    // shared storage paths and media URIs carry folder and video names
    private static final Pattern PATH = Pattern.compile("(/storage/|/sdcard/|/mnt/media_rw/)[^\\s,;)\"']*");
    private static final Pattern VIDEO = Pattern.compile("\\bVU_[^\\s,;)\"']*");
    private static final Pattern URI = Pattern.compile("content://[^\\s,;)\"']*");
    private static final Pattern SERIAL = Pattern.compile("(?i)(serial|imei|meid|android_?id|ssid|account)[=: ]+\\S+");

    static String redact(String s) {
        s = EMAIL.matcher(s).replaceAll("[email]");
        s = MAC.matcher(s).replaceAll("[mac]");
        s = IPV4.matcher(s).replaceAll("[ip]");
        s = SERIAL.matcher(s).replaceAll("$1=[removed]");
        s = PHONE.matcher(s).replaceAll("[number]");
        s = PATH.matcher(s).replaceAll("$1[file]");
        s = URI.matcher(s).replaceAll("content://[media]");
        s = VIDEO.matcher(s).replaceAll("[video]");
        return s;
    }
}
