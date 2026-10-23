package se.lopar.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Förgrundstjänst som mäter tid och GPS-distans även när skärmen är låst.
 * Tillståndet ligger i statiska fält så att MainActivity kan läsa det.
 */
public class RunService extends Service implements LocationListener {
    public static final String ACTION_START = "se.lopar.app.START";
    public static final String ACTION_STOP = "se.lopar.app.STOP";
    public static final String ACTION_CLEAR = "se.lopar.app.CLEAR";

    private static final String CHANNEL = "lopar_run";
    private static final int NOTIF_ID = 1;

    private static final float MAX_ACC = 25f;     // ignorera punkter sämre än 25 m
    private static final float MIN_STEP = 3f;     // ignorera steg under 3 m (brus)
    private static final float MAX_SPEED = 12f;   // m/s, orimliga hopp

    // Delat tillstånd
    static volatile boolean running = false;
    static volatile long accMs = 0;
    static volatile long startedAt = 0;
    static volatile double meters = 0;
    static volatile String status = "Tryck Start";

    private Location last;
    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable notifTick = new Runnable() {
        @Override public void run() {
            if (running) {
                getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotif());
                handler.postDelayed(this, 5000);
            }
        }
    };

    static long elapsedMs() {
        return accMs + (running ? SystemClock.elapsedRealtime() - startedAt : 0);
    }

    static void resetIdle() {
        accMs = 0;
        meters = 0;
        status = "Tryck Start";
    }

    static String fmt(long ms) {
        long s = ms / 1000, m = s / 60;
        s %= 60;
        return (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_START.equals(action)) {
            begin();
        } else if (ACTION_STOP.equals(action)) {
            halt();
            status = "Pausad";
            finish();
        } else if (ACTION_CLEAR.equals(action)) {
            halt();
            resetIdle();
            finish();
        } else {
            // Omstart av systemet utan uppdrag: avsluta tyst.
            finish();
        }
        return START_NOT_STICKY;
    }

    private void begin() {
        createChannel();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, buildNotif());
        }
        if (running) return;

        last = null;
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
        } catch (SecurityException e) {
            status = "Platsåtkomst saknas";
            finish();
            return;
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lopar:run");
        wakeLock.acquire(6 * 60 * 60 * 1000L); // max 6 h

        startedAt = SystemClock.elapsedRealtime();
        running = true;
        status = "Söker GPS…";
        handler.postDelayed(notifTick, 1000);
    }

    private void halt() {
        if (running) {
            accMs += SystemClock.elapsedRealtime() - startedAt;
            running = false;
        }
        handler.removeCallbacks(notifTick);
        try {
            ((LocationManager) getSystemService(Context.LOCATION_SERVICE)).removeUpdates(this);
        } catch (Exception ignored) { }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        last = null;
    }

    private void finish() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
        else stopForeground(true);
        stopSelf();
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (!running) return;
        float acc = loc.getAccuracy();
        status = "GPS ±" + Math.round(acc) + " m";
        if (acc > MAX_ACC) return;
        if (last == null) { last = loc; return; }
        float d = last.distanceTo(loc);
        double dt = (loc.getElapsedRealtimeNanos() - last.getElapsedRealtimeNanos()) / 1e9;
        if (d < MIN_STEP) return;
        if (dt > 0 && d / dt > MAX_SPEED) { last = loc; return; }
        meters += d;
        last = loc;
    }

    // Krävs explicit på äldre Android-versioner.
    @Override public void onStatusChanged(String provider, int s, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) {
        if (running) status = "GPS är avstängd – slå på Plats";
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Löppass", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotif() {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Löppass pågår")
                .setContentText(fmt(elapsedMs()) + "  ·  " + Math.round(meters) + " m")
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    @Override
    public void onDestroy() {
        halt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
