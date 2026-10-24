package dev.vidultra.proto;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.RggbChannelVector;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES30;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.SizeF;
import android.view.Surface;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAW camera -> GPU -> (10-bit HEVC encoder surface, preview surface).
 *
 * Unstabilized (preview, or recording with EIS off): one fused DEVELOP pass per surface.
 * Stabilized recording: frames wait {@link #lookaheadS} in the camera's own buffers so the
 * smoothed path can see the future; then only the sensor region the {@link Stabilizer} needs is
 * uploaded, DEVELOPed to a LogC3 intermediate, and WARPed into the encoder and the preview.
 * Device differences are resolved by {@link DeviceSetup}; all GL work runs on the "gl" thread.
 */
final class Engine {
    private static final String TAG = "VIDULTRA";

    private static final int EGL_GL_COLORSPACE_KHR = 0x309D;
    private static final int EGL_GL_COLORSPACE_BT2020_HLG_EXT = 0x3540;
    private static final int MARGIN_X = 4, MARGIN_Y = 2;   // demosaic kernel reach

    // Tuning switches (launch extras)
    static boolean fp16;                     // half-precision develop arithmetic
    static int previewEveryWhileRecording = 1;
    static boolean eis = true;               // gyro stabilization while recording
    static float eisZoom = 1.20f;            // fixed crop that buys stabilization margin
    // Fixed crop + per-axis soft limit (offline-tuned): no zoom "breathing". The dynamic crop
    // remains available (--ez dynzoom true) but its changing size is distracting to watch.
    static boolean dynamicZoom = false;
    // What the file holds: Rec.709 for now (the 709 view look); LogC3/AWG3 with --ez log true.
    // The pipeline works in LogC3 internally either way.
    static boolean recordLog = false;
    static volatile int logCurve = ColorSpaces.LOGC3, logGamut = ColorSpaces.G_AWG3;   // when recordLog
    static volatile String logTag = "LogC3_AWG3";
    static volatile boolean monitor709 = true;   // live view shows a log recording as Rec.709
    static volatile boolean autoTone = true;     // dynamic (local) tone mapping, Rec.709 and log
    static volatile boolean nr = true;           // temporal + chroma noise reduction (NoiseReducer)
    static boolean eightBit;                     // 8-bit EGL + encoder (phones without HEVC Main10)
    static boolean sidecars;                     // analysis CSVs next to each clip (test builds)
    static boolean spikeFilter = true;           // pre-demosaic same-colour spike clipping
    static float spikeK = 3.0f;                  // ... beyond the neighbours by this many sigmas
    static float eisSigmaS = 0.35f;          // smoothing strength
    static float lookaheadS = 0.6f;          // look-ahead (offline-tuned; 0.75 s was better but its
                                             // camera buffers ran the phone low on memory)
    // Calibrated per device with sim/tools/Analyze.java from an unstabilized clip:
    static float gyroOffsetMs = 0;           // gyro time = camera time + offset
    static float oisGain = 0, oisTauS = 0.3f;   // hidden-OIS model (0 gain = no OIS)
    static String oisCameraId;               // the lens it was measured on (null = any)
    // Experiment: OnePlus vendor request key com.oplus.video.stabilization.mode (-1 = not set),
    // to find out whether any value switches the lens OIS off.
    static int vendorStabMode = -1;
    // Viewfinder from the ISP side stream; with the live view on, the RAW pipeline itself.
    static volatile boolean ispViewfinder = true;
    // Video auto-exposure while recording (see AeController). The shutter grows to this cap at
    // base ISO before ISO rises; with anti-flicker the cap snaps to whole mains half-cycles
    // (50 Hz: 1/100 s; 60 Hz: 1/60 s). 0 = the HAL's own AE.
    static float shutterCapMs = 16.67f;
    static int flickerHz = 50;
    static boolean filmShutter = true;       // 180-degree shutter while calm (AeController)
    private AeController ae;
    private int aeRequestFrames;
    private float liftAnchor = 1.275f;   // highlight cap (HIGH_CAP of clip) in scene-linear units
    // Measure hidden OIS from the ISP frames while recording (hybrid with the OIS model if set).
    static boolean visionOisOn = true;
    static float visionSigmaS = 0.15f;  // zero-phase smoothing of the vision path (offline-tuned)

    private final Context context;
    private final HandlerThread glThread = new HandlerThread("gl");
    private final HandlerThread camThread = new HandlerThread("cam");
    private final HandlerThread encThread = new HandlerThread("enc");
    private final Handler gl, cam, enc;
    private final HandlerThread visionThread = new HandlerThread("vision");
    private Handler visionH;
    private CameraCaptureSession session;
    private CameraCaptureSession.CaptureCallback captureCallback;
    private VisionOis vision;
    private int visW, visH;
    private double ispRegX, ispRegY, ispRegW, ispRegH;   // sensor region the ISP stream shows
    private int yuvProgram;
    private final int[] yuvTex = new int[3];
    private boolean yuvAllocated, readyLogged;
    private byte[] chromaU, chromaV;
    private byte[] planeCopy = new byte[0];   // bulk copy of one plane (per-byte get() is slow)

    // Camera
    private DeviceSetup setup;
    private CameraDevice device;
    private ImageReader reader;
    private ImageReader ispReader;
    private ColorMath color;
    private int rPos, bPos;
    private final float[] staticBlack = new float[4];
    private int staticWhite;
    private final ConcurrentHashMap<Long, TotalCaptureResult> results = new ConcurrentHashMap<>();
    private volatile TotalCaptureResult latestResult;

    // How the RAW buffer is actually laid out, decided from the first frame.
    private int rawBits;            // 10, 12 or 16
    private boolean packedAsRaw16;  // RAW_SENSOR buffer that really holds MIPI10 data

    // Stabilization
    private Gyro gyro;
    private Stabilizer stab;
    private int lookaheadFrames;
    private boolean skewWarned;
    private static final class Pending {
        final Image img;
        final long ts;

        Pending(Image img, long ts) {
            this.img = img;
            this.ts = ts;
        }
    }
    private final ArrayDeque<Pending> queue = new ArrayDeque<>();

    // GL
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface pbuffer = EGL14.EGL_NO_SURFACE;
    private EGLSurface previewSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface encoderSurface = EGL14.EGL_NO_SURFACE;
    private int previewW, previewH;
    private boolean hlgColorspace;
    private int program, warpProgram;
    private final int[] rawTex = new int[2];
    private int texIndex;
    private int shadeTex;
    private int shadeCols, shadeRows;
    private int fbo, logTex;
    private final Uniforms u = new Uniforms();
    private final WarpUniforms w = new WarpUniforms();
    private float exposure = 1.5f;
    private volatile boolean preview709 = true;
    private int frameCount;

    // Recording
    private MediaCodec videoCodec;
    private Surface codecInput;
    private Muxer muxer;
    private AudioEncoder audio;
    private volatile boolean recording;
    private long recordBaseNs;
    private String outputPath;
    private BufferedWriter frameLog;

    // Colour matrix source: "cm" = DNG ColorMatrix path (default, any phone),
    // "isp" = vendor runtime CCM where published, "fm" = forward matrix as shipped.
    private volatile String matrixSource = "cm";
    private CaptureResult.Key<?> vendorCcmKey;
    private boolean vendorCcmSearched;
    private long lastIspLog;

    // Stats
    private long statStart = SystemClock.elapsedRealtime();
    private int statFrames, statEncoded, statDropped;
    private long statUploadNs, statDrawNs;
    private long lastTs;

    private static final class Uniforms {
        int raw, shade, shadeSize, viewOrigin, outSize, step, origin, sensorMax, sensorSize,
                black, scale, clip, rPosU, bPosU, ccm, view709, awgTo709, flip, fboU, lift, liftAnchor;
    }

    private static final class WarpUniforms {
        int log, boxOrigin, texSize, band, rawH, lens, lensSkew, dist, virt, viewOrigin, viewSize,
                outSize, flip, view709, awgTo709, base, baseScale, baseMax, local, curve, outGamut, ss;
    }

    // BASE pass: small log-luminance map of the pass-1 box for local tone mapping
    private static final int BASE_W = 64;
    private int baseProgram, baseTex, baseFbo, baseLog, baseTexSize, baseCell, baseLinear;
    private NoiseReducer noise;
    private boolean noiseOk;
    private float noiseS, noiseO;
    private float[] chanNoise = {1.4f, 1f, 1.4f};
    private boolean noiseWarned;
    private float[] tint = {1, 1, 1};
    private float[] smoothNeutral;

    Engine(Context context) {
        this.context = context;
        glThread.start();
        camThread.start();
        encThread.start();
        visionThread.start();
        visionH = new Handler(visionThread.getLooper());
        gl = new Handler(glThread.getLooper());
        cam = new Handler(camThread.getLooper());
        enc = new Handler(encThread.getLooper());
        gl.post(this::initEgl);
    }

    void togglePreview709() {
        setPreview709(!preview709);
    }

    /** Live view of a log recording: as Rec.709 (on) or the log signal itself (off). */
    void setPreview709(boolean on) {
        preview709 = on;
        monitor709 = on;
        Log.i(TAG, "log monitor: " + (on ? "Rec.709" : "log"));
    }

    /**
     * Live view: the viewfinder shows the real RAW pipeline (exposure, tone, noise reduction,
     * the recording's look) whether recording or not. Off: the ISP viewfinder, even while
     * recording (the file is still the RAW pipeline).
     */
    void setRawPreview(boolean on) {
        gl.post(() -> {
            if (ispViewfinder == !on) return;
            ispViewfinder = !on;
            if (!recording) {
                if (ae != null) ae.reset();   // our AE takes over from the HAL's (or hands back)
                if (noise != null) noise.reset();
                if (stab != null) stab.enabled = false;
            }
            streamRaw(recording || on);
            Log.i(TAG, "viewfinder: " + (on ? "RAW pipeline" : "ISP"));
        });
    }

    /** RAW frames are flowing (recording or live view): our AE drives the sensor. */
    private boolean rawActive() {
        return recording || !ispViewfinder;
    }

    /** Output look: Rec.709, or a camera log over a gamut (applied from the next frame). */
    void setOutput(boolean log, ColorSpaces.Profile p, int gamut) {
        gl.post(() -> {
            if (recording) return;   // never change the look mid-clip
            recordLog = log;
            logCurve = p.curve;
            logGamut = gamut;
            String g = ColorSpaces.GAMUTS[gamut].name.replaceAll("[^A-Za-z0-9.]", "");
            logTag = p.fileTag + "_" + g;
            Log.i(TAG, "output: " + (log ? p.name + " / " + ColorSpaces.GAMUTS[gamut].name : "Rec.709"));
        });
    }

    void setStabilization(boolean on) {
        gl.post(() -> {
            if (recording) return;
            eis = on;
            streamRaw(rawActive());   // OIS follows (off with gyro EIS, on without)
        });
    }

    void setFilmShutter(boolean on) {
        filmShutter = on;
        AeController a = ae;
        if (a != null) a.filmShutter = on;
    }

    void setFlicker(int hz) {
        gl.post(() -> {
            flickerHz = hz;
            if (ae != null) ae.setFlicker(hz);
            if (session != null) streamRaw(rawActive());
        });
    }

    // ---------------------------------------------------------------- touch focus / exposure

    private volatile float focusX = -1, focusY = -1;   // normalized output coords, -1 = none
    private volatile boolean aeAfLocked;
    private volatile float evBias;
    private double[] focusOrientation;

    /**
     * Tap: focus and meter at (nx, ny) of the picture (0..1, as displayed). With lock, focus
     * and exposure then stay put (AE/AF lock) until unlocked; without, the point is dropped
     * again when the camera turns away from it (like a phone camera).
     */
    void focusAt(float nx, float ny, boolean lock) {
        gl.post(() -> {
            focusX = Math.max(0, Math.min(1, nx));
            focusY = Math.max(0, Math.min(1, ny));
            aeAfLocked = false;
            if (ae != null) ae.locked = false;
            focusOrientation = gyro != null ? gyro.latestOrientation() : null;
            focusAtMs = SystemClock.elapsedRealtime();
            focusRef = null;
            faceRegion = null;
            triggerAf();
            if (lock) {
                // let focus and exposure settle on the point, then hold them
                gl.postDelayed(() -> setAeAfLock(true), 600);
            }
        });
    }

    void setAeAfLock(boolean on) {
        gl.post(() -> {
            aeAfLocked = on;
            if (ae != null) ae.locked = on;
            if (!on) {
                // unlocked: the point stays, judged afresh from here
                focusOrientation = gyro != null ? gyro.latestOrientation() : null;
                focusAtMs = SystemClock.elapsedRealtime();
                focusRef = null;
            }
            streamRaw(rawActive());
            Listener l = listener;
            if (l != null) l.onLockChanged(on);
        });
    }

    boolean isAeAfLocked() {
        return aeAfLocked;
    }

    /** Exposure compensation from the slider beside the focus box (EV). */
    void setEvBias(float ev) {
        evBias = Math.max(-2f, Math.min(2f, ev));
        AeController a = ae;
        if (a != null) a.bias = evBias;
        gl.post(() -> {
            if (session != null && !rawActive()) streamRaw(false);   // HAL AE compensation
        });
    }

    /** Back to whole-frame continuous focus and centre / face metering. */
    void clearFocus() {
        gl.post(() -> {
            focusX = focusY = -1;
            aeAfLocked = false;
            evBias = 0;
            if (ae != null) {
                ae.locked = false;
                ae.bias = 0;
            }
            streamRaw(rawActive());
            Listener l = listener;
            if (l != null) l.onFocusCleared();
        });
    }

    private void triggerAf() {
        if (session == null) return;
        try {
            CaptureRequest.Builder b = requestBuilder(rawActive());
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(b.build(), captureCallback, cam);
            session.setRepeatingRequest(requestBuilder(rawActive()).build(), captureCallback, cam);
        } catch (Exception e) {
            Log.e(TAG, "AF trigger", e);
        }
    }

    /** Tap point (output coords) -> raw sensor pixel, for metering and AF/AE regions. */
    private float[] focusSensorPoint() {
        if (focusX < 0 || setup == null) return null;
        float nx = focusX, ny = focusY;
        if (setup.flip180) {
            nx = 1 - nx;
            ny = 1 - ny;
        }
        float z = recording && eis ? eisZoom : 1f;
        float cx = setup.cropX + setup.cropW / 2f, cy = setup.cropY + setup.cropH / 2f;
        return new float[]{cx + (nx - 0.5f) * setup.cropW / z, cy + (ny - 0.5f) * setup.cropH / z};
    }

    private volatile long focusAtMs;
    private float[] focusRef;                   // scene thumbnail once the tap has settled
    private android.graphics.Rect faceRegion;   // largest face (active-array coords), no tap

    /**
     * "Subject area changed", like iOS: an unlocked tap point is dropped (back to continuous
     * focus and centre / face metering) when the camera turns away by more than ~15 degrees or
     * the picture's content changes. Content: a 16x12 log-brightness map compared with the
     * one taken once the tap settled, each with its mean removed so exposure changes do not
     * count. Runs on the always-on viewfinder frames (4x a second).
     */
    private void checkSceneChange(Image isp) {
        if (focusX < 0 || aeAfLocked) {
            focusRef = null;
            return;
        }
        long age = SystemClock.elapsedRealtime() - focusAtMs;
        if (age < 700) return;                   // focus and exposure are still moving
        float[] th = sceneThumb(isp);
        if (focusRef == null) {
            focusRef = th;
            return;
        }
        double diff = 0;
        for (int k = 0; k < th.length; k++) diff += Math.abs(th[k] - focusRef[k]);
        diff /= th.length;
        double turned = 0;
        if (focusOrientation != null && gyro != null) {
            double[] now = gyro.latestOrientation();
            if (now != null) {
                double d = Math.abs(now[0] * focusOrientation[0] + now[1] * focusOrientation[1]
                        + now[2] * focusOrientation[2] + now[3] * focusOrientation[3]);
                turned = Math.toDegrees(2 * Math.acos(Math.min(1, d)));
            }
        }
        if (diff > 0.30 || turned > 15) {
            Log.i(TAG, String.format(Locale.US, "tap point dropped: scene change %.2f, turned %.1f deg", diff, turned));
            focusRef = null;
            clearFocus();
        }
    }

    /** 16x12 map of log brightness of the viewfinder frame, mean removed. */
    private static float[] sceneThumb(Image isp) {
        Image.Plane y = isp.getPlanes()[0];
        ByteBuffer yb = y.getBuffer();
        int rs = y.getRowStride(), iw = isp.getWidth(), ih = isp.getHeight(), gw = 16, gh = 12;
        float[] t = new float[gw * gh];
        double mean = 0;
        for (int j = 0; j < gh; j++) {
            for (int i = 0; i < gw; i++) {
                int sum = 0, n = 0;
                for (int yy = j * ih / gh; yy < (j + 1) * ih / gh; yy += 6) {
                    int row = yy * rs;
                    for (int xx = i * iw / gw; xx < (i + 1) * iw / gw; xx += 6) {
                        sum += yb.get(row + xx) & 255;
                        n++;
                    }
                }
                t[j * gw + i] = (float) Math.log(1 + sum / (double) Math.max(1, n));
                mean += t[j * gw + i];
            }
        }
        mean /= t.length;
        for (int k = 0; k < t.length; k++) t[k] -= (float) mean;
        return t;
    }

    /** Without a tap point, focus and AE regions follow the largest face (re-requested on change). */
    private void trackFaces(TotalCaptureResult res) {
        if (focusX >= 0 || setup == null) {
            faceRegion = null;
            return;
        }
        android.hardware.camera2.params.Face[] faces = res.get(CaptureResult.STATISTICS_FACES);
        android.graphics.Rect best = null;
        if (faces != null) {
            for (android.hardware.camera2.params.Face f : faces) {
                android.graphics.Rect b = f.getBounds();
                if (f.getScore() >= 50 && (best == null || b.width() * b.height() > best.width() * best.height())) best = b;
            }
        }
        android.graphics.Rect old = faceRegion;
        boolean changed = (best == null) != (old == null) || (best != null
                && (Math.abs(best.centerX() - old.centerX()) > setup.activeW / 12
                || Math.abs(best.width() - old.width()) > setup.activeW / 15));
        if (changed) {
            faceRegion = best == null ? null : new android.graphics.Rect(best);
            gl.post(() -> streamRaw(rawActive()));
        }
    }

    /** UI callbacks (called on the gl thread; post to the UI thread as needed). */
    interface Listener {
        void onRecordingChanged(boolean recording);

        void onSaved(android.net.Uri uri);

        /** The camera / encoder setup is known (what this phone supports). */
        default void onSetup(DeviceSetup s) {
        }

        default void onLockChanged(boolean locked) {
        }

        /** The tap point was dropped (camera turned away, or cleared). */
        default void onFocusCleared() {
        }

        /** The viewfinder is showing pictures (after start / restart). */
        default void onFirstFrame() {
        }

        /** ~4x a second, on the camera's thread. */
        default void onReadout(Readout r) {
        }
    }

    private volatile Listener listener;

    void setListener(Listener l) {
        listener = l;
    }

    boolean isRecording() {
        return recording;
    }

    void setMatrixSource(String src) {
        matrixSource = src;
        Log.i(TAG, "matrix source: " + src);
    }

    // ---------------------------------------------------------------- camera

    @SuppressLint("MissingPermission")
    void openCamera(int displayRotationDeg, int forceFormat, int forceOutW, int forceOutH) {
        CameraManager cm = context.getSystemService(CameraManager.class);
        try {
            setup = DeviceSetup.choose(cm, displayRotationDeg, forceFormat, forceOutW, forceOutH);
            CameraCharacteristics ch = setup.chars;
            color = new ColorMath(ch);
            int cfa = ch.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
            // 2x2 index (row*2+col) of the red and blue sites for RGGB, GRBG, GBRG, BGGR
            int[][] sites = {{0, 3}, {1, 2}, {2, 1}, {3, 0}};
            if (cfa > 3) throw new IllegalStateException("unsupported CFA " + cfa + " (mono/RGB sensor)");
            rPos = sites[cfa][0];
            bPos = sites[cfa][1];
            BlackLevelPattern blp = ch.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
            for (int i = 0; i < 4; i++) staticBlack[i] = blp.getOffsetForIndex(i & 1, i >> 1);
            staticWhite = ch.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
            Log.i(TAG, "cfa=" + cfa + " black=" + Arrays.toString(staticBlack) + " white=" + staticWhite);

            setupStabilizer(ch);
            if (shutterCapMs > 0) {
                Range<Integer> isoR = ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
                Range<Long> expR = ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
                ae = new AeController(isoR.getLower(), isoR.getUpper(), expR.getLower(),
                        Math.min(expR.getUpper(), 1_000_000_000L / setup.fps), (long) (shutterCapMs * 1e6),
                        flickerHz, exposure);
                liftAnchor = (float) (AeController.HIGH_CAP * exposure);
                ae.filmShutter = filmShutter;
                ae.bias = evBias;
                ae.locked = aeAfLocked;
            }

            // Stabilized recording holds lookaheadFrames camera buffers, plus a few in flight.
            reader = ImageReader.newInstance(setup.rawSize.getWidth(), setup.rawSize.getHeight(),
                    setup.rawFormat, (eis ? lookaheadFrames : 0) + 6);
            reader.setOnImageAvailableListener(r -> onImage(), gl);
            // Small ISP YUV stream, frames discarded. On some HALs (e.g. OnePlus) AWB neutral and
            // lens-shading results are only published while a processed stream is running.
            ispReader = ImageReader.newInstance(setup.ispSize.getWidth(), setup.ispSize.getHeight(),
                    ImageFormat.YUV_420_888, 3);
            ispReader.setOnImageAvailableListener(r -> onIspImage(), gl);
            Listener l = listener;
            if (l != null) l.onSetup(setup);
            openDevice(cm, 0);
        } catch (Exception e) {
            Log.e(TAG, "openCamera", e);
        }
    }

    /** Opens the camera; retries while a previous instance (a settings restart) still holds it. */
    @SuppressLint("MissingPermission")
    private void openDevice(CameraManager cm, int attempt) {
        try {
            cm.openCamera(setup.cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    if (released) {
                        d.close();
                        return;
                    }
                    device = d;
                    startSession();
                }

                @Override
                public void onDisconnected(CameraDevice d) {
                    d.close();
                }

                @Override
                public void onError(CameraDevice d, int error) {
                    Log.e(TAG, "camera error " + error);
                    d.close();
                    if ((error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE) && attempt < 8 && !released) {
                        cam.postDelayed(() -> openDevice(cm, attempt + 1), 250);
                    }
                }
            }, cam);
        } catch (Exception e) {
            Log.e(TAG, "openCamera", e);
            if (attempt < 8 && !released) cam.postDelayed(() -> openDevice(cm, attempt + 1), 250);
        }
    }

    private volatile boolean released;

    /** Lens model in raw-pixel coordinates, gyro, and the stabilizer. */
    private void setupStabilizer(CameraCharacteristics ch) {
        int rw = setup.rawSize.getWidth(), rh = setup.rawSize.getHeight();
        float[] k = ch.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
        Rect pre = ch.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
        // from the lens's physical data: focal length (mm) over sensor width (mm), in pixels
        float[] fl = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float fPhys = fl != null && fl.length > 0 && phys != null ? fl[0] / phys.getWidth() * rw : rw * 0.75f;
        float[] intr = new float[]{fPhys, fPhys, rw * 0.5f, rh * 0.5f, 0};
        if (k != null && k[0] > 0) {
            // intrinsics are in pre-correction active array coordinates; the RAW buffer may be
            // that array or a scaled version of it
            float sx = pre == null ? 1 : (float) rw / pre.width(), sy = pre == null ? 1 : (float) rh / pre.height();
            float[] cal = {k[0] * sx, k[1] * sy, k[2] * sx, k[3] * sy, k[4] * sx};
            // Some HALs report nonsense (seen: a front camera with its centre at 0,0 and the
            // focal length of the unbinned array): only a calibration that agrees with the lens
            // data - centre in the middle half, focal within 30% - is used.
            boolean centred = cal[2] > rw * 0.25f && cal[2] < rw * 0.75f && cal[3] > rh * 0.25f && cal[3] < rh * 0.75f;
            boolean focalOk = Math.abs(cal[0] - fPhys) < 0.3f * fPhys;
            if (centred && focalOk) intr = cal;
            else Log.w(TAG, "lens calibration " + Arrays.toString(cal) + " inconsistent with the lens (f "
                    + fPhys + " px): using the physical model");
        }
        float[] dist = ch.get(CameraCharacteristics.LENS_DISTORTION);
        if (dist == null) dist = new float[5];
        boolean hasDist = false;
        for (float d : dist) hasDist |= d != 0;
        Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
        gyro = new Gyro(context, so == null ? 90 : so, setup.front);
        gyro.start();
        stab = new Stabilizer(gyro, rw, rh, intr, dist, setup.outW, setup.outH, setup.cropW, eisZoom,
                MARGIN_X + 2);
        stab.smoothSigmaS = eisSigmaS;
        stab.dynamicZoom = dynamicZoom;
        stab.softClamp = !dynamicZoom;
        stab.plannedAlpha = false;
        stab.gyroOffsetNs = (long) (gyroOffsetMs * 1e6);
        // the hidden-OIS model was measured on one lens; others get none
        boolean oisHere = oisCameraId == null || oisCameraId.equals(setup.cameraId);
        stab.oisGain = oisHere ? oisGain : 0;
        stab.oisTauS = oisTauS;
        lookaheadFrames = Math.max(1, Math.round(lookaheadS * setup.fps));

        // The ISP stream shows the sensor centred at its own aspect ratio.
        double ia = (double) setup.ispSize.getWidth() / setup.ispSize.getHeight(), ra = (double) rw / rh;
        ispRegW = ia >= ra ? rw : rh * ia;
        ispRegH = ia >= ra ? rw / ia : rh;
        ispRegX = (rw - ispRegW) / 2;
        ispRegY = (rh - ispRegH) / 2;
        visW = 640;
        visH = (int) Math.round(visW * setup.ispSize.getHeight() / (double) setup.ispSize.getWidth()) & ~1;
        if (visionOisOn && setup.ispSize.getWidth() >= visW && visH >= 3 * VisionOis.FH) {
            vision = new VisionOis(gyro, visW, visH, ispRegW / visW, ispRegX, ispRegY, intr[0], intr[1], rh);
            vision.gyroOffsetNs = stab.gyroOffsetNs;
            vision.smoothSigmaS = visionSigmaS;
            if (stab.oisGain > 0) vision.baseCaptured = stab::modelCaptured;   // hybrid
            stab.visionOis = vision;
        }
        Log.i(TAG, "EIS: enabled=" + eis + " zoom=" + eisZoom + " sigma=" + eisSigmaS + "s gyroOffset="
                + gyroOffsetMs + "ms ois=" + oisGain + "/" + oisTauS + "s lookahead="
                + lookaheadFrames + " frames intrinsics=" + Arrays.toString(intr)
                + " distortion=" + (hasDist ? Arrays.toString(dist) : "none reported")
                + " visionOis=" + (vision != null) + " viewfinder=" + (ispViewfinder ? "ISP" : "RAW"));
    }

    private void startSession() {
        try {
            List<OutputConfiguration> outs = new ArrayList<>();
            outs.add(new OutputConfiguration(reader.getSurface()));
            outs.add(new OutputConfiguration(ispReader.getSurface()));
            SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                    outs, cam::post, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    session = s;
                    try {
                        captureCallback = new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
                                                           TotalCaptureResult result) {
                                Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
                                if (ts != null) results.put(ts, result);
                                latestResult = result;
                                // keep results for every frame the stabilizer may still hold
                                if (results.size() > lookaheadFrames + 16) {
                                    long min = Long.MAX_VALUE;
                                    for (Long key : results.keySet()) min = Math.min(min, key);
                                    results.remove(min);
                                }
                            }
                        };
                        s.setRepeatingRequest(buildRequest(!ispViewfinder), captureCallback, cam);
                    } catch (Exception e) {
                        Log.e(TAG, "repeating request", e);
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    Log.e(TAG, "session configure failed");
                }
            });
            device.createCaptureSession(sc);
        } catch (Exception e) {
            Log.e(TAG, "startSession", e);
        }
    }

    /** Switch the camera between viewfinder-only (ISP) and recording (RAW + ISP). */
    private void streamRaw(boolean on) {
        if (session == null) return;
        try {
            session.setRepeatingRequest(buildRequest(on || !ispViewfinder), captureCallback, cam);
        } catch (Exception e) {
            Log.e(TAG, "switch RAW stream", e);
        }
    }

    private CaptureRequest buildRequest(boolean withRaw) throws Exception {
        return requestBuilder(withRaw).build();
    }

    private CaptureRequest.Builder requestBuilder(boolean withRaw) throws Exception {
        CameraCharacteristics ch = setup.chars;
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
        if (withRaw) b.addTarget(reader.getSurface());
        b.addTarget(ispReader.getSurface());
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(setup.fps, setup.fps));
        // Focus: continuous, or held on the tapped point (AF_MODE_AUTO keeps the lens where the
        // trigger left it, which is also how AE/AF lock holds focus).
        float[] fp = focusSensorPoint();
        int[] afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (fp != null && contains(afModes, CaptureRequest.CONTROL_AF_MODE_AUTO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            android.hardware.camera2.params.MeteringRectangle[] r = {regionAt(fp)};
            Integer maxAf = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            if (maxAf != null && maxAf > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, r);
            Integer maxAe = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            if (maxAe != null && maxAe > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, r);
        } else if (contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            android.graphics.Rect face = faceRegion;
            Integer maxAf = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            if (face != null && maxAf != null && maxAf > 0) {
                // continuous focus that prefers the face (the HAL keeps refocusing there)
                b.set(CaptureRequest.CONTROL_AF_REGIONS, new android.hardware.camera2.params.MeteringRectangle[]{
                        new android.hardware.camera2.params.MeteringRectangle(face,
                                android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX)});
            }
        }
        // the HAL's AE (ISP viewfinder only): lock and compensation follow the UI
        b.set(CaptureRequest.CONTROL_AE_LOCK, aeAfLocked);
        android.util.Rational step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        Range<Integer> comp = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (step != null && comp != null && step.floatValue() > 0) {
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, comp.clamp(Math.round(evBias / step.floatValue())));
        }
        int[] ab = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES);
        int band = flickerHz == 60 ? CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
                : flickerHz == 50 ? CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
                : CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO;
        if (contains(ab, band)) b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, band);
        // With gyro EIS, OIS must be off where it can be controlled: two stabilizers fight.
        int ois = eis ? CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                : CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON;
        if (contains(ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION), ois)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, ois);
        }
        if (contains(ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_LENS_SHADING_MAP_MODES),
                CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)) {
            b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
        }
        if (vendorStabMode >= 0) {
            try {
                b.set(new CaptureRequest.Key<>("com.oplus.video.stabilization.mode", Integer.class), vendorStabMode);
                Log.i(TAG, "set com.oplus.video.stabilization.mode=" + vendorStabMode);
            } catch (Exception e) {
                Log.w(TAG, "vendor stabilization key rejected: " + e);
            }
        }
        if (ae != null && rawActive() && ae.started() && ae.requestExposureNs() > 0) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ae.requestExposureNs());
            b.set(CaptureRequest.SENSOR_SENSITIVITY, ae.requestIso());
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / setup.fps);
        }
        if (contains(ch.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES),
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE)) {
            b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE);
        }
        // HAL-side stabilization would move the RAW frame under us.
        if (contains(ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        }
        return b;
    }

    /** A focus / metering rectangle (~10% of the frame) around a raw sensor point. */
    private android.hardware.camera2.params.MeteringRectangle regionAt(float[] p) {
        float sx = setup.activeW / (float) setup.rawSize.getWidth(), sy = setup.activeH / (float) setup.rawSize.getHeight();
        int half = Math.round(setup.activeW * 0.05f);
        int x = Math.round(p[0] * sx), y = Math.round(p[1] * sy);
        int x0 = Math.max(0, Math.min(setup.activeW - 2 * half, x - half));
        int y0 = Math.max(0, Math.min(setup.activeH - 2 * half, y - half));
        return new android.hardware.camera2.params.MeteringRectangle(x0, y0, 2 * half, 2 * half,
                android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX);
    }

    private static boolean contains(int[] arr, int v) {
        if (arr == null) return false;
        for (int a : arr) if (a == v) return true;
        return false;
    }

    // ---------------------------------------------------------------- GL

    private void initEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        EGL14.eglInitialize(display, v, 0, v, 1);
        int bits = eightBit ? 8 : 10;
        int[] attribs = {
                EGL14.EGL_RED_SIZE, bits, EGL14.EGL_GREEN_SIZE, bits, EGL14.EGL_BLUE_SIZE, bits,
                EGL14.EGL_ALPHA_SIZE, eightBit ? 8 : 2,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0);
        if (num[0] == 0) throw new RuntimeException("no " + (eightBit ? "RGBA8888" : "RGBA1010102") + " recordable EGL config");
        config = configs[0];
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE}, 0);
        pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext);
        String ext = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS);
        hlgColorspace = ext.contains("EGL_EXT_gl_colorspace_bt2020_hlg");
        Log.i(TAG, "GL: " + GLES30.glGetString(GLES30.GL_RENDERER) + " hlgColorspace=" + hlgColorspace);
        GLES30.glGenTextures(2, rawTex, 0);
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        shadeTex = t[0];
        yuvProgram = buildProgram(Shaders.VERTEX, Shaders.YUV_PREVIEW);
        GLES30.glGenTextures(3, yuvTex, 0);
    }

    /** Builds the shaders for the RAW layout seen in the first frame and allocates textures. */
    private void initPipeline(Image first) {
        Image.Plane plane = first.getPlanes()[0];
        int fmt = setup.rawFormat;
        rawBits = fmt == ImageFormat.RAW10 ? 10 : fmt == ImageFormat.RAW12 ? 12 : 16;
        if (fmt == ImageFormat.RAW_SENSOR && looksPacked10(plane.getBuffer())) {
            rawBits = 10;
            packedAsRaw16 = true;
        }
        Log.i(TAG, "RAW layout: format=" + DeviceSetup.fmtName(fmt) + " decodedAs=" + rawBits + "-bit"
                + " packedAsRaw16=" + packedAsRaw16 + " rowStride=" + plane.getRowStride()
                + " pixelStride=" + plane.getPixelStride() + " bytes=" + plane.getBuffer().remaining());

        // with the spike filter the develop pass reads its cleaned RAW16 output
        program = buildProgram(Shaders.VERTEX, Shaders.develop(spikeFilter ? 16 : rawBits, fp16));
        if (spikeFilter) initClean(setup.rawSize.getWidth(), setup.rawSize.getHeight());
        Log.i(TAG, "tuning: fp16=" + fp16 + " previewEvery=" + previewEveryWhileRecording
                + " ispMin=" + DeviceSetup.ispMin);
        u.raw = loc(program, "uRaw");
        u.shade = loc(program, "uShade");
        u.shadeSize = loc(program, "uShadeSize");
        u.viewOrigin = loc(program, "uViewOrigin");
        u.outSize = loc(program, "uOutSize");
        u.step = loc(program, "uStep");
        u.origin = loc(program, "uOrigin");
        u.sensorMax = loc(program, "uSensorMax");
        u.sensorSize = loc(program, "uSensorSize");
        u.black = loc(program, "uBlack");
        u.scale = loc(program, "uScale");
        u.clip = loc(program, "uClip");
        u.rPosU = loc(program, "uRPos");
        u.bPosU = loc(program, "uBPos");
        u.ccm = loc(program, "uCcm");
        u.view709 = loc(program, "uView709");
        u.awgTo709 = loc(program, "uAwgTo709");
        u.flip = loc(program, "uFlip");
        u.fboU = loc(program, "uFbo");
        u.lift = loc(program, "uLift");
        u.liftAnchor = loc(program, "uLiftAnchor");
        GLES30.glUseProgram(program);
        GLES30.glUniformMatrix3fv(u.awgTo709, 1, true, ColorMath.awg3ToRec709(), 0);
        GLES30.glUniform1i(u.flip, setup.flip180 ? 1 : 0);

        int rw = setup.rawSize.getWidth(), rh = setup.rawSize.getHeight();
        // The whole sensor, so any stabilized region can be uploaded in place.
        for (int tex : rawTex) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
            if (rawBits == 16) {
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R16UI, rw, rh);
            } else {
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R8UI, bytesForPixels(rw), rh);
            }
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        }
        initWarp(rw, rh);
    }

    /** Pass-1 intermediate (10-bit LogC3, whole-sensor size) and the warp program. */
    private void initWarp(int rw, int rh) {
        warpProgram = buildProgram(Shaders.VERTEX, Shaders.WARP);
        int p = warpProgram;
        w.log = loc(p, "uLog");
        w.boxOrigin = loc(p, "uBoxOrigin");
        w.texSize = loc(p, "uTexSize");
        w.band = loc(p, "uBand");
        w.rawH = loc(p, "uRawH");
        w.lens = loc(p, "uLens");
        w.lensSkew = loc(p, "uLensSkew");
        w.dist = loc(p, "uDist");
        w.virt = loc(p, "uVirt");
        w.viewOrigin = loc(p, "uViewOrigin");
        w.viewSize = loc(p, "uViewSize");
        w.outSize = loc(p, "uOutSize");
        w.flip = loc(p, "uFlip");
        w.view709 = loc(p, "uView709");
        w.awgTo709 = loc(p, "uAwgTo709");
        w.base = loc(p, "uBase");
        w.baseScale = loc(p, "uBaseScale");
        w.baseMax = loc(p, "uBaseMax");
        w.local = loc(p, "uLocal");
        w.curve = loc(p, "uCurve");
        w.outGamut = loc(p, "uOutGamut");
        w.ss = loc(p, "uSS");
        GLES30.glUseProgram(p);
        GLES30.glUniformMatrix3fv(w.awgTo709, 1, true, ColorMath.awg3ToRec709(), 0);
        GLES30.glUniform1i(w.flip, setup.flip180 ? 1 : 0);
        GLES30.glUniform2f(w.texSize, rw, rh);
        GLES30.glUniform1f(w.rawH, rh);
        float[] lens = stab.lens();
        GLES30.glUniform4f(w.lens, lens[0], lens[1], lens[2], lens[3]);
        GLES30.glUniform1f(w.lensSkew, 0);
        GLES30.glUniform1fv(w.dist, 5, stab.distortion(), 0);
        float[] virt = stab.virtualIntrinsics();
        GLES30.glUniform3f(w.virt, virt[0], virt[1], virt[2]);
        GLES30.glUniform2f(w.outSize, setup.outW, setup.outH);

        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        logTex = t[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, logTex);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGB10_A2, rw, rh);
        setLinear();
        GLES30.glGenFramebuffers(1, t, 0);
        fbo = t[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, logTex, 0);
        int st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);

        baseProgram = buildProgram(Shaders.VERTEX, Shaders.BASE);
        baseLog = loc(baseProgram, "uLog");
        baseTexSize = loc(baseProgram, "uTexSize");
        baseCell = loc(baseProgram, "uCell");
        baseLinear = loc(baseProgram, "uLinear");
        GLES30.glGenTextures(1, t, 0);
        baseTex = t[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, baseTex);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGB10_A2, BASE_W, BASE_W);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        GLES30.glGenFramebuffers(1, t, 0);
        baseFbo = t[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, baseFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0,
                GLES30.GL_TEXTURE_2D, baseTex, 0);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        if (nr) {
            NoiseReducer.fps = setup.fps;
            noise = new NoiseReducer(setup.outW, setup.outH);
            noiseOk = noise.init(rw, rh, stab.lens(), stab.distortion(), stab.virtualIntrinsics(), setup.flip180);
        }
        Log.i(TAG, "warp pipeline ready, intermediate " + rw + "x" + rh + " RGB10_A2 fbo="
                + (st == GLES30.GL_FRAMEBUFFER_COMPLETE ? "complete" : "0x" + Integer.toHexString(st)));
    }

    private static int loc(int program, String name) {
        return GLES30.glGetUniformLocation(program, name);
    }

    private int bytesForPixels(int px) {
        return rawBits == 10 ? px * 5 / 4 : rawBits == 12 ? px * 3 / 2 : px * 2;
    }

    /**
     * A RAW_SENSOR buffer that really carries MIPI10 data reads as noise above the white level
     * when interpreted as 16-bit. True 16-bit RAW never exceeds white.
     */
    private boolean looksPacked10(ByteBuffer buf) {
        ByteBuffer b = buf.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int n = b.remaining() / 2, over = 0, samples = 2000;
        for (int i = 0; i < samples; i++) {
            int idx = (int) ((long) n * (i + 1) / (samples + 2) * 6 / 10);   // first 60% only
            if ((b.getShort(idx * 2) & 0xFFFF) > staticWhite * 1.1f) over++;
        }
        return over > samples / 50;
    }

    static int buildProgram(String vs, String fs) {
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs));
        GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs));
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link: " + GLES30.glGetProgramInfoLog(p));
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("compile: " + GLES30.glGetShaderInfoLog(s));
        return s;
    }

    void setPreviewSurface(Surface s, int w, int h) {
        gl.post(() -> {
            if (previewSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previewSurface);
            previewSurface = s == null ? EGL14.EGL_NO_SURFACE
                    : EGL14.eglCreateWindowSurface(display, config, s, new int[]{EGL14.EGL_NONE}, 0);
            previewW = w;
            previewH = h;
        });
    }

    // ---------------------------------------------------------------- per frame

    private void onImage() {
        Image img = reader.acquireNextImage();
        if (img == null) return;
        long ts = img.getTimestamp();
        if (lastTs != 0 && ts - lastTs > 1_500_000_000L / setup.fps) statDropped++;
        lastTs = ts;
        if (!recording && ispViewfinder) {   // late frames after a stop
            img.close();
            return;
        }
        if (program == 0) initPipeline(img);
        if (ae != null && (recording ? ts >= recordBaseNs : !ispViewfinder)) meter(img, ts);

        if (recording && eis) {
            if (ts < recordBaseNs) {
                img.close();
                return;
            }
            queue.add(new Pending(img, ts));
            // live view shows this frame now, unstabilized; stabilization is only in the file
            if (!ispViewfinder) drawLivePreview(img, ts);
            while (queue.size() > lookaheadFrames) processStabilized(queue.poll());
            return;
        }
        if (recording && ts < recordBaseNs) {
            img.close();
            return;
        }
        // live view, or recording without stabilization: the same pipeline (noise reduction,
        // tone, look), rendered at once with a plain crop
        if (stab != null) stab.enabled = false;
        processStabilized(new Pending(img, ts));
    }

    /** ISP side stream: live viewfinder, and OIS measurement while recording. */
    private void onIspImage() {
        Image img = ispReader.acquireLatestImage();
        if (img == null) return;
        try {
            if (!readyLogged && setup != null) {
                readyLogged = true;
                Log.i(TAG, "READY");   // test scripts wait for this before sending commands
            }
            long ts = img.getTimestamp();
            if (recording && eis && vision != null) feedVision(img, ts);
            if (ispViewfinder && previewSurface != EGL14.EGL_NO_SURFACE) drawIspPreview(img);
            if (!firstFrameSent && previewSurface != EGL14.EGL_NO_SURFACE) {
                firstFrameSent = true;
                Listener fl = listener;
                if (fl != null) fl.onFirstFrame();
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastReadoutMs >= 250) {
                lastReadoutMs = now;
                publishReadout(img);
                checkSceneChange(img);
                TotalCaptureResult lr = latestResult;
                if (lr != null) trackFaces(lr);
            }
        } finally {
            img.close();
        }
    }

    private long lastReadoutMs;
    private boolean firstFrameSent;

    /** Live values for the viewfinder's readout bar, histogram and audio meter. */
    static final class Readout {
        long exposureNs;
        int iso;
        int cctK;
        float evBias;
        float shutterAngle;
        int fps;
        boolean locked;
        float audioDb = -90;
        final int[] histogram = new int[64];   // luma of the viewfinder frame
    }

    private void publishReadout(Image isp) {
        Listener l = listener;
        TotalCaptureResult res = latestResult;
        if (l == null || res == null || setup == null) return;
        Readout r = new Readout();
        Long exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
        r.exposureNs = exp == null ? 0 : exp;
        r.iso = iso == null ? 0 : iso;
        if (!rawActive() && color != null) color.cameraToAwg3Auto(neutralOf(res), 1f);   // refreshes lastCct
        r.cctK = color == null ? 0 : Math.round(color.lastCct / 50f) * 50;
        r.evBias = evBias;
        r.fps = setup.fps;
        r.shutterAngle = (float) (r.exposureNs * 360.0 * setup.fps / 1e9);
        r.locked = aeAfLocked;
        AudioEncoder a = audio;
        if (recording && a != null) r.audioDb = a.levelDb;
        // histogram of the ISP luma (every 8th pixel)
        Image.Plane y = isp.getPlanes()[0];
        ByteBuffer yb = y.getBuffer();
        int rs = y.getRowStride(), iw = isp.getWidth(), ih = isp.getHeight();
        for (int j = 0; j < ih; j += 8) {
            int row = j * rs;
            for (int i = 0; i < iw; i += 8) r.histogram[(yb.get(row + i) & 255) >> 2]++;
        }
        l.onReadout(r);
    }

    private int aeLog;
    private int visionLog;
    static boolean aeDebug;

    /**
     * Meters the RAW frame for AeController: a 64x48 grid of 2x2 colour quads, white-balanced,
     * luminance = (R + 2G + B) / 4 of clip. Centre-weighted (Gaussian) and face-weighted mean for
     * the midtones, plain 98th percentile for the highlights. Sparse reads of the camera
     * buffer: no GPU readback, nothing waits.
     */
    private void meter(Image img, long ts) {
        TotalCaptureResult res = resultFor(ts);
        if (res == null) return;
        Long exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
        if (exp == null || iso == null) return;
        Image.Plane p = img.getPlanes()[0];
        ByteBuffer b = p.getBuffer();
        int rs = packedAsRaw16 ? setup.rawSize.getWidth() * 5 / 4 : p.getRowStride();
        int rw = setup.rawSize.getWidth(), rh = setup.rawSize.getHeight();
        float[] neutral = neutralOf(res);
        double[] gain = {neutral[1] / neutral[0], 1, neutral[1] / neutral[2]};
        android.hardware.camera2.params.Face[] faces = res.get(CaptureResult.STATISTICS_FACES);
        float[] spot = focusSensorPoint();
        ae.spot = spot != null;
        int gw = 64, gh = 48;
        double[] lum = new double[gw * gh];
        double[] gLevel = new double[gw * gh], gDiff = new double[gw * gh];
        double sw = 0, swl = 0;
        double range = staticWhite - staticBlack[0];
        for (int j = 0; j < gh; j++) {
            int y = ((int) ((j + 0.5) * rh / gh)) & ~1;
            for (int i = 0; i < gw; i++) {
                int x = ((int) ((i + 0.5) * rw / gw)) & ~3;
                double r = 0, g = 0, bl = 0, g1 = Double.NaN, g2 = 0;
                for (int q = 0; q < 4; q++) {
                    int px = x + (q & 1), py = y + (q >> 1);
                    double v = (rawAt(b, rs, px, py) - staticBlack[q]) / range;
                    if (q == rPos) r = v * gain[0];
                    else if (q == bPos) bl = v * gain[2];
                    else {
                        g += 0.5 * v;
                        if (Double.isNaN(g1)) g1 = v; else g2 = v;
                    }
                }
                gLevel[j * gw + i] = 0.5 * (g1 + g2);
                gDiff[j * gw + i] = Math.abs(g1 - g2);
                double l = Math.max(0, (r + 2 * g + bl) * 0.25);
                lum[j * gw + i] = l;
                double wt;
                if (spot != null) {
                    // tapped point: a spot (~1/8 of the frame) dominates, the rest counts a little
                    double dx = (x - spot[0]) / rw, dy = (y - spot[1]) / rw;
                    wt = 0.05 + Math.exp(-(dx * dx + dy * dy) / (2 * 0.06 * 0.06));
                } else {
                    double dx = (i + 0.5) / gw - 0.5, dy = (j + 0.5) / gh - 0.5;
                    wt = Math.exp(-(dx * dx + dy * dy) / (2 * 0.3 * 0.3));
                }
                if (faces != null && spot == null) {
                    for (android.hardware.camera2.params.Face f : faces) {
                        if (f.getBounds().contains(x, y)) wt *= 4;
                    }
                }
                sw += wt;
                swl += wt * l;
            }
        }
        double mid = swl / sw;
        double[] sorted = lum.clone();
        java.util.Arrays.sort(sorted);
        double p98 = sorted[(int) (0.98 * (sorted.length - 1))];
        calibrateNoise(res, gLevel, gDiff);
        double frameEv = Math.log((double) exp * iso) / Math.log(2);
        boolean changed = ae.onFrame(ts * 1e-9, ts, mid, p98, frameEv, gyro.rate());
        if (aeDebug) Log.i(TAG, String.format(Locale.US,
                "AEDBG res %.2f ms ISO %d | req %.2f ms ISO %d | mid %.4f p98 %.3f lift %.2f",
                exp / 1e6, iso, ae.requestExposureNs() / 1e6, ae.requestIso(), mid, p98, ae.liftGain()));
        // re-issue the request when it changed, at most every other frame
        if (changed && ++aeRequestFrames >= 2) {
            aeRequestFrames = 0;
            streamRaw(true);
            if (aeLog++ % 12 == 0) {
                Log.i(TAG, String.format(Locale.US, "AE: shutter %.2f ms ISO %d, lift %.2f",
                        ae.requestExposureNs() / 1e6, ae.requestIso(), ae.liftGain()));
            }
        }
    }

    private volatile float noiseCal = 1f;   // measured RAW noise / HAL profile (smoothed)
    private int noiseCalLog;

    /**
     * Self-calibration of the noise level from the RAW frame itself, so NR is right on any phone
     * whatever its HAL reports: the two green pixels of a 2x2 block see the same light, so their
     * difference is noise (plus texture). Per brightness band, the lower quartile of |g1 - g2|
     * (robust against texture: for Gaussian noise it is 0.3186 sigma_d, sigma_d = sqrt 2 sigma)
     * is compared with the profile's prediction; the median ratio over bands, eased, scales it.
     */
    private void calibrateNoise(TotalCaptureResult res, double[] level, double[] diff) {
        android.util.Pair<Double, Double>[] np = res.get(CaptureResult.SENSOR_NOISE_PROFILE);
        double S, O;
        if (np != null && np.length >= 3) {
            S = 0.5 * (np[1].first + np[2].first);
            O = 0.5 * (np[1].second + np[2].second);
        } else {
            Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
            double k = (iso == null ? 400 : iso) / 100.0;
            S = 5.8e-5 * k;
            O = 1.5e-8 * k * k;
        }
        int bands = 8;
        double[] ratios = new double[bands];
        int nr = 0;
        for (int band = 0; band < bands; band++) {
            double lo = 0.002 * Math.pow(2, band), hi = lo * 2;   // 0.002 .. 0.5 of clip
            int n = 0;
            for (double l : level) if (l >= lo && l < hi) n++;
            if (n < 60) continue;
            double[] d = new double[n];
            int k = 0;
            double sumL = 0;
            for (int i = 0; i < level.length; i++) {
                if (level[i] >= lo && level[i] < hi) {
                    d[k++] = diff[i];
                    sumL += level[i];
                }
            }
            java.util.Arrays.sort(d);
            double sigma = d[n / 4] / 0.3186 / Math.sqrt(2);
            double model = Math.sqrt(S * (sumL / n) + O);
            if (model > 0 && sigma > 0) ratios[nr++] = sigma / model;
        }
        if (nr == 0) return;
        java.util.Arrays.sort(ratios, 0, nr);
        double r = Math.max(0.5, Math.min(4.0, ratios[nr / 2]));
        noiseCal += (float) ((r - noiseCal) * 0.08);
        if (noiseCalLog++ % 48 == 0) {
            Log.i(TAG, String.format(Locale.US, "NR calibration: measured/profile %.2f (now %.2f, %d bands)", r, noiseCal, nr));
        }
    }

    /** One RAW code at sensor position (x, y). */
    private int rawAt(ByteBuffer b, int rs, int x, int y) {
        if (rawBits == 16) {
            int o = y * rs + x * 2;
            return (b.get(o) & 255) | ((b.get(o + 1) & 255) << 8);
        } else if (rawBits == 12) {
            int g = x >> 1, r = x & 1;
            int hi = b.get(y * rs + g * 3 + r) & 255, lo = b.get(y * rs + g * 3 + 2) & 255;
            return (hi << 4) | ((lo >> (r * 4)) & 15);
        }
        int g = x >> 2, r = x & 3;
        int hi = b.get(y * rs + g * 5 + r) & 255, lo = b.get(y * rs + g * 5 + 4) & 255;
        return (hi << 2) | ((lo >> (r * 2)) & 3);
    }

    private void feedVision(Image img, long ts) {
        // one native copy here; the downsampling and FFTs run on the vision thread
        Image.Plane y = img.getPlanes()[0];
        ByteBuffer yb = y.getBuffer().duplicate();
        yb.position(0);
        byte[] src = new byte[yb.remaining()];
        yb.get(src);
        int rs = y.getRowStride(), iw = img.getWidth(), ih = img.getHeight();
        TotalCaptureResult res = resultFor(ts);
        Long exp = res == null ? null : res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Long skew = res == null ? null : res.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
        long e = exp == null ? 10_000_000L : exp, k = skew == null ? 1_000_000_000L / setup.fps * 3 / 4 : skew;
        visionH.post(() -> {
            byte[] g = new byte[visW * visH];
            double sx = iw / (double) visW, sy = ih / (double) visH;
            for (int j = 0; j < visH; j++) {
                int row = (int) (j * sy) * rs;
                for (int i = 0; i < visW; i++) g[j * visW + i] = src[row + (int) (i * sx)];
            }
            vision.onFrame(g, ts, e, k);
            if (++visionLog % 24 == 0) Log.i(TAG, "vision: " + vision.describe());
        });
    }

    private void drawIspPreview(Image img) {
        int iw = img.getWidth(), ih = img.getHeight(), cw = iw / 2, chh = ih / 2;
        Image.Plane[] pl = img.getPlanes();
        if (!yuvAllocated) {
            for (int i = 0; i < 3; i++) {
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, yuvTex[i]);
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R8, i == 0 ? iw : cw, i == 0 ? ih : chh);
                setLinear();
            }
            chromaU = new byte[cw * chh];
            chromaV = new byte[cw * chh];
            yuvAllocated = true;
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, yuvTex[0]);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, pl[0].getRowStride());
        ByteBuffer yb = pl[0].getBuffer();
        yb.position(0);
        GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, iw, ih, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, yb);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0);
        // chroma planes may be interleaved (pixel stride 2); pack each into its own texture
        for (int c = 0; c < 2; c++) {
            Image.Plane p = pl[1 + c];
            byte[] cs = bulk(p.getBuffer());
            int ps = p.getPixelStride(), rs = p.getRowStride();
            byte[] dst = c == 0 ? chromaU : chromaV;
            for (int j = 0; j < chh; j++) {
                int row = j * rs;
                for (int i = 0; i < cw; i++) {
                    int k = row + i * ps;
                    dst[j * cw + i] = k < cs.length ? cs[k] : 0;
                }
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, yuvTex[1 + c]);
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, cw, chh, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE,
                    ByteBuffer.wrap(dst));
        }
        EGL14.eglMakeCurrent(display, previewSurface, previewSurface, eglContext);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        int[] v = previewViewport();
        GLES30.glViewport(0, 0, previewW, previewH);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glViewport(v[0], v[1], v[2], v[3]);
        GLES30.glUseProgram(yuvProgram);
        for (int i = 0; i < 3; i++) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + i);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, yuvTex[i]);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(yuvProgram, i == 0 ? "uY" : i == 1 ? "uU" : "uV"), i);
        }
        // recording framing: the base crop, tightened by the stabilizer's base zoom
        double z = eis ? eisZoom : 1;
        double fw = setup.cropW / z, fh = setup.cropH / z;
        double fx0 = setup.cropX + (setup.cropW - fw) / 2, fy0 = setup.cropY + (setup.cropH - fh) / 2;
        GLES30.glUniform4f(GLES30.glGetUniformLocation(yuvProgram, "uRect"), (float) ((fx0 - ispRegX) / ispRegW),
                (float) ((fy0 - ispRegY) / ispRegH), (float) (fw / ispRegW), (float) (fh / ispRegH));
        GLES30.glUniform2i(GLES30.glGetUniformLocation(yuvProgram, "uViewOrigin"), v[0], v[1]);
        GLES30.glUniform2f(GLES30.glGetUniformLocation(yuvProgram, "uViewSize"), v[2], v[3]);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(yuvProgram, "uFlip"), setup.flip180 ? 1 : 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        EGL14.eglSwapBuffers(display, previewSurface);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    }

    /** Whole buffer into a reused array (one native copy instead of per-byte calls). */
    private byte[] bulk(ByteBuffer b) {
        ByteBuffer d = b.duplicate();
        d.position(0);
        int n = d.remaining();
        if (planeCopy.length < n) planeCopy = new byte[n];
        d.get(planeCopy, 0, n);
        return planeCopy;
    }

    private TotalCaptureResult resultFor(long ts) {
        TotalCaptureResult res = results.get(ts);
        return res != null ? res : latestResult;
    }

    /**
     * Live view while recording with stabilization: the newest frame straight to the screen,
     * unstabilized and without the 0.6 s look-ahead delay (the file is stabilized; the
     * viewfinder never is, like a phone camera). One develop pass at preview resolution.
     */
    private void drawLivePreview(Image img, long ts) {
        if (previewSurface == EGL14.EGL_NO_SURFACE || frameCount % previewEveryWhileRecording != 0) return;
        TotalCaptureResult res = resultFor(ts);
        if (res == null) return;
        int ux = setup.cropX - MARGIN_X, uy = setup.cropY - MARGIN_Y;
        int uw = setup.cropW + 2 * MARGIN_X, uh = setup.cropH + 2 * MARGIN_Y;
        upload(img.getPlanes()[0], ux, uy, uw, uh);
        updateShading(res);
        if (spikeFilter) runClean(res, ux, uy, uw, uh);
        setFrameUniforms(res, ts);
        EGL14.eglMakeCurrent(display, previewSurface, previewSurface, eglContext);
        int[] v = previewViewport();
        GLES30.glViewport(0, 0, previewW, previewH);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
        GLES30.glUniform2i(u.origin, setup.cropX, setup.cropY);
        GLES30.glUniform1i(u.fboU, 0);
        GLES30.glUniform1i(u.view709, 1);
        draw(v[0], v[1], v[2], v[3], (float) setup.cropW / v[2]);
        EGL14.eglSwapBuffers(display, previewSurface);
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext);
    }

    /** Stabilized: develop the needed sensor region, then warp it into each surface. */
    private void processStabilized(Pending p) {
        try {
            TotalCaptureResult res = resultFor(p.ts);
            if (res == null) return;
            Long exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Long skew = res.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
            if (skew == null) {
                // conservative default: readout over most of the frame time
                skew = 1_000_000_000L / setup.fps * 3 / 4;
                if (!skewWarned) {
                    skewWarned = true;
                    Log.w(TAG, "HAL reports no rolling shutter skew; assuming " + skew / 1e6 + " ms");
                }
            }
            long exposureNs = exp == null ? 10_000_000L : exp;
            if (!stab.compute(p.ts, exposureNs, skew)) return;

            long t0 = System.nanoTime();
            int rw = setup.rawSize.getWidth(), rh = setup.rawSize.getHeight();
            int x0 = Math.max(0, stab.boxX - MARGIN_X) & ~3, y0 = Math.max(0, stab.boxY - MARGIN_Y) & ~1;
            int x1 = Math.min(rw, (stab.boxX + stab.boxW + MARGIN_X + 3) & ~3);
            int y1 = Math.min(rh, stab.boxY + stab.boxH + MARGIN_Y);
            upload(p.img.getPlanes()[0], x0, y0, x1 - x0, y1 - y0);
            updateShading(res);
            if (spikeFilter) runClean(res, x0, y0, x1 - x0, y1 - y0);
            long t1 = System.nanoTime();

            // Pass 1: develop the box into the LogC3 intermediate.
            setFrameUniforms(res, p.ts);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo);
            GLES30.glUniform2i(u.origin, stab.boxX, stab.boxY);
            GLES30.glUniform1i(u.fboU, 1);
            GLES30.glUniform1i(u.view709, 0);
            draw(0, 0, stab.boxW, stab.boxH, 1f);
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);

            // Rec.709 matrix with a hint of the room's colour, eased so AWB changes glide (~1 s)
            float[] m709 = ColorMath.awg3ToRec709();
            if (autoTone) {
                float[] want = ColorMath.ambienceTint(color.lastCct, 0.06f, 0.05f);
                for (int i = 0; i < 3; i++) tint[i] += (want[i] - tint[i]) * 0.04f;
                if (frameCount % 120 == 0) Log.i(TAG, String.format(Locale.US, "WB: cct %.0f K neutral %.3f %.3f %.3f",
                        color.lastCct, smoothNeutral == null ? 0 : smoothNeutral[0], smoothNeutral == null ? 0 : smoothNeutral[1],
                        smoothNeutral == null ? 0 : smoothNeutral[2]));
                for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) m709[r * 3 + c] *= tint[r];
            }
            float[] virt = stab.virtualIntrinsics();
            boolean useNoise = nr && noiseOk;
            float[] outGamut = ColorSpaces.awg3To(logGamut);
            // sub-samples per axis: sensor pixels per output pixel, rounded up (oversampling)
            float sensorPerOut = setup.cropW / (float) setup.outW / (stab.enabled ? eisZoom : 1f);
            int ss = Math.max(1, Math.min(4, (int) Math.ceil(sensorPerOut - 0.15f)));
            boolean previewLook = !recordLog || monitor709;
            int bw = BASE_W, bh;
            if (useNoise) {
                // Pass 2: warp + temporal NR, colour NR (output space)
                noise.accumulate(logTex, shadeTex, shadeCols, shadeRows, stab.boxX, stab.boxY,
                        stab.bandMats, virt, stab.virtual, noiseS, noiseO, chanNoise, ss);
                bh = Math.max(1, Math.min(BASE_W, Math.round(BASE_W * setup.outH / (float) setup.outW)));
                if (autoTone) drawBase(noise.downTex(), noise.qW(), noise.qH(), true,
                        noise.qW() / (float) bw, noise.qH() / (float) bh, bw, bh);
            } else {
                // Base map for local tone mapping: BASE_W cells across the box, square cells.
                bh = Math.max(1, Math.min(BASE_W, Math.round(BASE_W * stab.boxH / (float) stab.boxW)));
                if (autoTone) drawBase(logTex, rw, rh, false, stab.boxW / (float) bw, stab.boxH / (float) bh, bw, bh);
                GLES30.glUseProgram(warpProgram);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE3);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, baseTex);
                GLES30.glUniform1i(w.base, 3);
                GLES30.glUniform2f(w.baseScale, bw / (float) stab.boxW, bh / (float) stab.boxH);
                GLES30.glUniform2i(w.baseMax, bw - 1, bh - 1);
                GLES30.glUniform1i(w.local, autoTone ? 1 : 0);
                GLES30.glUniformMatrix3fv(w.awgTo709, 1, true, m709, 0);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, logTex);
                GLES30.glUniform1i(w.log, 2);
                GLES30.glUniform2f(w.boxOrigin, stab.boxX, stab.boxY);
                GLES30.glUniformMatrix3fv(w.band, Stabilizer.BANDS, true, stab.bandMats, 0);
                GLES30.glUniform3f(w.virt, virt[0], virt[1], virt[2]);
                GLES30.glUniform1i(w.curve, logCurve);
                GLES30.glUniformMatrix3fv(w.outGamut, 1, true, outGamut, 0);
                GLES30.glUniform1i(w.ss, ss);
            }
            if (recording && encoderSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, encoderSurface, encoderSurface, eglContext);
                if (useNoise) {
                    noise.draw(0, 0, setup.outW, setup.outH, !recordLog, m709, baseTex, bw, bh, autoTone,
                            logCurve, outGamut);
                } else {
                    GLES30.glUniform1i(w.view709, recordLog ? 0 : 1);
                    warpDraw(0, 0, setup.outW, setup.outH);
                    GLES30.glUniform1i(w.view709, previewLook ? 1 : 0);
                }
                EGLExt.eglPresentationTimeANDROID(display, encoderSurface, p.ts - recordBaseNs);
                EGL14.eglSwapBuffers(display, encoderSurface);
                statEncoded++;
            }
            frameCount++;
            // (while recording with stabilization the live view is drawn on arrival instead,
            // unstabilized and without the look-ahead delay)
            if (!ispViewfinder && !(recording && eis)
                    && (!recording || frameCount % previewEveryWhileRecording == 0)
                    && previewSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, previewSurface, previewSurface, eglContext);
                int[] v = previewViewport();
                GLES30.glViewport(0, 0, previewW, previewH);
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
                if (useNoise) {
                    noise.draw(v[0], v[1], v[2], v[3], previewLook, m709, baseTex, bw, bh, autoTone,
                            logCurve, outGamut);
                } else {
                    GLES30.glUniform1i(w.view709, previewLook ? 1 : 0);
                    warpDraw(v[0], v[1], v[2], v[3]);
                }
                EGL14.eglSwapBuffers(display, previewSurface);
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            logFrame(p.ts, res, stab.alpha, stab.boxX, stab.boxY, stab.boxW, stab.boxH);
            finishStats(t0, t1);
        } finally {
            p.img.close();
        }
    }

    // ---------------------------------------------------------------- pre-demosaic spike filter

    private int cleanProgram, cleanTex, cleanFbo;
    private int cRaw, cSensorMax, cBlack, cRange, cNoiseS, cNoiseO, cK;

    private void initClean(int rw, int rh) {
        cleanProgram = buildProgram(Shaders.VERTEX, Shaders.bayerClean(rawBits));
        cRaw = loc(cleanProgram, "uRaw");
        cSensorMax = loc(cleanProgram, "uSensorMax");
        cBlack = loc(cleanProgram, "uBlack");
        cRange = loc(cleanProgram, "uRange");
        cNoiseS = loc(cleanProgram, "uNoiseS");
        cNoiseO = loc(cleanProgram, "uNoiseO");
        cK = loc(cleanProgram, "uK");
        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        cleanTex = t[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, cleanTex);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R16UI, rw, rh);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        GLES30.glGenFramebuffers(1, t, 0);
        cleanFbo = t[0];
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, cleanFbo);
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, cleanTex, 0);
        int st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
        Log.i(TAG, "spike filter " + (st == GLES30.GL_FRAMEBUFFER_COMPLETE ? "ready" : "fbo 0x" + Integer.toHexString(st)));
    }

    /** Clips isolated same-colour RAW spikes in the uploaded region into cleanTex (RAW16 codes). */
    private void runClean(TotalCaptureResult res, int x0, int y0, int w, int h) {
        float[] black = res.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        if (black == null) black = staticBlack;
        Integer wl = res.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        int white = wl == null ? staticWhite : wl;
        float[] range = new float[4], ns = new float[4], no = new float[4];
        android.util.Pair<Double, Double>[] np = res.get(CaptureResult.SENSOR_NOISE_PROFILE);
        double cal2 = noiseCal * noiseCal;
        for (int pos = 0; pos < 4; pos++) {
            range[pos] = white - black[pos];
            // the profile lists R, Gr, Gb, B (canonical order); map to this CFA's 2x2 sites
            int ch = pos == rPos ? 0 : pos == bPos ? 3 : (pos >> 1) == (rPos >> 1) ? 1 : 2;
            double S, O;
            if (np != null && np.length >= 4) {
                S = np[ch].first;
                O = np[ch].second;
            } else {
                Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
                double k = (iso == null ? 400 : iso) / 100.0;
                S = 5.8e-5 * k;
                O = 1.5e-8 * k * k;
            }
            ns[pos] = (float) (S * cal2);
            no[pos] = (float) (O * cal2);
        }
        GLES30.glUseProgram(cleanProgram);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, cleanFbo);
        GLES30.glViewport(x0, y0, w, h);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex[texIndex]);
        GLES30.glUniform1i(cRaw, 0);
        GLES30.glUniform2i(cSensorMax, setup.rawSize.getWidth() - 1, setup.rawSize.getHeight() - 1);
        GLES30.glUniform4fv(cBlack, 1, black, 0);
        GLES30.glUniform4fv(cRange, 1, range, 0);
        GLES30.glUniform4fv(cNoiseS, 1, ns, 0);
        GLES30.glUniform4fv(cNoiseO, 1, no, 0);
        // only where spikes occur: relative noise at mid grey above ~4% (roughly ISO 500 and up)
        double rel = Math.sqrt(Math.max(0, noiseS * 0.18 + noiseO)) / 0.18;
        GLES30.glUniform1f(cK, rel > 0.04 ? spikeK : 0f);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    /** BASE pass: bw x bh cells of log2 luminance over the given texture (LogC3 or linear). */
    private void drawBase(int tex, int texW, int texH, boolean linear, float cellW, float cellH, int bw, int bh) {
        GLES30.glUseProgram(baseProgram);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, baseFbo);
        GLES30.glViewport(0, 0, bw, bh);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glUniform1i(baseLog, 2);
        GLES30.glUniform2f(baseTexSize, texW, texH);
        GLES30.glUniform2f(baseCell, cellW, cellH);
        GLES30.glUniform1i(baseLinear, linear ? 1 : 0);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0);
    }

    private void logFrame(long ts, TotalCaptureResult res, double alpha, int bx, int by, int bw, int bh) {
        if (frameLog == null) return;
        Long exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Long skew = res.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
        try {
            frameLog.write(ts + "," + exp + "," + skew + "," + (float) alpha + "," + bx + "," + by + ","
                    + bw + "," + bh + "\n");
        } catch (IOException e) {
            frameLog = null;
        }
    }

    private volatile int padLeft, padRight, padBottom;

    /** Space the UI's bars take: the picture fits in what is left (never under a control). */
    void setPreviewPadding(int left, int right, int bottom) {
        padLeft = left;
        padRight = right;
        padBottom = bottom;
    }

    /**
     * Picture rectangle in the preview surface, GL convention (origin bottom-left): fitted in
     * the area right of padL, left of padR and above padB, centred there. The UI computes
     * the same one.
     */
    static int[] fitViewport(int w, int h, int padL, int padR, int padB, int outW, int outH) {
        int aw = Math.max(1, w - padL - padR), ah = Math.max(1, h - padB);
        int vh = ah, vw = (int) ((long) vh * outW / outH);
        if (vw > aw) {
            vw = aw;
            vh = (int) ((long) vw * outH / outW);
        }
        return new int[]{padL + (aw - vw) / 2, padB + (ah - vh) / 2, vw, vh};
    }

    private int[] previewViewport() {
        return fitViewport(previewW, previewH, padLeft, padRight, padBottom, setup.outW, setup.outH);
    }

    private void warpDraw(int x, int y, int vw, int vh) {
        GLES30.glViewport(x, y, vw, vh);
        GLES30.glUniform2i(w.viewOrigin, x, y);
        GLES30.glUniform2f(w.viewSize, vw, vh);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    private void finishStats(long t0, long t1) {
        long t2 = System.nanoTime();
        statFrames++;
        statUploadNs += t1 - t0;
        statDrawNs += t2 - t1;
        logStats();
    }

    /** Upload a sensor rectangle (x0 multiple of 4) into the raw texture at its own position. */
    private void upload(Image.Plane plane, int x0, int y0, int wPx, int hPx) {
        int rw = setup.rawSize.getWidth(), rh = setup.rawSize.getHeight();
        x0 = Math.max(0, x0) & ~3;
        y0 = Math.max(0, y0);
        wPx = Math.min(wPx, rw - x0) & ~3;
        hPx = Math.min(hPx, rh - y0);
        ByteBuffer buf = plane.getBuffer();
        // A packed buffer mislabelled RAW16 has rows of width*10/8 bytes, whatever stride says.
        int rowStride = packedAsRaw16 ? rw * 5 / 4 : plane.getRowStride();
        texIndex ^= 1;
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, rawTex[texIndex]);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, rawBits == 16 ? 2 : 1);
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, rawBits == 16 ? rowStride / 2 : rowStride);
        buf.position(y0 * rowStride + bytesForPixels(x0));
        if (rawBits == 16) {
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, x0, y0, wPx, hPx,
                    GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_SHORT, buf);
        } else {
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, bytesForPixels(x0), y0, bytesForPixels(wPx), hPx,
                    GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_BYTE, buf);
        }
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0);
    }

    private void updateShading(TotalCaptureResult res) {
        LensShadingMap map = res.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadeTex);
        if (map == null) {
            if (shadeCols == 0) {
                shadeCols = shadeRows = 1;
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, 1, 1, 0,
                        GLES30.GL_RGBA, GLES30.GL_FLOAT, FloatBuffer.wrap(new float[]{1, 1, 1, 1}));
                setLinear();
                Log.w(TAG, "no lens shading map from this HAL; shading correction disabled");
            }
        } else {
            int c = map.getColumnCount(), r = map.getRowCount();
            float[] gains = new float[c * r * 4];
            map.copyGainFactors(gains, 0);
            if (c != shadeCols || r != shadeRows) {
                shadeCols = c;
                shadeRows = r;
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, c, r, 0,
                        GLES30.GL_RGBA, GLES30.GL_FLOAT, FloatBuffer.wrap(gains));
                setLinear();
                Log.i(TAG, "lens shading map " + c + "x" + r);
            } else {
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, c, r,
                        GLES30.GL_RGBA, GLES30.GL_FLOAT, FloatBuffer.wrap(gains));
            }
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
    }

    private static void setLinear() {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    }

    /** As-shot neutral; falls back to the inverse of the AWB gains, then to no white balance. */
    private static float[] neutralOf(TotalCaptureResult res) {
        Rational[] nr = res.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT);
        if (nr != null) {
            float[] n = {nr[0].floatValue(), nr[1].floatValue(), nr[2].floatValue()};
            if (n[0] > 0 && n[1] > 0 && n[2] > 0 && !Float.isNaN(n[0] + n[1] + n[2])) return n;
        }
        RggbChannelVector g = res.get(CaptureResult.COLOR_CORRECTION_GAINS);
        if (g != null && g.getRed() > 0 && g.getBlue() > 0) {
            float gg = 0.5f * (g.getGreenEven() + g.getGreenOdd());
            return new float[]{gg / g.getRed(), 1f, gg / g.getBlue()};
        }
        return new float[]{1, 1, 1};
    }

    /** Develop-program uniforms that change per frame (leaves the develop program bound). */
    private void setFrameUniforms(TotalCaptureResult res, long ts) {
        // stepless exposure: the gap between the smooth AE value and what the sensor shot at
        float trim = 1f;
        if (ae != null && rawActive() && ae.started()) {
            Long exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
            if (exp != null && iso != null) trim = (float) ae.trimGain(ts, Math.log((double) exp * iso) / Math.log(2));
        }
        float exposure = this.exposure * trim;
        updateNoise(res, exposure);
        float[] black = res.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        if (black == null) black = staticBlack;
        Integer wl = res.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        int white = wl == null ? staticWhite : wl;

        float[] neutral = neutralOf(res);
        // While recording, white balance eases (~1.5 s) instead of following every AWB step,
        // so the colour never visibly hunts mid-clip.
        if (recording && smoothNeutral != null) {
            for (int i = 0; i < 3; i++) smoothNeutral[i] += (neutral[i] - smoothNeutral[i]) / 36f;
        } else {
            smoothNeutral = neutral.clone();
        }
        neutral = smoothNeutral.clone();
        float[] gain = {neutral[1] / neutral[0], 1f, neutral[1] / neutral[2]};
        float[] scale = new float[4];
        for (int i = 0; i < 4; i++) {
            int ch = i == rPos ? 0 : i == bPos ? 2 : 1;
            scale[i] = gain[ch] / (white - black[i]);
        }

        float[] ccm;
        float[] isp = "isp".equals(matrixSource) ? vendorCcm(res) : null;
        if (isp != null) {
            ccm = color.fromIspCcm(isp, exposure);
        } else if ("fm".equals(matrixSource) && color.hasForwardMatrix()) {
            ccm = color.cameraToAwg3(neutral, exposure);
        } else if (color.hasColorMatrix()) {
            ccm = color.cameraToAwg3Auto(neutral, exposure);
        } else {
            // no usable calibration for this lens: the ISP's own per-frame matrix - the vendor's
            // (OnePlus / OPPO publish the real one only there), else the standard one if it is
            // not just identity, else a typical phone-sensor matrix so colour is never grey
            float[] v = vendorCcm(res);
            ColorSpaceTransform t = res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
            if (v != null && !nearIdentity(v)) {
                ccm = color.fromIspCcm(v, exposure);
            } else if (t != null && !nearIdentity(ColorMath.toArray(t))) {
                ccm = color.fromSrgbTransform(t, exposure);
            } else {
                ccm = color.fromIspCcm(TYPICAL_CCM, exposure);
            }
        }
        logIspCcm(res);
        chanNoise = colourNoiseGain(ccm, gain);

        GLES30.glUseProgram(program);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, spikeFilter ? cleanTex : rawTex[texIndex]);
        GLES30.glUniform1i(u.raw, 0);
        GLES30.glUniform1i(u.shade, 1);
        GLES30.glUniform2f(u.shadeSize, shadeCols, shadeRows);
        GLES30.glUniform2i(u.sensorMax, setup.rawSize.getWidth() - 1, setup.rawSize.getHeight() - 1);
        GLES30.glUniform2f(u.sensorSize, setup.rawSize.getWidth(), setup.rawSize.getHeight());
        GLES30.glUniform4fv(u.black, 1, black, 0);
        GLES30.glUniform4fv(u.scale, 1, scale, 0);
        GLES30.glUniform3f(u.clip, gain[0], gain[1], gain[2]);
        GLES30.glUniform1i(u.rPosU, rPos);
        GLES30.glUniform1i(u.bPosU, bPos);
        GLES30.glUniformMatrix3fv(u.ccm, 1, true, ccm, 0);
        // backlight shadow lift (Rec.709 output only; a log file keeps the scene as shot)
        boolean lift = ae != null && rawActive() && ae.started() && !recordLog && !autoTone;
        GLES30.glUniform1f(u.lift, lift ? (float) ae.liftGain() : 1f);
        GLES30.glUniform1f(u.liftAnchor, liftAnchor * trim);
    }

    /**
     * Noise of each AWG3 channel relative to luminance noise, from the colour matrix and the
     * white-balance gains (independent per-channel RAW noise, scaled by its WB gain, through the
     * matrix). A strong colour matrix amplifies colour noise far more than brightness noise.
     */
    private static float[] colourNoiseGain(float[] ccm, float[] gain) {
        double[] Y = {0.291954, 0.823841, -0.115795};
        double yy = 0;
        double[] yr = new double[3];
        for (int j = 0; j < 3; j++) {
            yr[j] = (Y[0] * ccm[j] + Y[1] * ccm[3 + j] + Y[2] * ccm[6 + j]) * gain[j];
            yy += yr[j] * yr[j];
        }
        float[] f = new float[3];
        for (int c = 0; c < 3; c++) {
            double cc = 0;
            for (int j = 0; j < 3; j++) cc += Math.pow(ccm[c * 3 + j] * gain[j], 2);
            f[c] = (float) Math.max(0.5, Math.min(6.0, Math.sqrt(cc / Math.max(yy, 1e-12))));
        }
        return f;
    }

    /**
     * Expected noise for NoiseReducer, from the HAL's per-frame noise profile (var = S x + O for
     * RAW normalized to [0, 1]); green channels, carried to working units: Y = exposure x.
     */
    private void updateNoise(TotalCaptureResult res, float exposure) {
        android.util.Pair<Double, Double>[] np = res.get(CaptureResult.SENSOR_NOISE_PROFILE);
        double S, O;
        if (np != null && np.length >= 3) {
            S = 0.5 * (np[1].first + np[2].first);
            O = 0.5 * (np[1].second + np[2].second);
        } else {
            // no profile from this HAL: the shape measured on a 1/1.56" phone sensor (IMX890:
            // S 5.8e-5, O 1.5e-8 per 100 ISO), scaled by ISO
            Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
            double k = (iso == null ? 400 : iso) / 100.0;
            S = 5.8e-5 * k;
            O = 1.5e-8 * k * k;
        }
        double g2 = NoiseReducer.noiseGain * NoiseReducer.noiseGain * noiseCal * noiseCal;
        noiseS = (float) (exposure * S * g2);
        noiseO = (float) (exposure * (double) exposure * O * g2);
        if (!noiseWarned || frameCount % 240 == 0) {
            noiseWarned = true;
            Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
            Log.i(TAG, String.format(Locale.US, "NR: noise profile %s S %.3g O %.3g (ISO %s)",
                    np != null ? "HAL" : "estimated", S, O, iso));
        }
    }

    /** A typical phone CMOS sensor's white-balanced camera -> linear sRGB matrix (D65). */
    private static final float[] TYPICAL_CCM = {1.72f, -0.60f, -0.12f, -0.24f, 1.52f, -0.28f, 0.02f, -0.56f, 1.54f};

    private static boolean nearIdentity(float[] m) {
        if (m == null || m.length < 9) return true;
        float err = 0;
        for (int i = 0; i < 9; i++) err = Math.max(err, Math.abs(m[i] - (i % 4 == 0 ? 1f : 0f)));
        return err < 0.06f;
    }

    /** OnePlus/OPPO publish the ISP's runtime colour matrix as com.oplus.custom.ccm.sync.result. */
    private float[] vendorCcm(TotalCaptureResult res) {
        if (!vendorCcmSearched) {
            vendorCcmSearched = true;
            for (CaptureResult.Key<?> k : res.getKeys()) {
                if (k.getName().endsWith("custom.ccm.sync.result")) vendorCcmKey = k;
            }
            Log.i(TAG, "vendor CCM key: " + (vendorCcmKey == null ? "not available" : vendorCcmKey.getName()));
        }
        if (vendorCcmKey == null) return null;
        Object v = res.get(vendorCcmKey);
        return v instanceof float[] && ((float[]) v).length == 9 ? (float[]) v : null;
    }

    /**
     * Every 5 s: the standard per-frame camera->sRGB matrix, and any vendor OIS/EIS result keys
     * (whether OIS lens-shift data reaches third-party apps decides how EIS must treat OIS).
     */
    private void logIspCcm(TotalCaptureResult res) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastIspLog < 5000) return;
        lastIspLog = now;
        ColorSpaceTransform t = res.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        if (t != null) {
            Rational[] r = new Rational[9];
            t.copyElements(r, 0);
            float[] m = new float[9];
            for (int i = 0; i < 9; i++) m[i] = r[i].floatValue();
            Log.i(TAG, "ISP COLOR_CORRECTION_TRANSFORM cam->sRGB=" + Arrays.toString(m));
        }
        Integer ois = res.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE);
        StringBuilder sb = new StringBuilder("OIS mode=" + ois);
        for (CaptureResult.Key<?> k : res.getKeys()) {
            String n = k.getName().toLowerCase(Locale.US);
            if (!(n.contains("ois") || n.contains("eis"))) continue;
            Object v = res.get(k);
            String s = v instanceof float[] ? Arrays.toString((float[]) v)
                    : v instanceof int[] ? Arrays.toString((int[]) v)
                    : v instanceof long[] ? Arrays.toString((long[]) v)
                    : v instanceof byte[] ? "byte[" + ((byte[]) v).length + "]" : String.valueOf(v);
            sb.append(" | ").append(k.getName()).append('=').append(s);
        }
        Log.i(TAG, sb.toString());
        if (recording && eis) Log.i(TAG, "EIS " + stab.describe());
    }

    private void draw(int x, int y, int w, int h, float step) {
        GLES30.glViewport(x, y, w, h);
        GLES30.glUniform2i(u.viewOrigin, x, y);
        GLES30.glUniform2i(u.outSize, w, h);
        GLES30.glUniform1f(u.step, step);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3);
    }

    private void logStats() {
        long now = SystemClock.elapsedRealtime();
        if (now - statStart < 1000) return;
        float sec = (now - statStart) / 1000f;
        Log.i(TAG, String.format(Locale.US,
                "stats fps=%.2f encoded=%.2f dropped=%d uploadMs=%.2f drawMs=%.2f recording=%b queued=%d",
                statFrames / sec, statEncoded / sec, statDropped,
                statFrames == 0 ? 0 : statUploadNs / 1e6 / statFrames,
                statFrames == 0 ? 0 : statDrawNs / 1e6 / statFrames, recording, queue.size()));
        statStart = now;
        statFrames = statEncoded = statDropped = 0;
        statUploadNs = statDrawNs = 0;
    }

    // ---------------------------------------------------------------- recording

    void toggleRecording() {
        gl.post(() -> setRecordingNow(!recording));
    }

    /** Idempotent start/stop, so a lost or early command cannot invert the state. */
    void setRecording(boolean on) {
        gl.post(() -> setRecordingNow(on));
    }

    private void setRecordingNow(boolean on) {
        if (setup == null || (!ispViewfinder && program == 0)) {
            Log.w(TAG, "record command ignored: camera not ready yet");
            return;
        }
        if (on && !recording) {
            if (noise != null) noise.reset();
            startRecording();
        }
        else if (!on && recording) stopRecording();
    }

    private void startRecording() {
        try {
            File dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES);
            String name = new SimpleDateFormat("yyMMdd_HHmmss", Locale.US).format(new Date());
            boolean hlg = recordLog && logCurve == ColorSpaces.HLG;
            String base = "VU_" + name + (recordLog ? "_" + logTag : "_Rec709");
            outputPath = "DCIM/VidUltra/" + base + ".mp4";
            android.content.ContentResolver cr = context.getContentResolver();
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, base + ".mp4");
            cv.put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            cv.put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "DCIM/VidUltra");
            cv.put(android.provider.MediaStore.Video.Media.IS_PENDING, 1);
            android.net.Uri uri = cr.insert(android.provider.MediaStore.Video.Media.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
            android.os.ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "rw");
            muxer = new Muxer(pfd.getFileDescriptor(), outputPath, () -> {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
                android.content.ContentValues done = new android.content.ContentValues();
                done.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0);
                cr.update(uri, done, null, null);
                Listener l = listener;
                if (l != null) l.onSaved(uri);
            });

            MediaFormat f = MediaFormat.createVideoFormat(setup.mime, setup.outW, setup.outH);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, setup.bitrate);
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, setup.bitrateMode);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, setup.fps);
            f.setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 30f / setup.fps);
            f.setInteger(MediaFormat.KEY_PROFILE, setup.tenBit ? MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                    : MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
            // Rec.709 look: BT.709. Log: wide-gamut container tags as cinema cameras / the
            // reference app write them (graders interpret log by name). HLG: BT.2100 HLG.
            f.setInteger(MediaFormat.KEY_COLOR_STANDARD,
                    recordLog ? MediaFormat.COLOR_STANDARD_BT2020 : MediaFormat.COLOR_STANDARD_BT709);
            f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, hlg ? MediaFormat.COLOR_TRANSFER_HLG
                    : MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
            f.setInteger(MediaFormat.KEY_PRIORITY, 0);
            videoCodec = MediaCodec.createByCodecName(setup.encoderName);
            videoCodec.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec c, int i) {
                }

                @Override
                public void onOutputBufferAvailable(MediaCodec c, int i, MediaCodec.BufferInfo info) {
                    muxer.write(true, c.getOutputBuffer(i), info);
                    c.releaseOutputBuffer(i, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        c.stop();
                        c.release();
                        muxer.end(true);
                    }
                }

                @Override
                public void onError(MediaCodec c, MediaCodec.CodecException e) {
                    Log.e(TAG, "video codec", e);
                }

                @Override
                public void onOutputFormatChanged(MediaCodec c, MediaFormat format) {
                    Log.i(TAG, "video format " + format);
                    muxer.addTrack(true, format);
                }
            }, enc);
            videoCodec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codecInput = videoCodec.createInputSurface();
            videoCodec.start();
            Log.i(TAG, "encoder " + videoCodec.getName());

            // the HLG/BT.2020 surface tag is only for the log file; Rec.709 uses the default
            int[] attrs = hlg && hlgColorspace
                    ? new int[]{EGL_GL_COLORSPACE_KHR, EGL_GL_COLORSPACE_BT2020_HLG_EXT, EGL14.EGL_NONE}
                    : new int[]{EGL14.EGL_NONE};
            encoderSurface = EGL14.eglCreateWindowSurface(display, config, codecInput, attrs, 0);
            if (encoderSurface == EGL14.EGL_NO_SURFACE) {
                encoderSurface = EGL14.eglCreateWindowSurface(display, config, codecInput,
                        new int[]{EGL14.EGL_NONE}, 0);
            }
            // One time base for picture and sound; frames captured before it are skipped.
            recordBaseNs = SystemClock.elapsedRealtimeNanos();
            if (vision != null) vision.reset();
            // keep the live view's exposure running into the clip; otherwise start from the HAL's
            if (ae != null && ispViewfinder) ae.reset();
            if (noise != null) noise.reset();
            if (stab != null) stab.enabled = eis;
            streamRaw(true);
            audio = new AudioEncoder(muxer, recordBaseNs);
            audio.start();
            // Sidecars for offline analysis (OIS detection, gyro/camera clock offset, shake score),
            // test builds only: they cost storage.
            if (sidecars) {
                frameLog = new BufferedWriter(new FileWriter(new File(dir, base + ".frames.csv")));
                frameLog.write("ts_ns,exposure_ns,skew_ns,alpha,box_x,box_y,box_w,box_h\n");
                BufferedWriter gl = new BufferedWriter(new FileWriter(new File(dir, base + ".gyro.csv")));
                gl.write("t_ns,wx,wy,wz\n");
                gyro.setLog(gl);
            }
            if (sidecars && vision != null) {
                BufferedWriter vl = new BufferedWriter(new FileWriter(new File(dir, base + ".vision.csv")));
                vl.write("t_ns,meas_dy,gyro_dy,resid_dy,used,path_y\n");
                vision.log = vl;
            }
            recording = true;
            Log.i(TAG, "recording to " + outputPath + " eis=" + eis);
            Listener l = listener;
            if (l != null) l.onRecordingChanged(true);
        } catch (Exception e) {
            Log.e(TAG, "startRecording", e);
        }
    }

    private void stopRecording() {
        // Frames still waiting for look-ahead are rendered with whatever future is known.
        while (!queue.isEmpty()) processStabilized(queue.poll());
        recording = false;
        if (ae != null && ispViewfinder) ae.reset();   // the viewfinder goes back to the HAL's AE
        if (noise != null) noise.reset();
        streamRaw(false);
        if (vision != null) Log.i(TAG, "vision OIS: used " + vision.framesUsed + " frames, rejected " + vision.framesRejected);
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext);
        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, encoderSurface);
            encoderSurface = EGL14.EGL_NO_SURFACE;
        }
        videoCodec.signalEndOfInputStream();
        audio.stop();
        codecInput.release();
        gyro.setLog(null);
        if (vision != null && vision.log != null) {
            java.io.Writer vl = vision.log;
            visionH.post(() -> {   // after the vision thread's last frame
                vision.log = null;
                try {
                    vl.close();
                } catch (IOException ignored) {
                }
            });
        }
        if (frameLog != null) {
            try {
                frameLog.close();
            } catch (IOException ignored) {
            }
            frameLog = null;
        }
        Log.i(TAG, "stopped recording " + outputPath);
        Listener l = listener;
        if (l != null) l.onRecordingChanged(false);
    }

    /**
     * Stops recording, closes the camera and frees GL resources, then the worker threads. Blocks
     * up to timeoutMs for the camera to close, so a restart (new settings) can reopen it.
     */
    void release(long timeoutMs) {
        if (released) return;
        released = true;
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        gl.post(() -> {
            try {
                if (recording) stopRecording();
                for (Pending p : queue) p.img.close();
                queue.clear();
                if (gyro != null) gyro.stop();
                if (session != null) session.close();
                if (device != null) device.close();
                if (reader != null) reader.close();
                if (ispReader != null) ispReader.close();
                if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                    // (the display itself is shared with the UI's renderer: not terminated)
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (previewSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, previewSurface);
                    if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer);
                    if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext);
                    previewSurface = pbuffer = EGL14.EGL_NO_SURFACE;
                    eglContext = EGL14.EGL_NO_CONTEXT;
                }
            } catch (Exception e) {
                Log.e(TAG, "release", e);
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        // give the encoder / audio threads a moment to finish a clip, then stop all threads
        enc.postDelayed(() -> {
            glThread.quitSafely();
            camThread.quitSafely();
            encThread.quitSafely();
            visionThread.quitSafely();
        }, 1500);
    }
}
