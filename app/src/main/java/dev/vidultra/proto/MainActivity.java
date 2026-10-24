package dev.vidultra.proto;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.util.Size;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Fullscreen viewfinder ({@link CameraUi}) with the settings sheet ({@link SettingsSheet}).
 * Settings apply live where the pipeline allows; format changes (resolution, aspect, frame
 * rate, bitrate, codec) restart the activity, which rebuilds the camera and encoder setup.
 * Recording can also be driven from adb:
 * {@code am broadcast -a dev.vidultra.proto.RECORD -p dev.vidultra.proto --ez on true}.
 */
public class MainActivity extends Activity {
    static final String ACTION_RECORD = "dev.vidultra.proto.RECORD";
    static final String ACTION_VIEW = "dev.vidultra.proto.VIEW";
    /** extra "src": cm | isp | fm */
    static final String ACTION_MATRIX = "dev.vidultra.proto.MATRIX";

    private Engine engine;
    private Settings settings;
    private boolean switching;            // a camera restart is under way (UI shows the spinner)
    private boolean permitted;
    private SurfaceView view;
    private android.view.Surface surface;
    private int surfaceW, surfaceH, padL, padR, padB;
    /** Camera teardown / bring-up happens here, off the UI thread, strictly in order. */
    private final java.util.concurrent.ExecutorService lifecycle = java.util.concurrent.Executors.newSingleThreadExecutor();
    private CameraUi ui;
    private SettingsSheet sheet;
    private DeviceSetup setup;
    private android.net.Uri lastVideo;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (ACTION_VIEW.equals(i.getAction())) engine.togglePreview709();
            else if (ACTION_MATRIX.equals(i.getAction())) engine.setMatrixSource(i.getStringExtra("src"));
            else if (i.hasExtra("on")) engine.setRecording(i.getBooleanExtra("on", false));
            else engine.toggleRecording();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DiagLog.start(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setDecorFitsSystemWindows(false);

        settings = new Settings(this);
        applySettingsToPipeline();
        lenses = DeviceSetup.lenses(getSystemService(android.hardware.camera2.CameraManager.class));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        view = new SurfaceView(this);
        root.addView(view);
        ui = new CameraUi(this, uiCallbacks);
        root.addView(ui);
        sheet = new SettingsSheet(this, settings, sheetListener);
        root.addView(sheet);
        setContentView(root);
        hideSystemBars();
        if (getIntent().getBooleanExtra("diagdump", false)) {
            // testing (adb only): the report exactly as Copy would give it, in the app's own folder
            ui.postDelayed(() -> {
                try {
                    java.nio.file.Files.write(new java.io.File(getExternalFilesDir(null), "diag_report.txt").toPath(),
                            DiagLog.report(this, settings, setup).getBytes());
                } catch (java.io.IOException ignored) {
                }
            }, 8000);
        }
        reflectSettingsInUi();
        ui.setSwitching(formatLabel());
        switching = true;
        engine = new Engine(this);
        engine.setListener(engineListener);
        view.getHolder().setFormat(Engine.eightBit ? PixelFormat.RGBA_8888 : PixelFormat.RGBA_1010102);
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
                surface = h.getSurface();
                surfaceW = w;
                surfaceH = hgt;
                if (engine != null) engine.setPreviewSurface(surface, w, hgt);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                surface = null;
                if (engine != null) engine.setPreviewSurface(null, 0, 0);
            }
        });
        IntentFilter filter = new IntentFilter(ACTION_RECORD);
        filter.addAction(ACTION_VIEW);
        filter.addAction(ACTION_MATRIX);
        registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);

        statusTick.run();
        android.net.Uri newest = newestVideo();
        if (newest != null) loadThumbnail(newest);

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            permitted = true;
            openCamera(engine);
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, 1);
        }
    }

    private String formatLabel() {
        if (lensLabel != null) {
            String l = lensLabel;
            lensLabel = null;
            return l;
        }
        int[] sz = settings.size();
        return resLabel(sz[1]) + " · " + settings.fpsValue();
    }

    private java.util.List<DeviceSetup.Lens> lenses = new java.util.ArrayList<>();
    private String lensLabel;

    private final Engine.Listener engineListener = new Engine.Listener() {
            @Override
            public void onRecordingChanged(boolean recording) {
                runOnUiThread(() -> {
                    ui.setRecording(recording);
                    sheet.setRecording(recording);
                });
            }

            @Override
            public void onSaved(android.net.Uri uri) {
                lastVideo = uri;
                loadThumbnail(uri);
            }

            @Override
            public void onSetup(DeviceSetup s) {
                runOnUiThread(() -> {
                    setup = s;
                    sheet.setCapabilities(s);
                    ui.setLenses(lenses, s.cameraId);
                    reflectSettingsInUi();

                });
            }

            @Override
            public void onFirstFrame() {
                // pictures are on screen: lift the loading cover
                runOnUiThread(() -> {
                    switching = false;
                    ui.setSwitching(null);
                });
            }

            @Override
            public void onLockChanged(boolean locked) {
                runOnUiThread(() -> {
                    ui.setLocked(locked);
                    if (locked) ui.showHud("AE/AF Lock");
                });
            }

            @Override
            public void onReadout(Engine.Readout r) {
                runOnUiThread(() -> ui.setReadout(r));
            }

            @Override
            public void onFocusCleared() {
                runOnUiThread(() -> {
                    ui.hideFocus();
                    ui.setLocked(false);
                });
            }
        };

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.hide(WindowInsets.Type.systemBars());
        c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    }

    // ------------------------------------------------------------------ settings

    /** Settings -> pipeline switches (before the engine exists; launch extras may override). */
    private void applySettingsToPipeline() {
        int[] sz = settings.size();
        DeviceSetup.targetW = sz[0];
        DeviceSetup.targetH = sz[1];
        DeviceSetup.targetFps = settings.fpsValue();
        DeviceSetup.bitsPerPixel = Settings.BITS_PER_PIXEL[settings.bitrate];
        DeviceSetup.codecPref = settings.codec;
        Engine.eightBit = settings.codec == 2 || (settings.codec == 0 && !DeviceSetup.deviceHasHevc10());
        Engine.recordLog = settings.log;
        ColorSpaces.Profile pr = settings.profile();
        Engine.logCurve = pr.curve;
        Engine.logGamut = settings.gamutIndex();
        Engine.logTag = pr.fileTag + "_" + ColorSpaces.GAMUTS[settings.gamutIndex()].name.replaceAll("[^A-Za-z0-9.]", "");
        Engine.monitor709 = settings.monitor709;
        Engine.autoTone = settings.tone;
        Engine.nr = settings.nr;
        Engine.eis = settings.eis;
        Engine.filmShutter = settings.filmShutter;
        Engine.flickerHz = settings.flicker == 1 ? 60 : 50;
        NoiseReducer.grain = Settings.GRAIN[settings.grain];
    }

    private void reflectSettingsInUi() {
        ui.setLive(settings.live);
        ui.setStab(settings.eis);
        ui.setNr(settings.nr);
        ui.setTone(settings.tone);
        ui.setGrid(settings.grid);
        ui.setHistogram(settings.histogram);
        ui.setAudioMeter(settings.audioMeter);
        ui.setLook(settings.lookLabel());
        int w = setup != null ? setup.outW : settings.size()[0], h = setup != null ? setup.outH : settings.size()[1];
        int fps = setup != null ? setup.fps : settings.fpsValue();
        ui.setFormat(resLabel(h), fps, w, h);
    }

    private static String resLabel(int h) {
        for (int k = 0; k < Settings.RES_H.length; k++) if (Math.abs(Settings.RES_H[k] - h) < 60) return Settings.RES_NAMES[k];
        return h + "p";
    }

    private final SettingsSheet.Listener sheetListener = new SettingsSheet.Listener() {
        @Override
        public void onChanged(String key) {
            for (String k : SettingsSheet.RESTART_KEYS) {
                if (k.equals(key)) {
                    restartPipeline();
                    return;
                }
            }
            applyLive(key);
        }

        @Override
        public void onClosed() {
        }

        @Override
        public void onDiagnostics(boolean share) {
            String text = DiagLog.report(MainActivity.this, settings, setup);
            if (share) {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_SUBJECT, "VidUltra diagnostic log");
                send.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(send, "Share diagnostic log"));
            } else {
                android.content.ClipboardManager cb = getSystemService(android.content.ClipboardManager.class);
                android.content.ClipData clip = android.content.ClipData.newPlainText("VidUltra diagnostic log", text);
                cb.setPrimaryClip(clip);
                // Android 13+ confirms a copy itself
                if (android.os.Build.VERSION.SDK_INT < 33) {
                    android.widget.Toast.makeText(MainActivity.this, "Log copied", android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    /** A setting that applies without restarting. */
    private void applyLive(String key) {
        switch (key) {
            case "look":
                engine.setOutput(settings.log, settings.profile(), settings.gamutIndex());
                break;
            case "monitor709":
                engine.setPreview709(settings.monitor709);
                break;
            case "tone":
                Engine.autoTone = settings.tone;
                break;
            case "nr":
                Engine.nr = settings.nr;
                break;
            case "grain":
                NoiseReducer.grain = Settings.GRAIN[settings.grain];
                break;
            case "eis":
                engine.setStabilization(settings.eis);
                break;
            case "film":
                engine.setFilmShutter(settings.filmShutter);
                break;
            case "flicker":
                engine.setFlicker(settings.flicker == 1 ? 60 : 50);
                break;
            case "live":
                engine.setRawPreview(settings.live);
                break;
            case "grid":
            case "histogram":
            case "audioMeter":
                break;   // reflectSettingsInUi below
            default:
                break;
        }
        reflectSettingsInUi();
    }

    /**
     * New camera / encoder setup, in place: the UI stays, the picture area shows a spinner while
     * the old camera pipeline is released (off the UI thread) and a new one is brought up.
     */
    private void restartPipeline() {
        if (switching || engine == null || engine.isRecording()) return;
        settings.save();
        switching = true;
        ui.setSwitching(formatLabel());
        Engine old = engine;
        lifecycle.execute(() -> {
            old.release(2500);
            runOnUiThread(this::startEngine);
        });
    }

    /** A fresh engine on the existing preview surface (settings re-applied). */
    private void startEngine() {
        if (isDestroyed()) return;
        applySettingsToPipeline();
        engine = new Engine(this);
        engine.setListener(engineListener);
        engine.setPreviewPadding(padL, padR, padB);
        int fmt = Engine.eightBit ? PixelFormat.RGBA_8888 : PixelFormat.RGBA_1010102;
        view.getHolder().setFormat(fmt);   // a format change re-delivers surfaceChanged
        if (surface != null) engine.setPreviewSurface(surface, surfaceW, surfaceH);
        if (permitted) openCamera(engine);
    }

    private final CameraUi.Callbacks uiCallbacks = new CameraUi.Callbacks() {
        @Override
        public void onShutter() {
            if (!engine.isRecording() && storageMinutes >= 0 && storageMinutes < 1) {
                ui.showHud("Storage Full");
                return;
            }
            engine.setRecording(!engine.isRecording());
        }

        @Override
        public void onGallery() {
            openGallery();
        }

        @Override
        public void onSettings() {
            sheet.open(ui.getRootWindowInsets() == null ? 0
                    : ui.getRootWindowInsets().getInsets(WindowInsets.Type.displayCutout()).right);
        }

        @Override
        public void onLiveView(boolean on) {
            settings.live = on;
            settings.save();
            engine.setRawPreview(on);
            ui.showHud(on ? "Live View On" : "Live View Off");
        }

        @Override
        public void onLock(boolean on) {
            engine.setAeAfLock(on);
            if (!on) ui.showHud("AE/AF Unlocked");
        }

        @Override
        public void onStabilization(boolean on) {
            settings.eis = on;
            settings.save();
            engine.setStabilization(on);
            ui.showHud(on ? "Stabilization On" : "Stabilization Off");
        }

        @Override
        public void onNoiseReduction(boolean on) {
            settings.nr = on;
            settings.save();
            Engine.nr = on;
            ui.showHud(on ? "Noise Reduction On" : "Noise Reduction Off");
        }

        @Override
        public void onToneMapping(boolean on) {
            settings.tone = on;
            settings.save();
            Engine.autoTone = on;
            ui.showHud(on ? "Dynamic Tone Mapping On" : "Dynamic Tone Mapping Off");
        }

        @Override
        public void onLookToggle() {
            settings.log = !settings.log;
            settings.save();
            applyLive("look");
            ui.showHud(settings.log ? settings.profile().name + " · " + ColorSpaces.GAMUTS[settings.gamutIndex()].name : "Rec.709");
        }

        @Override
        public void onResolutionCycle() {
            int next = nextSupported(settings.res, setup != null ? setup.resOk : null);
            if (next == settings.res) return;
            settings.res = next;
            restartPipeline();
        }

        @Override
        public void onFpsCycle() {
            int next = nextSupported(settings.fps, setup != null ? setup.fpsOk : null);
            if (next == settings.fps) return;
            settings.fps = next;
            restartPipeline();
        }

        @Override
        public void onFocus(float nx, float ny, boolean lock) {
            engine.focusAt(nx, ny, lock);
        }

        @Override
        public void onExposureBias(float ev) {
            engine.setEvBias(ev);
        }

        @Override
        public void onLens(String id) {
            if (engine == null || engine.isRecording()) return;
            settings.camera = id;
            for (DeviceSetup.Lens l : lenses) {
                if (l.id.equals(id)) lensLabel = l.name + " · " + l.label();
            }
            restartPipeline();
        }

        @Override
        public void onPadding(int left, int right, int bottom) {
            padL = left;
            padR = right;
            padB = bottom;
            if (engine != null) engine.setPreviewPadding(left, right, bottom);
        }
    };

    private static int nextSupported(int cur, boolean[] ok) {
        int n = ok == null ? 1 : ok.length;
        for (int k = 1; k <= n; k++) {
            int c = (cur + k) % n;
            if (ok == null || ok[c]) return c;
        }
        return cur;
    }

    // ------------------------------------------------------------------ gallery

    /** The last recording (or the newest VidUltra video) in the gallery app; swipe for others. */
    private void openGallery() {
        android.net.Uri uri = lastVideo != null ? lastVideo : newestVideo();
        if (uri == null) {
            android.widget.Toast.makeText(this, "No videos yet", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            android.widget.Toast.makeText(this, "No gallery app found", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void loadThumbnail(android.net.Uri uri) {
        new Thread(() -> {
            try {
                Bitmap bm = getContentResolver().loadThumbnail(uri, new Size(192, 192), null);
                runOnUiThread(() -> ui.setThumbnail(bm));
            } catch (Exception e) {
                android.util.Log.w("VIDULTRA", "thumbnail: " + e);
            }
        }, "thumb").start();
    }

    private android.net.Uri newestVideo() {
        android.net.Uri coll = android.provider.MediaStore.Video.Media.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try (android.database.Cursor c = getContentResolver().query(coll,
                new String[]{android.provider.MediaStore.Video.Media._ID},
                android.provider.MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?", new String[]{"DCIM/VidUltra%"},
                android.provider.MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (c != null && c.moveToFirst()) return android.content.ContentUris.withAppendedId(coll, c.getLong(0));
        }
        return null;
    }

    // ------------------------------------------------------------------ camera

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            permitted = true;
            openCamera(engine);
        }
    }

    private void openCamera(Engine engine) {
        // Tuning / test overrides (adb launch extras): --ez fp16 true, --ez ispmin true,
        // --ei pevery N, --ez eis false, --ef zoom 1.1, --ef sigma 0.35, --ef lookahead 0.5, ...
        Engine.fp16 = getIntent().getBooleanExtra("fp16", Engine.fp16);
        Engine.eis = getIntent().getBooleanExtra("eis", Engine.eis);
        Engine.eisZoom = getIntent().getFloatExtra("zoom", Engine.eisZoom);
        Engine.dynamicZoom = getIntent().getBooleanExtra("dynzoom", Engine.dynamicZoom);
        Engine.recordLog = getIntent().getBooleanExtra("log", Engine.recordLog);
        Engine.autoTone = getIntent().getBooleanExtra("autotone", Engine.autoTone);
        Engine.nr = getIntent().getBooleanExtra("nr", Engine.nr);
        Engine.sidecars = getIntent().getBooleanExtra("sidecars", Engine.sidecars);
        Engine.spikeFilter = getIntent().getBooleanExtra("spike", Engine.spikeFilter);
        NoiseReducer.motionComp = getIntent().getBooleanExtra("mc", NoiseReducer.motionComp);   // experiment
        NoiseReducer.maxFrames = getIntent().getFloatExtra("nrframes", NoiseReducer.maxFrames);
        NoiseReducer.motionTol = getIntent().getFloatExtra("nrtol", NoiseReducer.motionTol);
        NoiseReducer.lumaK = getIntent().getFloatExtra("nrluma", NoiseReducer.lumaK);
        NoiseReducer.noiseGain = getIntent().getFloatExtra("nrgain", NoiseReducer.noiseGain);
        NoiseReducer.grain = getIntent().getFloatExtra("nrgrain", NoiseReducer.grain);
        NoiseReducer.maskLo = getIntent().getFloatExtra("nrmasklo", NoiseReducer.maskLo);
        NoiseReducer.maskHi = getIntent().getFloatExtra("nrmaskhi", NoiseReducer.maskHi);
        Engine.eisSigmaS = getIntent().getFloatExtra("sigma", Engine.eisSigmaS);
        Engine.lookaheadS = getIntent().getFloatExtra("lookahead", Engine.lookaheadS);
        //         --ef gyrooff MS, --ef oisk K, --ef oistau S  (from sim/tools/Analyze.java)
        Engine.gyroOffsetMs = getIntent().getFloatExtra("gyrooff", Engine.gyroOffsetMs);
        if ("CPH2585".equals(android.os.Build.MODEL)) {
            // measured on this model's main camera with sim/tools/Analyze.java
            Engine.oisGain = 0.70f;
            Engine.oisTauS = 0.15f;
            Engine.oisCameraId = "2";   // the main camera; the ultra-wide / front have no OIS
        }
        Engine.oisGain = getIntent().getFloatExtra("oisk", Engine.oisGain);
        Engine.oisTauS = getIntent().getFloatExtra("oistau", Engine.oisTauS);
        Engine.vendorStabMode = getIntent().getIntExtra("stabmode", Engine.vendorStabMode);
        String camExtra = getIntent().getStringExtra("cam");   // test override
        DeviceSetup.forceCameraId = camExtra != null ? camExtra : settings.camera.isEmpty() ? null : settings.camera;
        DeviceSetup.fullFov = getIntent().getBooleanExtra("fullfov", false);   // with --ei outw/outh
        Engine.shutterCapMs = getIntent().getFloatExtra("shutter", Engine.shutterCapMs);   // ms, 0 = auto
        Engine.flickerHz = getIntent().getIntExtra("flicker", Engine.flickerHz);           // 50, 60, 0
        Engine.filmShutter = getIntent().getBooleanExtra("filmshutter", Engine.filmShutter);
        Engine.aeDebug = getIntent().getBooleanExtra("aedebug", false);
        Engine.visionOisOn = getIntent().getBooleanExtra("vision", Engine.visionOisOn);
        Engine.visionSigmaS = getIntent().getFloatExtra("vsigma", Engine.visionSigmaS);
        DeviceSetup.ispMin = getIntent().getBooleanExtra("ispmin", DeviceSetup.ispMin);
        Engine.previewEveryWhileRecording = Math.max(1, getIntent().getIntExtra("pevery",
                Engine.previewEveryWhileRecording));
        int[] degrees = {0, 90, 180, 270};
        // Test overrides: --es raw raw10|raw12|raw16, --ei outw N --ei outh N
        String raw = getIntent().getStringExtra("raw");
        int fmt = raw == null ? 0 : raw.equals("raw10") ? android.graphics.ImageFormat.RAW10
                : raw.equals("raw12") ? android.graphics.ImageFormat.RAW12
                : android.graphics.ImageFormat.RAW_SENSOR;
        engine.openCamera(degrees[getDisplay().getRotation()], fmt,
                getIntent().getIntExtra("outw", 0), getIntent().getIntExtra("outh", 0));
        engine.setRawPreview(settings.live);
    }

    private boolean stopped;
    private int storageMinutes = -1;

    /** Gravity -> horizon angle on screen, for the level shown with the grid. */
    private final android.hardware.SensorEventListener level = new android.hardware.SensorEventListener() {
        @Override
        public void onSensorChanged(android.hardware.SensorEvent e) {
            float gx = e.values[0], gy = e.values[1];
            int rot = getDisplay().getRotation();
            // canvas angle of the true horizon, landscape either way round
            double a = rot == android.view.Surface.ROTATION_270 ? Math.atan2(gy, -gx) : Math.atan2(-gy, gx);
            ui.setLevel((float) Math.toDegrees(a));
        }

        @Override
        public void onAccuracyChanged(android.hardware.Sensor s, int acc) {
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        android.hardware.SensorManager sm = getSystemService(android.hardware.SensorManager.class);
        android.hardware.Sensor g = sm == null ? null : sm.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY);
        if (g != null) sm.registerListener(level, g, android.hardware.SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onPause() {
        super.onPause();
        android.hardware.SensorManager sm = getSystemService(android.hardware.SensorManager.class);
        if (sm != null) sm.unregisterListener(level);
    }

    /** Recording time left on the storage at the current bitrate, and the battery, every 5 s. */
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            if (isDestroyed()) return;
            long free = new android.os.StatFs(android.os.Environment.getExternalStorageDirectory().getPath()).getAvailableBytes();
            int rate = setup != null ? setup.bitrate : (int) (Settings.BITS_PER_PIXEL[settings.bitrate]
                    * settings.size()[0] * settings.size()[1] * settings.fpsValue());
            int minutes = (int) (free * 8 / (rate + 256_000L) / 60);
            android.os.BatteryManager bm = getSystemService(android.os.BatteryManager.class);
            int pct = bm == null ? -1 : bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
            boolean chg = bm != null && bm.isCharging();
            storageMinutes = minutes;
            ui.setDeviceStatus(minutes, pct, chg);
            ui.postDelayed(this, 5000);
        }
    };

    /**
     * Leaving the app (gallery, home, screen off): Android takes the camera from background
     * apps, so stop cleanly here (a recording is stopped and saved) and rebuild on return.
     */
    @Override
    protected void onStop() {
        super.onStop();
        if (isChangingConfigurations() || stopped) return;
        stopped = true;
        switching = true;
        DiagLog.cleanExit();
        ui.setSwitching(formatLabel());
        Engine old = engine;
        lifecycle.execute(() -> old.release(2500));
    }

    @Override
    protected void onStart() {
        super.onStart();
        DiagLog.markRunning();
        if (stopped) {
            stopped = false;
            ui.setSwitching(formatLabel());
            // after the release queued in onStop has finished
            lifecycle.execute(() -> runOnUiThread(this::startEngine));
        }
    }

    @Override
    public void onBackPressed() {
        if (sheet.isOpen()) sheet.close();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(receiver);
        Engine e = engine;
        lifecycle.execute(() -> e.release(2000));
        lifecycle.shutdown();
        DiagLog.cleanExit();
        super.onDestroy();
    }
}
