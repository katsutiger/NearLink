package com.example.nearlink;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.PowerManager;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class BluetoothService {
    private static final String TAG = "BluetoothService";
    private static final String APP_NAME = "NearLink";
    private static final java.util.UUID APP_UUID = java.util.UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");

    private static final int MAX_RETRY_COUNT = 3;
    private static final long DISCOVERY_TIMEOUT = 30000; // 30秒
    private static final long RETRY_DELAY_BASE = 1000; // 1秒
    private static final long DISCOVERY_INTERVAL_NORMAL = 5000; // 5秒
    private static final long DISCOVERY_INTERVAL_POWER_SAVE = 15000; // 15秒
    private static final long CONNECTION_TIMEOUT = 10000; // 10秒
    private static final int MAX_MESSAGE_SIZE = 1024 * 1024; // 1MB
    private static final int DISCOVERABLE_DURATION = 300; // 300秒

    private final WeakReference<Context> contextRef;
    private final BluetoothAdapter bluetoothAdapter;
    private final MeshNode meshNode;
    private final Handler handler;
    private final ConcurrentHashMap<String, DeviceInfo> discoveredDevices;
    private final ConcurrentHashMap<String, Long> connectionAttempts;
    private final AtomicBoolean isDiscovering;
    private final PowerManager.WakeLock wakeLock;
    private BluetoothCommunicationService communicationService;

    private BroadcastReceiver discoveryReceiver;
    private BroadcastReceiver scanModeReceiver;
    private int retryCount;
    private long lastDiscoveryTime;
    private long lastDiscoverableRequestTime;
    private boolean isDiscoverable;
    private final Object connectionLock = new Object();

    public BluetoothService(@NonNull BluetoothAdapter adapter,
                            @NonNull MeshNode meshNode,
                            @NonNull Context context) {
        this.bluetoothAdapter = adapter;
        this.meshNode = meshNode;
        this.contextRef = new WeakReference<>(context.getApplicationContext());
        this.handler = new Handler(Looper.getMainLooper());
        this.discoveredDevices = new ConcurrentHashMap<>();
        this.connectionAttempts = new ConcurrentHashMap<>();
        this.isDiscovering = new AtomicBoolean(false);
        this.isDiscoverable = false;

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NearLink:BluetoothDiscovery"
        );
        wakeLock.setReferenceCounted(false);

        initializeReceivers();
        initializeCommunicationService(context);
        advertiseServiceUUID();
    }

    private void initializeReceivers() {
        // 发现设备的广播接收器
        discoveryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null || intent.getAction() == null) return;

                try {
                    String action = intent.getAction();
                    Log.d(TAG, "Received action: " + action);

                    switch (action) {
                        case BluetoothDevice.ACTION_FOUND:
                            handleDeviceFound(intent);
                            break;
                        case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                            handleDiscoveryFinished();
                            break;
                        case BluetoothAdapter.ACTION_STATE_CHANGED:
                            handleBluetoothStateChanged(intent);
                            break;
                        case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                            handleBondStateChanged(intent);
                            break;
                        case BluetoothAdapter.ACTION_SCAN_MODE_CHANGED:
                            int scanMode = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE,
                                    BluetoothAdapter.ERROR);
                            handleScanModeChanged(scanMode);
                            break;
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "Security Exception in discovery receiver", e);
                } catch (Exception e) {
                    Log.e(TAG, "Error in discovery receiver", e);
                }
            }
        };

        // 注册接收器
        Context context = contextRef.get();
        if (context != null) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
            filter.addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
            context.registerReceiver(discoveryReceiver, filter);
        }
    }

    private void handleScanModeChanged(int scanMode) {
        isDiscoverable = (scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
        Log.d(TAG, "Scan mode changed, isDiscoverable: " + isDiscoverable);
    }

    private void advertiseServiceUUID() {
        try {
            if (checkBluetoothPermissions()) {
                bluetoothAdapter.setName("NearLink_" + bluetoothAdapter.getName());

                try {
                    Method getUuidsMethod = BluetoothAdapter.class.getDeclaredMethod("getUuids", null);
                    ParcelUuid[] uuids = (ParcelUuid[]) getUuidsMethod.invoke(bluetoothAdapter, null);

                    if (uuids == null || !containsAppUUID(uuids)) {
                        Method addUuidMethod = BluetoothAdapter.class.getDeclaredMethod("addUuid", ParcelUuid.class);
                        addUuidMethod.invoke(bluetoothAdapter, new ParcelUuid(APP_UUID));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error setting service UUID", e);
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security Exception in advertiseServiceUUID", e);
        }
    }

    private boolean containsAppUUID(ParcelUuid[] uuids) {
        for (ParcelUuid uuid : uuids) {
            if (uuid.getUuid().equals(APP_UUID)) {
                return true;
            }
        }
        return false;
    }

    private void makeDeviceDiscoverable() {
        Context context = contextRef.get();
        if (context == null) return;

        try {
            if (checkBluetoothPermissions()) {
                // 检查当前是否已经可见
                long currentTime = System.currentTimeMillis();
                if (isDiscoverable && (currentTime - lastDiscoverableRequestTime) <
                        (DISCOVERABLE_DURATION * 1000 - 10000)) {
                    return;
                }

                Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                        DISCOVERABLE_DURATION);
                discoverableIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(discoverableIntent);

                lastDiscoverableRequestTime = currentTime;

                handler.postDelayed(() -> {
                    isDiscoverable = false;
                }, DISCOVERABLE_DURATION * 1000);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security Exception in makeDeviceDiscoverable", e);
        } catch (Exception e) {
            Log.e(TAG, "Error making device discoverable", e);
        }
    }

    public boolean startDiscovery() {
        if (!checkPrerequisites()) {
            return false;
        }

        try {
            if (checkBluetoothPermissions()) {
                // 确保设备可被发现
                if (!isDiscoverable) {
                    makeDeviceDiscoverable();
                }

                Log.d(TAG, "Starting Bluetooth discovery");
                registerReceivers();

                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }

                if (bluetoothAdapter.startDiscovery()) {
                    isDiscovering.set(true);
                    lastDiscoveryTime = System.currentTimeMillis();
                    wakeLock.acquire(DISCOVERY_TIMEOUT);
                    scheduleDiscoveryTimeout();
                    return true;
                } else {
                    Log.e(TAG, "Failed to start discovery");
                    handleDiscoveryFailure();
                    return false;
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security Exception in startDiscovery", e);
            handleDiscoveryFailure();
        } catch (Exception e) {
            Log.e(TAG, "Error in startDiscovery", e);
            handleDiscoveryFailure();
        }
        return false;
    }

    private void initializeCommunicationService(Context context) {
        Handler commHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(@NonNull android.os.Message msg) {
                switch (msg.what) {
                    case MainActivity.MESSAGE_STATE_CHANGE:
                        handleStateChange(msg.arg1);
                        break;
                    case MainActivity.MESSAGE_READ:
                        handleReadMessage((Message) msg.obj);
                        break;
                    case MainActivity.MESSAGE_DEVICE_NAME:
                        Bundle bundle = msg.getData();
                        if (bundle != null) {
                            handleDeviceConnected(bundle.getString("device_name"));
                        }
                        break;
                    case MainActivity.MESSAGE_TOAST:
                        handleConnectionError(msg.getData().getString("toast"));
                        break;
                }
            }
        };

        communicationService = new BluetoothCommunicationService(context, commHandler);
        communicationService.start();
    }

    private void handleDeviceFound(Intent intent) {
        BluetoothDevice device;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        } else {
            device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        }

        if (device == null) return;

        try {
            // 检查设备名称是否包含应用标识
            String deviceName = device.getName();
            if (deviceName != null && deviceName.startsWith("NearLink_")) {
                processNewDevice(device);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security Exception in handleDeviceFound", e);
        }
    }

    private void processNewDevice(BluetoothDevice device) {
        try {
            String deviceAddress = device.getAddress();
            String deviceName = device.getName();

            if (deviceName != null && !discoveredDevices.containsKey(deviceAddress)) {
                Log.d(TAG, "Found new device: " + deviceName);

                synchronized (connectionLock) {
                    if (!connectionAttempts.containsKey(deviceAddress) ||
                            canAttemptConnection(deviceAddress)) {

                        if (bluetoothAdapter.isDiscovering()) {
                            bluetoothAdapter.cancelDiscovery();
                        }

                        communicationService.connect(device);
                        connectionAttempts.put(deviceAddress, System.currentTimeMillis());
                    }
                }

                DeviceInfo deviceInfo = new DeviceInfo(deviceAddress, deviceName, System.currentTimeMillis());
                discoveredDevices.put(deviceAddress, deviceInfo);
                meshNode.handleNewDevice(deviceInfo);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security Exception in processNewDevice", e);
        } catch (Exception e) {
            Log.e(TAG, "Error processing new device", e);
        }
    }

    public void sendMessage(Message message) {
        try {
            byte[] serializedMessage = serializeMessage(message);
            if (serializedMessage.length <= MAX_MESSAGE_SIZE) {
                for (DeviceInfo deviceInfo : discoveredDevices.values()) {
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceInfo.address);
                    communicationService.connect(device);
                    communicationService.write(serializedMessage);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending message", e);
        }
    }

    private byte[] serializeMessage(Message message) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(message);
        return bos.toByteArray();
    }

    private void handleReadMessage(Message message) {
        if (message != null) {
            meshNode.broadcastMessage(message);
        }
    }

    private void handleDeviceConnected(String deviceName) {
        Log.d(TAG, "Connected to device: " + deviceName);
    }

    private void handleConnectionError(String error) {
        Log.e(TAG, "Connection error: " + error);
        retryConnection();
    }

    private void handleStateChange(int state) {
        Log.d(TAG, "Bluetooth state changed to: " + state);
    }

    private void handleDiscoveryFinished() {
        isDiscovering.set(false);
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }

        // 检查是否需要重新开始发现
        long timeSinceLastDiscovery = System.currentTimeMillis() - lastDiscoveryTime;
        if (timeSinceLastDiscovery >= getDiscoveryInterval()) {
            retryDiscovery();
        }

        // 清理过期的连接
        cleanupExpiredConnections();
    }

    private void handleDiscoveryFailure() {
        isDiscovering.set(false);
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
        retryDiscovery();
    }

    private void handleBluetoothStateChanged(Intent intent) {
        int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
        switch (state) {
            case BluetoothAdapter.STATE_ON:
                if (!isDiscovering.get()) {
                    startDiscovery();
                }
                break;
            case BluetoothAdapter.STATE_TURNING_OFF:
            case BluetoothAdapter.STATE_OFF:
                stopDiscovery();
                break;
        }
    }

    private void handleBondStateChanged(Intent intent) {
        BluetoothDevice device;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        } else {
            device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        }

        if (device == null) return;

        int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
        if (bondState == BluetoothDevice.BOND_BONDED) {
            communicationService.connect(device);
        }
    }

    private void cleanupExpiredConnections() {
        long now = System.currentTimeMillis();

        // 清理过期的设备记录
        discoveredDevices.entrySet().removeIf(entry ->
                (now - entry.getValue().lastSeen) > DISCOVERY_TIMEOUT);

        // 清理超时的连接尝试记录
        connectionAttempts.entrySet().removeIf(entry ->
                (now - entry.getValue()) > CONNECTION_TIMEOUT);
    }

    private long getDiscoveryInterval() {
        Context context = contextRef.get();
        if (context != null) {
            PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager != null && powerManager.isPowerSaveMode()) {
                return DISCOVERY_INTERVAL_POWER_SAVE;
            }
        }
        return DISCOVERY_INTERVAL_NORMAL;
    }

    private void scheduleDiscoveryTimeout() {
        handler.postDelayed(() -> {
            if (isDiscovering.get()) {
                Log.d(TAG, "Discovery timeout reached");
                stopDiscovery();
                retryDiscovery();
            }
        }, DISCOVERY_TIMEOUT);
    }

    private void retryDiscovery() {
        if (retryCount < MAX_RETRY_COUNT) {
            retryCount++;
            long delay = RETRY_DELAY_BASE * retryCount;
            handler.postDelayed(this::startDiscovery, delay);
            Log.d(TAG, "Scheduling retry " + retryCount + " in " + delay + "ms");
        } else {
            retryCount = 0;
            Log.d(TAG, "Max retry count reached");
        }
    }

    private void retryConnection() {
        if (retryCount < MAX_RETRY_COUNT) {
            retryCount++;
            long delay = RETRY_DELAY_BASE * retryCount;
            handler.postDelayed(this::startDiscovery, delay);
        } else {
            retryCount = 0;
        }
    }

    private boolean checkPrerequisites() {
        return bluetoothAdapter != null &&
                bluetoothAdapter.isEnabled() &&
                !isDiscovering.get();
    }

    private boolean checkBluetoothPermissions() {
        Context context = contextRef.get();
        if (context == null) return false;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                            PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                            PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) ==
                            PackageManager.PERMISSION_GRANTED;
        }
    }

    private void registerReceivers() {
        try {
            Context context = contextRef.get();
            if (context == null) return;

            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothDevice.ACTION_FOUND);
            filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);

            context.registerReceiver(discoveryReceiver, filter);
            Log.d(TAG, "Registered Bluetooth receivers");
        } catch (Exception e) {
            Log.e(TAG, "Error registering receivers", e);
        }
    }

    private void unregisterReceivers() {
        try {
            Context context = contextRef.get();
            if (context != null && discoveryReceiver != null) {
                context.unregisterReceiver(discoveryReceiver);
                Log.d(TAG, "Unregistered Bluetooth receivers");
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Receiver not registered", e);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver", e);
        }
    }

    public void stopDiscovery() {
        if (!isDiscovering.get()) {
            return;
        }

        try {
            Log.d(TAG, "Stopping Bluetooth discovery");
            if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
            unregisterReceivers();
            isDiscovering.set(false);
            retryCount = 0;

            if (wakeLock.isHeld()) {
                wakeLock.release();
            }

            if (communicationService != null) {
                communicationService.stop();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security Exception in stopDiscovery", e);
        } catch (Exception e) {
            Log.e(TAG, "Error in stopDiscovery", e);
        }
    }

    private boolean canAttemptConnection(String deviceAddress) {
        Long lastAttempt = connectionAttempts.get(deviceAddress);
        if (lastAttempt == null) {
            return true;
        }
        return System.currentTimeMillis() - lastAttempt > CONNECTION_TIMEOUT;
    }

    // Public static class for DeviceInfo
    public static class DeviceInfo {
        public final String address;
        public final String name;
        public final long lastSeen;

        public DeviceInfo(String address, String name, long lastSeen) {
            this.address = address;
            this.name = name;
            this.lastSeen = lastSeen;
        }
    }

    // Public methods for external use
    public int getDiscoveredDeviceCount() {
        return discoveredDevices.size();
    }

    public boolean isDeviceConnected(String address) {
        if (communicationService != null) {
            return communicationService.getState() == BluetoothCommunicationService.STATE_CONNECTED;
        }
        return false;
    }

    public void disconnectDevice() {
        if (communicationService != null) {
            communicationService.stop();
        }
    }

    public void resetConnections() {
        stopDiscovery();
        if (communicationService != null) {
            communicationService.stop();
            communicationService.start();
        }
        startDiscovery();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            Context context = contextRef.get();
            if (context != null && scanModeReceiver != null) {
                try {
                    context.unregisterReceiver(scanModeReceiver);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Error unregistering scan mode receiver", e);
                }
            }
            stopDiscovery();
        } finally {
            super.finalize();
        }
    }
}