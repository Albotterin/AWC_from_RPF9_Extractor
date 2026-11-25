package org.awctool.radio;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;

/** One song of the pack played with MediaPlayer, straight out of the pack file (see Engine.Player). */
final class PackPlayer implements Engine.Player, Episodes.Player {
    private MediaPlayer mp;
    private boolean prepared, failed, finished, seeking, wantStart, released;
    private double seekTarget;
    private final String name;

    /** `usage` says on which volume it plays: USAGE_MEDIA for the radio, USAGE_ALARM for the alarm. */
    PackPlayer(Pack pack, String path, int usage) {
        this.name = path;
        try {
            Pack.Entry entry = pack.entry(path);
            if (entry == null) throw new IllegalStateException("not in the pack");
            mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            mp.setDataSource(pack.fd(), entry.offset, entry.length);
            mp.setVolume(0f, 0f);
            mp.setOnPreparedListener(m -> prepared = true);
            mp.setOnCompletionListener(m -> finished = true);
            mp.setOnErrorListener((m, what, extra) -> {
                AppLog.d("MediaPlayer error " + what + "/" + extra + " in " + name);
                failed = true;
                return true;
            });
            mp.setOnSeekCompleteListener(m -> {
                seeking = false;
                if (wantStart && !released) {
                    wantStart = false;
                    try {
                        m.start();
                    } catch (RuntimeException e) {
                        failed = true;
                    }
                }
            });
            mp.prepareAsync();
        } catch (Exception e) {
            AppLog.d("cannot open " + path + ": " + e);
            failed = true;
            release();
        }
    }

    /** Sound is coming out right now (the alarm checks this). */
    boolean isPlaying() {
        try {
            return mp != null && !failed && mp.isPlaying();
        } catch (RuntimeException e) {
            return false;
        }
    }

    boolean isReleased() {
        return released;
    }

    @Override
    public boolean isPrepared() {
        return prepared && !failed && !released;
    }

    @Override
    public boolean isFailed() {
        return failed;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void startAt(double posSec, float volume) {
        if (mp == null) return;
        try {
            mp.setVolume(volume, volume);
            if (posSec > 0.4) {                      // joined in the middle of the song
                wantStart = true;
                seek(posSec);
            } else {
                mp.start();
            }
        } catch (RuntimeException e) {
            AppLog.d("start failed in " + name + ": " + e);
            failed = true;
        }
    }

    private void seek(double posSec) {
        seeking = true;
        seekTarget = posSec;
        finished = false;
        int ms = (int) (posSec * 1000);
        if (Build.VERSION.SDK_INT >= 26) mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
        else mp.seekTo(ms);
    }

    // ---- for podcasts: they pause and go back and forth
    @Override
    public void seekTo(double posSec) {
        if (mp == null) return;
        try {
            seek(posSec);
        } catch (RuntimeException e) {
            AppLog.d("seek failed in " + name + ": " + e);
            failed = true;
        }
    }

    @Override
    public void pause() {
        wantStart = false;
        if (mp == null) return;
        try {
            if (mp.isPlaying()) mp.pause();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void resume() {
        if (mp == null) return;
        if (seeking) {
            wantStart = true;
            return;
        }
        try {
            mp.start();
        } catch (RuntimeException e) {
            AppLog.d("resume failed in " + name + ": " + e);
            failed = true;
        }
    }

    @Override
    public double position() {
        if (mp == null) return 0;
        if (seeking) return seekTarget;
        try {
            return mp.getCurrentPosition() / 1000.0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public void setVolume(float volume) {
        if (mp == null) return;
        try {
            mp.setVolume(volume, volume);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void release() {
        released = true;
        if (mp != null) {
            try {
                mp.release();
            } catch (RuntimeException ignored) {
            }
            mp = null;
        }
    }
}
