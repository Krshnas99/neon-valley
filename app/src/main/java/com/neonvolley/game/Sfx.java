package com.neonvolley.game;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/** Tiny synthesised sound effects: no audio files needed. Any audio failure just means silence. */
final class Sfx {
    static final int HIT = 0, BREAK = 1, PICK = 2, LASER = 3, SHOT = 4, OVER = 5;
    private static final int RATE = 22050;
    private static final long[] GAP_MS = {45, 35, 0, 70, 60, 0}; // minimum time between repeats

    private final AudioTrack[] tracks = new AudioTrack[6];
    private final long[] lastPlayed = new long[6];
    boolean muted;

    Sfx() {
        tracks[HIT] = make(tone(0.05f, 700, 700, 0.28f, false));
        tracks[BREAK] = make(tone(0.13f, 520, 1100, 0.34f, false));
        tracks[PICK] = make(tone(0.17f, 880, 1760, 0.30f, false));
        tracks[LASER] = make(tone(0.20f, 1500, 260, 0.22f, true));
        tracks[SHOT] = make(tone(0.03f, 420, 520, 0.16f, false));
        tracks[OVER] = make(tone(0.70f, 330, 70, 0.36f, true));
    }

    private static short[] tone(float secs, float f0, float f1, float vol, boolean square) {
        int n = (int) (RATE * secs);
        short[] d = new short[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            float k = i / (float) n;
            phase += 2 * Math.PI * (f0 + (f1 - f0) * k) / RATE;
            double s = Math.sin(phase);
            if (square) s = s > 0 ? 0.6 : -0.6;
            double env = (1 - k) * (1 - k) * Math.min(1.0, i / 60.0);
            d[i] = (short) (s * env * vol * 32767);
        }
        return d;
    }

    @SuppressWarnings("deprecation")
    private static AudioTrack make(short[] d) {
        try {
            AudioTrack a = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, d.length * 2, AudioTrack.MODE_STATIC);
            a.write(d, 0, d.length);
            if (a.getState() != AudioTrack.STATE_INITIALIZED) {
                a.release();
                return null;
            }
            return a;
        } catch (Throwable t) {
            return null;
        }
    }

    void play(int id, float pitch) {
        AudioTrack a = tracks[id];
        if (muted || a == null) return;
        long now = System.nanoTime() / 1000000L;
        if (now - lastPlayed[id] < GAP_MS[id]) return;
        lastPlayed[id] = now;
        try {
            a.stop();
            a.reloadStaticData();
            a.setPlaybackRate((int) (RATE * pitch));
            a.play();
        } catch (Throwable t) {
            // ignore: sound is optional
        }
    }

    void release() {
        for (int i = 0; i < tracks.length; i++) {
            try {
                if (tracks[i] != null) tracks[i].release();
            } catch (Throwable t) {
                // ignore
            }
            tracks[i] = null;
        }
    }
}
