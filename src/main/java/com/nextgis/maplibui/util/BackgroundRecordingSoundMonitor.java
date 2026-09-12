/*
 * Project: NextGIS Mobile
 * Purpose: Audible health feedback for background geometry recording.
 */
package com.nextgis.maplibui.util;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import com.hypertrack.hyperlog.HyperLog;
import com.nextgis.maplib.util.Constants;
import com.nextgis.maplib.util.LocationTrackFilter;

import java.util.List;

/**
 * Plays a fixed-rate heartbeat while recording receives fresh usable coordinates and the app UI
 * is hidden. The owning service feeds the shared validated GNSS stream before decimation,
 * so stationary fixes confirm health without opening another location subscription.
 */
public final class BackgroundRecordingSoundMonitor implements LocationListener {
    private static final int TONE_VOLUME_PERCENT = 45;
    private static final int HEARTBEAT_DURATION_MS = 90;
    private static final int ERROR_DURATION_MS = 350;
    private static final long HEARTBEAT_VIBRATION_MS = 80L;
    private static final long[] ERROR_VIBRATION_PATTERN = {0L, 80L, 70L, 80L};

    private final Context mContext;
    private final SharedPreferences mPreferences;
    private final BackgroundRecordingSoundPolicy mPolicy = new BackgroundRecordingSoundPolicy();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHeartbeatRunnable = this::runHeartbeat;
    private ToneGenerator mToneGenerator;
    private boolean mStarted;
    private long mLastUsableLocationAtMs = Long.MIN_VALUE;
    private long mNextHeartbeatAtMs = Long.MIN_VALUE;

    public BackgroundRecordingSoundMonitor(Context context, SharedPreferences preferences) {
        mContext = context.getApplicationContext();
        mPreferences = preferences;
    }

    /**
     * Starts the fixed 10-second heartbeat schedule; the owner supplies validated fixes.
     */
    public synchronized void start() {
        if (mStarted) return;
        mStarted = true;
        long nowMs = SystemClock.elapsedRealtime();
        mNextHeartbeatAtMs = nowMs + BackgroundRecordingSoundPolicy.HEARTBEAT_INTERVAL_MS;
        scheduleNextHeartbeat(nowMs);
    }

    public synchronized void onPersistenceFailed() {
        long nowMs = SystemClock.elapsedRealtime();
        boolean enabled = isEnabled();
        boolean background = isAppUiHidden();
        if (!mPolicy.shouldPlayError(enabled, background, nowMs)) {
            return;
        }
        if (emitSignal(ToneGenerator.TONE_PROP_NACK, ERROR_DURATION_MS, true)) {
            mPolicy.recordError(nowMs);
        }
    }

    public synchronized void release() {
        mStarted = false;
        mHandler.removeCallbacks(mHeartbeatRunnable);
        mLastUsableLocationAtMs = Long.MIN_VALUE;
        mNextHeartbeatAtMs = Long.MIN_VALUE;
        if (mToneGenerator != null) {
            mToneGenerator.release();
            mToneGenerator = null;
        }
    }

