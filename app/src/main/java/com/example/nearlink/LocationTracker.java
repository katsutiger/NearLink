package com.example.nearlink;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;

import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.Priority;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.Task;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class LocationTracker {
    private static final String TAG = "LocationTracker";

    private static final long UPDATE_INTERVAL_NORMAL = 1000L;          // 1秒
    private static final long UPDATE_INTERVAL_POWER_SAVING = 5000L;    // 5秒
    private static final long UPDATE_INTERVAL_BACKGROUND = 30000L;     // 30秒
    private static final float MIN_DISTANCE_CHANGE = 1.0f;            // 1米
    private static final long LOCATION_MAX_AGE = 30000L;              // 30秒
    private static final float ACCURACY_THRESHOLD_HIGH = 20.0f;       // 20米
    private static final float ACCURACY_THRESHOLD_MEDIUM = 50.0f;     // 50米
    private static final int MAX_LOCATION_SAMPLES = 5;                // 位置平滑样本数


    private final WeakReference<Context> contextRef;
    private final MeshNode meshNode;
    private final FusedLocationProviderClient fusedLocationClient;
    private final LocationCallback locationCallback;
    private final PowerManager.WakeLock wakeLock;
    private final List<Location> locationSamples;
    private final AtomicBoolean isTracking;


    private Location lastLocation;
    private LocationRequest currentLocationRequest;
    private LocationAccuracyCallback accuracyCallback;

    /**
     * 位置精度回调接口
     */
    public interface LocationAccuracyCallback {
        void onAccuracyChanged(LocationAccuracy accuracy);
    }

    /**
     * 位置精度枚举
     */
    public enum LocationAccuracy {
        HIGH("GPS信号良好"),
        MEDIUM("GPS信号普通"),
        LOW("GPS信号が弱い"),
        NONE("GPS信号なし");

        private final String description;

        LocationAccuracy(String description) {
            this.description = description;
        }

        @SuppressWarnings("unused")
        public String getDescription() {
            return description;
        }
    }

    /**
     * 构造函数
     */
    public LocationTracker(@NonNull Context context, @NonNull MeshNode meshNode) {
        this.contextRef = new WeakReference<>(context.getApplicationContext());
        this.meshNode = meshNode;
        this.fusedLocationClient = LocationServices.getFusedLocationProviderClient(context);
        this.locationSamples = new ArrayList<>();
        this.isTracking = new AtomicBoolean(false);

        // 初始化 WakeLock
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NearLink:LocationTracking");
        wakeLock.setReferenceCounted(false);

        // 初始化位置回调
        this.locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                handleLocationResult(locationResult);
            }
        };
    }


    /**
     * 开始位置追踪
     */
    public boolean startTracking() {
        try {
            if (!checkLocationPermissions()) {
                Log.e(TAG, "Location permissions not granted");
                return false;
            }

            Task<Void> task = fusedLocationClient.requestLocationUpdates(
                    currentLocationRequest,
                    locationCallback,
                    Looper.getMainLooper()
            );

            task.addOnSuccessListener(aVoid -> isTracking.set(true))
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Failed to request location updates", e);
                        isTracking.set(false);
                    });

            return true;
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception while starting location tracking", e);
            return false;
        }
    }

    /**
     * 停止位置追踪
     */
    public void stopTracking() {
        if (!isTracking.get()) {
            return;
        }

        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
                    .addOnCompleteListener(task -> {
                        isTracking.set(false);
                        if (wakeLock.isHeld()) {
                            wakeLock.release();
                        }
                    });
            locationSamples.clear();
        } catch (Exception e) {
            Log.e(TAG, "Error stopping location tracking", e);
        }
    }

    /**
     * 获取最后已知位置
     */
    @Nullable
    public Location getLastLocation() {
        return lastLocation;
    }



    private LocationRequest createLocationRequest() {
        Context context = contextRef.get();
        PowerManager powerManager = null;
        boolean isPowerSaveMode = false;

        if (context != null) {
            powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            isPowerSaveMode = powerManager != null && powerManager.isPowerSaveMode();
        }

        boolean isBackgroundMode = !(context instanceof Activity);

        long interval;
        if (isBackgroundMode) {
            interval = UPDATE_INTERVAL_BACKGROUND;
        } else if (isPowerSaveMode) {
            interval = UPDATE_INTERVAL_POWER_SAVING;
        } else {
            interval = UPDATE_INTERVAL_NORMAL;
        }

        return new LocationRequest.Builder(interval)
                .setMinUpdateDistanceMeters(MIN_DISTANCE_CHANGE)
                .setMinUpdateIntervalMillis(interval / 2)
                .setMaxUpdateDelayMillis(interval * 2)
                .setPriority(isPowerSaveMode ?
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY :
                        Priority.PRIORITY_HIGH_ACCURACY)
                .setWaitForAccurateLocation(!isPowerSaveMode)
                .build();
    }

    private void handleLocationResult(@NonNull LocationResult locationResult) {
        Location location = locationResult.getLastLocation();
        if (location != null) {
            handleLocationUpdate(location);
        }
    }

    private void handleLocationUpdate(@NonNull Location location) {
        if (!isValidLocation(location)) {
            return;
        }

        // 添加位置样本并进行平滑处理
        locationSamples.add(location);
        if (locationSamples.size() > MAX_LOCATION_SAMPLES) {
            locationSamples.remove(0);
        }

        Location smoothedLocation = smoothLocation(locationSamples);
        lastLocation = smoothedLocation;

        // 更新位置精度状态
        updateLocationAccuracy(smoothedLocation);

        // 发送位置更新
        meshNode.updateLocation(smoothedLocation);

        // 记录调试信息
        logLocationUpdate(smoothedLocation);
    }

    private void updateLocationAccuracy(@NonNull Location location) {
        if (accuracyCallback == null) return;

        LocationAccuracy accuracy;
        if (location.hasAccuracy()) {
            float accuracyMeters = location.getAccuracy();
            if (accuracyMeters <= ACCURACY_THRESHOLD_HIGH) {
                accuracy = LocationAccuracy.HIGH;
            } else if (accuracyMeters <= ACCURACY_THRESHOLD_MEDIUM) {
                accuracy = LocationAccuracy.MEDIUM;
            } else {
                accuracy = LocationAccuracy.LOW;
            }
        } else {
            accuracy = LocationAccuracy.NONE;
        }

        accuracyCallback.onAccuracyChanged(accuracy);
    }


    private boolean isValidLocation(@NonNull Location location) {
        // 检查位置时间戳
        if (isLocationStale(location)) {
            Log.d(TAG, "Stale location rejected");
            return false;
        }

        // 检查经纬度范围
        if (!isValidCoordinates(location)) {
            Log.d(TAG, "Invalid coordinates rejected");
            return false;
        }

        // 检查精度
        if (location.hasAccuracy() && location.getAccuracy() > 100) {
            Log.d(TAG, "Low accuracy location rejected");
            return false;
        }

        // 检查速度合理性
        if (location.hasSpeed() && location.getSpeed() > 100) {
            Log.d(TAG, "Unreasonable speed detected");
            return false;
        }

        return true;
    }

    private boolean isLocationStale(@NonNull Location location) {
        return System.currentTimeMillis() - location.getTime() > LOCATION_MAX_AGE;
    }

    private boolean isValidCoordinates(@NonNull Location location) {
        return location.getLatitude() >= -90 && location.getLatitude() <= 90 &&
                location.getLongitude() >= -180 && location.getLongitude() <= 180;
    }

    private Location smoothLocation(List<Location> samples) {
        if (samples.size() < 2) {
            return samples.get(samples.size() - 1);
        }

        double latSum = 0;
        double lngSum = 0;
        float accSum = 0;

        for (Location location : samples) {
            latSum += location.getLatitude();
            lngSum += location.getLongitude();
            if (location.hasAccuracy()) {
                accSum += location.getAccuracy();
            }
        }

        Location smoothed = new Location("");
        smoothed.setLatitude(latSum / samples.size());
        smoothed.setLongitude(lngSum / samples.size());
        smoothed.setAccuracy(accSum / samples.size());
        smoothed.setTime(System.currentTimeMillis());

        return smoothed;
    }

    private boolean checkLocationPermissions() {
        Context context = contextRef.get();
        if (context == null) return false;

        return ActivityCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(context,
                        Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }


    private void logLocationUpdate(Location location) {
        Log.d(TAG, String.format(
                "Location Update - Lat: %f, Lng: %f, Accuracy: %f meters, Provider: %s, Speed: %f",
                location.getLatitude(),
                location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : -1,
                location.getProvider(),
                location.hasSpeed() ? location.getSpeed() : -1
        ));
    }
}