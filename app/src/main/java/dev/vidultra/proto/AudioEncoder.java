package dev.vidultra.proto;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/** 48 kHz stereo AAC-LC at 256 kbps, matching Motion Cam Pro's audio track. */
final class AudioEncoder implements Runnable {
    private static final String TAG = "VIDULTRA";
    private static final int RATE = 48000;
    private static final int CHANNELS = 2;

    private final Muxer muxer;
    // Recording time base (CLOCK_BOOTTIME ns) shared with the video track, so the stabilizer's
    // frame delay cannot shift audio against picture.
    private final long baseNs;
    private volatile boolean running = true;
    private final Thread thread = new Thread(this, "audio");
    /** Peak level of the latest buffer, dBFS (-90 = silence / not running). */
    volatile float levelDb = -90;

    AudioEncoder(Muxer muxer, long baseNs) {
        this.muxer = muxer;
        this.baseNs = baseNs;
    }

    void start() {
        thread.start();
    }

    void stop() {
        running = false;
        try {
            thread.join(3000);
        } catch (InterruptedException ignored) {
        }
    }

    @SuppressLint("MissingPermission")
    @Override
    public void run() {
        int minBuf = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.CAMCORDER, RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
        MediaCodec codec;
        try {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CHANNELS);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            f.setInteger(MediaFormat.KEY_BIT_RATE, 256000);
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (IOException e) {
            Log.e(TAG, "audio encoder", e);
            muxer.end(false);
            return;
        }
        codec.start();
        rec.startRecording();
        long startUs = (android.os.SystemClock.elapsedRealtimeNanos() - baseNs) / 1000;
        long frames = 0;
        boolean eosSent = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            if (!eosSent) {
                int in = codec.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer b = codec.getInputBuffer(in);
                    long pts = startUs + frames * 1_000_000L / RATE;
                    if (!running) {
                        codec.queueInputBuffer(in, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        eosSent = true;
                    } else {
                        int n = rec.read(b, Math.min(b.capacity(), 4096));
                        if (n > 0) {
                            // peak level for the viewfinder meter (dBFS, both channels)
                            int peak = 1;
                            for (int k = 0; k + 1 < n; k += 2) {
                                int v = Math.abs((short) ((b.get(k) & 255) | (b.get(k + 1) << 8)));
                                if (v > peak) peak = v;
                            }
                            levelDb = (float) (20 * Math.log10(peak / 32768.0));
                            codec.queueInputBuffer(in, 0, n, pts, 0);
                            frames += n / (2 * CHANNELS);
                        } else {
                            codec.queueInputBuffer(in, 0, 0, pts, 0);
                        }
                    }
                }
            }
            int out = codec.dequeueOutputBuffer(info, eosSent ? 10_000 : 0);
            if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxer.addTrack(false, codec.getOutputFormat());
            } else if (out >= 0) {
                muxer.write(false, codec.getOutputBuffer(out), info);
                codec.releaseOutputBuffer(out, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
        rec.stop();
        rec.release();
        codec.stop();
        codec.release();
        muxer.end(false);
    }
}
