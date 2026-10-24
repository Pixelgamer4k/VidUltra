package dev.vidultra.proto;

import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

/**
 * Everything that differs between phones, decided once from the device's own capability
 * metadata: which camera, which RAW format and size, which ISP side stream, frame rate,
 * output size / crop / scale, and which hardware HEVC Main10 encoder.
 */
final class DeviceSetup {
    private static final String TAG = "VIDULTRA";

    // Requested output (from Settings); the closest the phone supports is used.
    static int targetW = 3840, targetH = 2160, targetFps = 24;
    static double bitsPerPixel = 0.5;            // per frame: 100 Mbps at 4K24
    static int codecPref;                        // 0 auto (HEVC 10-bit if any), 1 HEVC 10-bit, 2 H.264 8-bit
    // Raw pixels kept around the crop for the 5x5 demosaic kernel.
    static final int MARGIN_X = 4, MARGIN_Y = 2;

    String cameraId;
    boolean front;

    /** A lens the RAW pipeline can use: camera id, facing, true focal length, 35 mm equivalent. */
    static final class Lens {
        final String id, name;
        final boolean front;
        final float focalMm, equivMm;

        Lens(String id, String name, boolean front, float focalMm, float equivMm) {
            this.id = id;
            this.name = name;
            this.front = front;
            this.focalMm = focalMm;
            this.equivMm = equivMm;
        }

        String label() {
            return Math.round(equivMm) + "mm";
        }
    }

