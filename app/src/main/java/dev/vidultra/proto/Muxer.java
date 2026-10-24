package dev.vidultra.proto;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** MP4 muxer that waits for both tracks before starting and buffers early samples. */
final class Muxer {
    private static final String TAG = "VIDULTRA";

    private static final class Pending {
        final boolean video;
        final ByteBuffer data;
        final MediaCodec.BufferInfo info;

        Pending(boolean video, ByteBuffer data, MediaCodec.BufferInfo info) {
            this.video = video;
            this.data = data;
            this.info = info;
        }
    }

    private final MediaMuxer muxer;
    private final String path;
    private final Runnable onDone;   // called once the file is complete
    private int videoTrack = -1, audioTrack = -1;
    private boolean started, videoEnded, audioEnded;
    private final List<Pending> pending = new ArrayList<>();

    Muxer(String path) throws IOException {
        this.path = path;
        this.onDone = null;
        muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    /** Writes into a MediaStore file (gallery-visible); onDone publishes it. */
    Muxer(java.io.FileDescriptor fd, String label, Runnable onDone) throws IOException {
        this.path = label;
        this.onDone = onDone;
        muxer = new MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    synchronized void addTrack(boolean video, MediaFormat format) {
        int t = muxer.addTrack(format);
        if (video) videoTrack = t; else audioTrack = t;
        if (videoTrack >= 0 && audioTrack >= 0) {
            muxer.start();
            started = true;
            for (Pending p : pending) {
                muxer.writeSampleData(p.video ? videoTrack : audioTrack, p.data, p.info);
            }
            pending.clear();
        }
    }

    synchronized void write(boolean video, ByteBuffer buf, MediaCodec.BufferInfo info) {
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || info.size == 0) return;
        if (started) {
            muxer.writeSampleData(video ? videoTrack : audioTrack, buf, info);
            return;
        }
        ByteBuffer copy = ByteBuffer.allocateDirect(info.size);
        buf.position(info.offset);
        buf.limit(info.offset + info.size);
        copy.put(buf).flip();
        MediaCodec.BufferInfo i = new MediaCodec.BufferInfo();
        i.set(0, info.size, info.presentationTimeUs, info.flags);
        pending.add(new Pending(video, copy, i));
    }

    synchronized void end(boolean video) {
        if (video) videoEnded = true; else audioEnded = true;
        if (videoEnded && audioEnded) {
            if (started) muxer.stop();
            muxer.release();
            Log.i(TAG, "Muxer finished: " + path);
            if (onDone != null) onDone.run();
        }
    }
}
