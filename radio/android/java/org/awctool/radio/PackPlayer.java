package org.awctool.radio;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;

/** One song of the pack played with MediaPlayer, straight out of the pack file (see Engine.Player). */
final class PackPlayer implements Engine.Player {
    private MediaPlayer mp;
    private boolean prepared, failed, finished, seeking, wantStart, released;
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
                seeking = true;
                wantStart = true;
                int ms = (int) (posSec * 1000);
                if (Build.VERSION.SDK_INT >= 26) mp.seekTo(ms, MediaPlayer.SEEK_CLOSEST);
                else mp.seekTo(ms);
            } else {
                mp.start();
            }
        } catch (RuntimeException e) {
            AppLog.d("start failed in " + name + ": " + e);
            failed = true;
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