    /**
     * Every camera this app can open with RAW, as lenses: back ones sorted wide to tele, then
     * front. A logical multi-camera whose lenses are also listed on their own is skipped (it
     * would switch lenses by itself mid-shot). 35 mm equivalent = focal x 43.27 / sensor diagonal.
     */
    static java.util.List<Lens> lenses(CameraManager cm) {
        java.util.List<Lens> back = new java.util.ArrayList<>(), frontL = new java.util.ArrayList<>();
        try {
            java.util.Set<String> listed = new java.util.HashSet<>(java.util.Arrays.asList(cm.getCameraIdList()));
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                boolean raw = false, logical = false;
                for (int cap : caps) {
                    if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) raw = true;
                    if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) logical = true;
                }
                if (!raw || largestRaw(c) == null) {
                    Log.i(TAG, "lens " + id + ": no RAW, not offered");
                    continue;
                }
                if (logical && listed.containsAll(c.getPhysicalCameraIds())) continue;
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                float[] fl = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                SizeF ph = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
                if (fl == null || fl.length == 0 || ph == null) continue;
                float diag = (float) Math.hypot(ph.getWidth(), ph.getHeight());
                float eq = fl[0] * 43.27f / diag;
                boolean isFront = facing != null && facing == CameraMetadata.LENS_FACING_FRONT;
                Lens l = new Lens(id, isFront ? "Front" : "", isFront, fl[0], eq);
                if (isFront) frontL.add(l); else if (facing != null && facing == CameraMetadata.LENS_FACING_BACK) back.add(l);
            }
        } catch (Exception e) {
            Log.e(TAG, "lens list", e);
        }
        back.sort((a, b) -> Float.compare(a.equivMm, b.equivMm));
        // the main lens: closest to the classic 24-28 mm
        Lens main = null;
        for (Lens l : back) if (main == null || Math.abs(l.equivMm - 26) < Math.abs(main.equivMm - 26)) main = l;
        java.util.List<Lens> out = new java.util.ArrayList<>();
        for (Lens l : back) {
            float r = main == null ? 1 : l.equivMm / main.equivMm;
            String n = l == main ? "Wide" : r < 0.85f ? "Ultra Wide" : r > 1.5f ? "Telephoto" : "Wide";
            out.add(new Lens(l.id, n, false, l.focalMm, l.equivMm));
        }
        out.addAll(frontL);
        for (Lens l : out) Log.i(TAG, "lens " + l.id + ": " + l.name + " " + l.label() + " (" + l.focalMm + " mm)");
        return out;
    }
    CameraCharacteristics chars;
    int rawFormat;          // ImageFormat.RAW10 / RAW12 / RAW_SENSOR
    Size rawSize;
    Size ispSize;           // small YUV stream that makes the HAL publish AWB / shading metadata
    int fps;
    int outW, outH;
    int cropX, cropY, cropW, cropH;
    float step;             // raw pixels per output pixel
    int uploadX, uploadY, uploadW, uploadH;
    boolean flip180;
    String encoderName;
    String mime;            // MediaFormat.MIMETYPE_VIDEO_HEVC or _AVC
    boolean tenBit;         // HEVC Main10 (else 8-bit)
    // what this phone can do, for the settings UI
    boolean[] resOk = new boolean[Settings.RES_NAMES.length];
    boolean[] aspectOk = new boolean[Settings.ASPECT_NAMES.length];
    boolean[] fpsOk = new boolean[Settings.FPS.length];
    boolean hasHevc10, hasAvc;
    int activeW, activeH;   // active array, for focus / metering regions
    int bitrate;
    int bitrateMode;

    // Test overrides (launch extras) so other phones' code paths can be exercised on one device.
    int forceFormat;        // 0 = auto
    int forceOutW, forceOutH;

    static DeviceSetup choose(CameraManager cm, int displayRotationDeg, int forceFormat,
                              int forceOutW, int forceOutH) throws Exception {
        DeviceSetup s = new DeviceSetup();
        s.forceFormat = forceFormat;
        s.forceOutW = forceOutW;
        s.forceOutH = forceOutH;
        s.pickCamera(cm);
        s.pickFps();
        s.pickRaw();
        s.pickIspStream();
        s.pickEncoderAndGeometry();
        s.findCapabilities();
        android.graphics.Rect aa = s.chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        s.activeW = aa == null ? s.rawSize.getWidth() : aa.width();
        s.activeH = aa == null ? s.rawSize.getHeight() : aa.height();
        Integer so = s.chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        // Android's rule: back cameras turn by (orientation - display), front ones by
        // (orientation + display), because a front camera faces the other way
        int o = so == null ? 90 : so;
        int rel = s.front ? (o + displayRotationDeg) % 360 : (o - displayRotationDeg + 360) % 360;
        s.flip180 = rel == 180;
        Log.i(TAG, "DeviceSetup: camera=" + s.cameraId + " raw=" + fmtName(s.rawFormat) + " " + s.rawSize
                + " isp=" + s.ispSize + " fps=" + s.fps + " out=" + s.outW + "x" + s.outH
                + " crop=" + s.cropW + "x" + s.cropH + "@" + s.cropX + "," + s.cropY + " step=" + s.step
                + " flip180=" + s.flip180 + " encoder=" + s.encoderName + (s.tenBit ? " 10-bit" : " 8-bit")
                + " bitrate=" + s.bitrate
                + " mode=" + s.bitrateMode);
        return s;
    }

    static String fmtName(int f) {
        return f == ImageFormat.RAW10 ? "RAW10" : f == ImageFormat.RAW12 ? "RAW12" : "RAW16";
    }

    /**
     * Back-facing, RAW-capable camera with the largest RAW stream. Physical (non-logical)
     * cameras win ties, so the lens never switches mid-shot and metadata stays per-sensor.
     */
    private void pickCamera(CameraManager cm) throws Exception {
        if (forceCameraId != null) {
            try {
                cameraId = forceCameraId;
                chars = cm.getCameraCharacteristics(cameraId);
                Integer fc = chars.get(CameraCharacteristics.LENS_FACING);
                front = fc != null && fc == CameraMetadata.LENS_FACING_FRONT;
                Log.i(TAG, "camera chosen: " + cameraId + (front ? " (front)" : ""));
                return;
            } catch (Exception e) {
                Log.w(TAG, "camera " + forceCameraId + " unavailable, picking automatically");
                cameraId = null;
            }
        }
        long bestScore = -1;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics c = cm.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing == null || facing != CameraMetadata.LENS_FACING_BACK) continue;
            int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            boolean raw = false, logical = false;
            for (int cap : caps) {
                if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW) raw = true;
                if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) logical = true;
            }
            if (!raw) continue;
            Size biggest = largestRaw(c);
            if (biggest == null) continue;
            long score = (long) biggest.getWidth() * biggest.getHeight() * 2 + (logical ? 0 : 1);
            Log.i(TAG, "candidate camera " + id + " raw=" + biggest + " logical=" + logical);
            if (score > bestScore) {
                bestScore = score;
                cameraId = id;
                chars = c;
            }
        }
        if (cameraId == null) throw new IllegalStateException("no back camera with RAW capability");
    }

    private static Size largestRaw(CameraCharacteristics c) {
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size best = null;
        for (int f : new int[]{ImageFormat.RAW12, ImageFormat.RAW10, ImageFormat.RAW_SENSOR}) {
            Size[] sizes = map.getOutputSizes(f);
            if (sizes == null) continue;
            for (Size s : sizes)
                if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight())
                    best = s;
        }
        return best;
    }

    /** The requested frame rate if the camera runs it, else the nearest one it does. */
    private void pickFps() {
        fps = 30;
        int best = Integer.MAX_VALUE;
        for (int f : new int[]{targetFps, 24, 25, 30}) {
            if (fpsSupported(f) && Math.abs(f - targetFps) < best) {
                best = Math.abs(f - targetFps);
                fps = f;
            }
        }
    }

    private boolean fpsSupported(int f) {
        Range<Integer>[] ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        boolean ae = false;
        for (Range<Integer> r : ranges) if (r.getLower() <= f && r.getUpper() >= f) ae = true;
        if (!ae) return false;
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        for (int fmt : new int[]{ImageFormat.RAW12, ImageFormat.RAW10, ImageFormat.RAW_SENSOR}) {
            Size[] sizes = map.getOutputSizes(fmt);
            if (sizes == null) continue;
            for (Size sz : sizes)
                if (sz.getWidth() >= 1920 && map.getOutputMinFrameDuration(fmt, sz) <= 1_000_000_000L / f + 1_000_000) return true;
        }
        return false;
    }

    /**
     * Largest RAW size that sustains the frame rate. Packed formats are preferred (less memory
     * traffic); RAW12 over RAW10 because it keeps more sensor bits when the sensor has them.
     */
    private void pickRaw() {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        long frameNs = 1_000_000_000L / fps;
        int[] order = forceFormat != 0 ? new int[]{forceFormat}
                : new int[]{ImageFormat.RAW12, ImageFormat.RAW10, ImageFormat.RAW_SENSOR};
        for (int f : order) {
            Size[] sizes = map.getOutputSizes(f);
            if (sizes == null) continue;
            Size best = null;
            for (Size s : sizes) {
                long d = map.getOutputMinFrameDuration(f, s);
                if (d > frameNs + 1_000_000) continue;   // can't sustain fps
                if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight())
                    best = s;
            }
            if (best != null) {
                rawFormat = f;
                rawSize = best;
                return;
            }
        }
        throw new IllegalStateException("no RAW size sustains " + fps + " fps");
    }

    // Tuning: use the smallest YUV size the HAL offers for the metadata side stream.
    static boolean ispMin;
    // Test override: use this camera id instead of auto-picking (e.g. the ultra-wide as a
    // no-OIS control for the OIS detection test).
    static String forceCameraId;
    // Dataset mode: record the whole sensor (its own aspect, scaled), unstabilized, so
    // stabilization variants can be compared offline with the full margins the phone has.
    static boolean fullFov;

    /** Smallest YUV size of at least 1280 wide with the sensor's aspect ratio. */
    private void pickIspStream() {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (ispMin) {
            for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888))
                if (ispSize == null || (long) s.getWidth() * s.getHeight() < (long) ispSize.getWidth() * ispSize.getHeight())
                    ispSize = s;
            return;
        }
        float aspect = (float) rawSize.getWidth() / rawSize.getHeight();
        Size best = null, any = null;
        for (Size s : map.getOutputSizes(ImageFormat.YUV_420_888)) {
            if (s.getWidth() < 640) continue;
            if (any == null || s.getWidth() < any.getWidth()) any = s;
            float a = (float) s.getWidth() / s.getHeight();
            if (s.getWidth() >= 1280 && Math.abs(a - aspect) < 0.02f
                    && (best == null || s.getWidth() < best.getWidth())) best = s;
        }
        ispSize = best != null ? best : any;
    }

    private void pickEncoderAndGeometry() {
        int rw = rawSize.getWidth(), rh = rawSize.getHeight();
        int availW = (rw - 2 * MARGIN_X) & ~3, availH = (rh - 2 * MARGIN_Y) & ~1;

        // Output: the requested size, or the largest of that aspect an encoder takes (the pipeline
        // up- or down-samples from the sensor, so the field of view is the same either way).
        int ow = forceOutW > 0 ? forceOutW : targetW, oh = forceOutH > 0 ? forceOutH : targetH;
        double aspect = ow / (double) oh;
        boolean ok = false;
        for (int tries = 0; tries < 6 && !ok; tries++) {
            ok = pickEncoder(ow, oh);
            if (!ok) {
                ow = (int) Math.round(ow * 0.75) & ~7;
                oh = (int) Math.round(ow / aspect) & ~1;
            }
        }
        if (!ok) throw new IllegalStateException("no hardware video encoder for " + targetW + "x" + targetH);
        outW = ow;
        outH = oh;

        // Crop: 1:1 when the sensor is close to the output size (like Motion Cam Pro),
        // otherwise the widest 16:9 region scaled down so the field of view is preserved.
        if (fullFov) {
            cropW = availW;
            cropH = availH;
        } else if (availW <= outW * 1.15f && availW >= outW && availH >= outH) {
            cropW = outW;
            cropH = outH;
        } else {
            // the widest region of the output's aspect the sensor has
            cropW = (int) Math.min(availW, availH * aspect) & ~3;
            cropH = (int) Math.round(cropW / aspect) & ~1;
        }
        step = (float) cropW / outW;
        cropX = ((rw - cropW) / 2) & ~3;
        cropY = ((rh - cropH) / 2) & ~1;
        uploadX = cropX - MARGIN_X;
        uploadY = cropY - MARGIN_Y;
        uploadW = Math.min((cropW + 2 * MARGIN_X + 3) & ~3, rw - uploadX);
        uploadH = Math.min(cropH + 2 * MARGIN_Y, rh - uploadY);
    }

    /** Encoder for this size: HEVC Main10 (preferred), or H.264 8-bit when chosen or the only one. */
    private boolean pickEncoder(int w, int h) {
        if (codecPref != 2 && pickHevc10(w, h)) return true;
        return codecPref != 1 && pickAvc(w, h);
    }

    private boolean pickAvc(int w, int h) {
        MediaCodecInfo.CodecCapabilities caps = encoderCaps(MediaFormat.MIMETYPE_VIDEO_AVC, -1, w, h, fps);
        if (caps == null) return false;
        mime = MediaFormat.MIMETYPE_VIDEO_AVC;
        tenBit = false;
        setRate(caps, w, h);
        return true;
    }

    private void setRate(MediaCodecInfo.CodecCapabilities caps, int w, int h) {
        bitrate = caps.getVideoCapabilities().getBitrateRange().clamp((int) Math.min(Integer.MAX_VALUE,
                bitsPerPixel * w * h * fps));
        MediaCodecInfo.EncoderCapabilities ec = caps.getEncoderCapabilities();
        bitrateMode = ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                ? MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                : MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR;
    }

    /** Hardware encoder capabilities for mime at this size and rate (profile -1 = any), or null. */
    private MediaCodecInfo.CodecCapabilities encoderCaps(String type, int profile, int w, int h, int f) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder() || !info.isHardwareAccelerated() || info.isAlias()) continue;
            boolean has = false;
            for (String t : info.getSupportedTypes()) if (t.equalsIgnoreCase(type)) has = true;
            if (!has) continue;
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
            boolean prof = profile < 0;
            for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels) if (pl.profile == profile) prof = true;
            MediaCodecInfo.VideoCapabilities v = caps.getVideoCapabilities();
            if (!prof || v == null || !v.areSizeAndRateSupported(w, h, f)) continue;
            encoderName = info.getName();
            return caps;
        }
        return null;
    }

    /** Which resolutions / aspects / frame rates / codecs the settings UI may offer. */
    private void findCapabilities() {
        String keepName = encoderName, keepMime = mime;
        boolean keepTen = tenBit;
        int keepRate = bitrate, keepMode = bitrateMode;
        int availW = rawSize.getWidth() - 2 * MARGIN_X, availH = rawSize.getHeight() - 2 * MARGIN_Y;
        hasHevc10 = encoderCaps(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                1920, 1080, fps) != null;
        hasAvc = encoderCaps(MediaFormat.MIMETYPE_VIDEO_AVC, -1, 1920, 1080, fps) != null;
        for (int r = 0; r < resOk.length; r++) {
            int[] sz = Settings.size(r, 0);
            // the sensor must feed at least ~75% of the pixels (beyond that is upscaling)
            resOk[r] = sz[0] <= availW * 1.34 && sz[1] <= availH * 1.34 && pickEncoder(sz[0], sz[1]);
        }
        int res = 0;
        for (int r = 0; r < resOk.length; r++) if (Settings.RES_H[r] == targetH) res = r;
        for (int a = 0; a < aspectOk.length; a++) {
            int[] sz = Settings.size(res, a);
            aspectOk[a] = pickEncoder(sz[0], sz[1]);
        }
        for (int i = 0; i < fpsOk.length; i++) fpsOk[i] = fpsSupported(Settings.FPS[i]);
        encoderName = keepName;
        mime = keepMime;
        tenBit = keepTen;
        bitrate = keepRate;
        bitrateMode = keepMode;
    }

    /** Whether any hardware HEVC Main10 encoder exists (decides the 10/8-bit EGL config early). */
    static boolean deviceHasHevc10() {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder() || !info.isHardwareAccelerated()) continue;
            for (String t : info.getSupportedTypes()) {
                if (!t.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) continue;
                for (MediaCodecInfo.CodecProfileLevel pl : info.getCapabilitiesForType(t).profileLevels)
                    if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) return true;
            }
        }
        return false;
    }

    /** First hardware HEVC encoder that does Main10 at this size and frame rate. */
    private boolean pickHevc10(int w, int h) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder() || !info.isHardwareAccelerated() || info.isAlias()) continue;
            boolean hevc = false;
            for (String t : info.getSupportedTypes()) if (t.equalsIgnoreCase(MediaFormat.MIMETYPE_VIDEO_HEVC)) hevc = true;
            if (!hevc) continue;
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC);
            boolean main10 = false;
            for (MediaCodecInfo.CodecProfileLevel pl : caps.profileLevels)
                if (pl.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) main10 = true;
            MediaCodecInfo.VideoCapabilities v = caps.getVideoCapabilities();
            if (!main10 || v == null || !v.areSizeAndRateSupported(w, h, fps)) continue;
            encoderName = info.getName();
            mime = MediaFormat.MIMETYPE_VIDEO_HEVC;
            tenBit = true;
            setRate(caps, w, h);
            return true;
        }
        return false;
    }
}