    @Override
    public synchronized void onLocationChanged(Location location) {
        if (mStarted && LocationTrackFilter.passesRecordingIntegrity(location)) {
            // Batches and replayed cache entries must not renew health at receipt time.
            mLastUsableLocationAtMs = location.getElapsedRealtimeNanos() / 1_000_000L;
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // Fresh location callbacks are the source of truth for health.
    }

    @Override
    public void onProviderEnabled(String provider) {
        // Wait for a fresh usable callback before confirming health.
    }

    @Override
    public void onProviderDisabled(String provider) {
        // The last callback naturally expires after LOCATION_FRESHNESS_MS.
    }

    private synchronized void runHeartbeat() {
        if (!mStarted) {
            return;
        }
        long nowMs = SystemClock.elapsedRealtime();
        if (mPolicy.shouldPlayHeartbeat(
                isEnabled(), isAppUiHidden(), mLastUsableLocationAtMs, nowMs)
                && emitSignal(ToneGenerator.TONE_PROP_BEEP, HEARTBEAT_DURATION_MS, false)) {
            mPolicy.recordHeartbeat(nowMs);
        }

        // Keep the cadence anchored to the original schedule and skip missed slots without a burst.
        do {
            mNextHeartbeatAtMs += BackgroundRecordingSoundPolicy.HEARTBEAT_INTERVAL_MS;
        } while (mNextHeartbeatAtMs <= nowMs);
        scheduleNextHeartbeat(nowMs);
    }

    private void scheduleNextHeartbeat(long nowMs) {
        mHandler.removeCallbacks(mHeartbeatRunnable);
        mHandler.postDelayed(mHeartbeatRunnable, Math.max(1L, mNextHeartbeatAtMs - nowMs));
    }

    public synchronized void onLocationUnavailable() {
        mLastUsableLocationAtMs = Long.MIN_VALUE;
    }

    private boolean isEnabled() {
        return mPreferences.getBoolean(
                SettingsConstantsUI.KEY_PREF_BACKGROUND_RECORDING_SOUND, true);
    }

    private boolean isAppUiHidden() {
        PowerManager powerManager =
                (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
        if (powerManager != null && !powerManager.isInteractive()) {
            return true;
        }

        ActivityManager activityManager =
                (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return true;
        }
        List<ActivityManager.RunningAppProcessInfo> processes =
                activityManager.getRunningAppProcesses();
        if (processes == null) {
            return true;
        }
        String mainProcessName = mContext.getPackageName();
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (!mainProcessName.equals(process.processName)) {
                continue;
            }
            // IMPORTANCE_FOREGROUND_SERVICE means recording is alive but the UI is hidden.
            return process.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    && process.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
        }
        return true;
    }

    private boolean emitSignal(int tone, int durationMs, boolean error) {
        AudioManager audioManager =
                (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        boolean alarmMuted = true;
        int alarmVolume = 0;
        if (audioManager != null) {
            try {
                alarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
                alarmMuted = audioManager.isStreamMute(AudioManager.STREAM_ALARM);
            } catch (RuntimeException ex) {
                HyperLog.w(Constants.TAG,
                        "BackgroundRecordingSoundMonitor alarm volume failure: "
                                + ex.getMessage(), ex);
            }
        }
        if (BackgroundRecordingSoundPolicy.shouldVibrate(alarmVolume, alarmMuted)) {
            return vibrate(error);
        }
        if (playAlarmTone(tone, durationMs)) {
            return true;
        }
        return vibrate(error);
    }

    private boolean playAlarmTone(int tone, int durationMs) {
        try {
            if (mToneGenerator == null) {
                mToneGenerator = new ToneGenerator(
                        AudioManager.STREAM_ALARM, TONE_VOLUME_PERCENT);
            }
            return mToneGenerator.startTone(tone, durationMs);
        } catch (RuntimeException ex) {
            HyperLog.w(Constants.TAG,
                    "BackgroundRecordingSoundMonitor audio failure: " + ex.getMessage(), ex);
            if (mToneGenerator != null) {
                mToneGenerator.release();
                mToneGenerator = null;
            }
            return false;
        }
    }

    private boolean vibrate(boolean error) {
        Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return false;
        }
        try {
            VibrationEffect effect = error
                    ? VibrationEffect.createWaveform(ERROR_VIBRATION_PATTERN, -1)
                    : VibrationEffect.createOneShot(
                    HEARTBEAT_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE);
            vibrator.vibrate(effect);
            return true;
        } catch (RuntimeException ex) {
            HyperLog.w(Constants.TAG,
                    "BackgroundRecordingSoundMonitor vibration failure: "
                            + ex.getMessage(), ex);
            return false;
        }
    }
}
