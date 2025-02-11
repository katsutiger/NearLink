package com.example.nearlink;

import android.content.Context;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MeshNode implements AutoCloseable {
    private static final String TAG = "MeshNode";
    private static final int MAX_QUEUE_SIZE = 1000;
    private static final int MAX_CACHE_SIZE = 10000;
    private static final int MESSAGE_TIMEOUT = 30000; // 30秒
    private static final int LOCATION_TIMEOUT = 300000; // 5分钟

    private BluetoothCommunicationService bluetoothCommunicationService = null;
    private final Context context;
    private final String nodeId;
    private volatile Location currentLocation;
    private final LinkedBlockingQueue<Message> messageQueue;
    private final ConcurrentHashMap<String, Message> processedMessages;
    private final ConcurrentHashMap<String, Long> messageTimestamps;
    private final LocationCache locationCache;
    private final ConcurrentHashMap<String, BluetoothService.DeviceInfo> nearbyDevices;
    private final AtomicBoolean isRunning;
    private final AtomicInteger messageCount;
    private MessageListener messageListener;
    private final Object locationLock = new Object();
    private final ScheduledExecutorService scheduler;

    public interface MessageListener {
        void onMessageReceived(Message message);
        default void onMessageDeliveryFailed(Message message, Exception e) {
            Log.e(TAG, "Message delivery failed", e);
        }
    }

    public MeshNode(@NonNull String nodeId, @NonNull Context context) {
        this.nodeId = nodeId;
        this.context = context.getApplicationContext();
        this.messageQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
        this.processedMessages = new ConcurrentHashMap<>();
        this.messageTimestamps = new ConcurrentHashMap<>();
        this.locationCache = new LocationCache();
        this.nearbyDevices = new ConcurrentHashMap<>();
        this.isRunning = new AtomicBoolean(true);
        this.messageCount = new AtomicInteger(0);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        initializeBluetoothService();
        startMessageProcessor();
        startCacheCleanup();
    }

    private void initializeBluetoothService() {
        Handler handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(@NonNull android.os.Message msg) {
                switch (msg.what) {
                    case MainActivity.MESSAGE_READ:
                    case MainActivity.MESSAGE_STATE_CHANGE:
                        // Handle message if needed
                        break;
                }
            }
        };
        bluetoothCommunicationService = new BluetoothCommunicationService(context, handler);
    }

    private void relayMessageToNearbyNodes(@NonNull Message message) {
        if (bluetoothCommunicationService == null) {
            Log.e(TAG, "BluetoothCommunicationService not initialized");
            return;
        }

        try {
            byte[] messageData = serializeMessage(message);
            List<BluetoothService.DeviceInfo> devices = getNearbyDevices();

            for (BluetoothService.DeviceInfo device : devices) {
                String messageKey = message.getId() + device.address;
                if (!processedMessages.containsKey(messageKey)) {
                    bluetoothCommunicationService.write(messageData);
                    processedMessages.put(messageKey, message);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error serializing message for relay", e);
        } catch (Exception e) {
            Log.e(TAG, "Error relaying message", e);
        }
    }

    @SuppressWarnings("unused")
    public void startBluetoothService() {
        if (bluetoothCommunicationService != null) {
            bluetoothCommunicationService.start();
        }
    }

    @SuppressWarnings("unused")
    public void stopBluetoothService() {
        if (bluetoothCommunicationService != null) {
            bluetoothCommunicationService.stop();
        }
    }

    public void setMessageListener(@Nullable MessageListener listener) {
        this.messageListener = listener;
    }

    @NonNull
    public String getNodeId() {
        return nodeId;
    }

    public void updateLocation(@NonNull Location location) {
        synchronized (locationLock) {
            this.currentLocation = location;
        }
        LocationMessage locationMessage = new LocationMessage(nodeId, location);
        broadcastMessage(locationMessage);
    }

    public boolean broadcastMessage(@NonNull Message message) {
        try {
            if (!isValidMessage(message)) {
                Log.w(TAG, "Invalid message rejected");
                return false;
            }

            if (processedMessages.containsKey(message.getId())) {
                Log.d(TAG, "Duplicate message rejected: " + message.getId());
                return false;
            }

            boolean added = messageQueue.offer(message, 100, TimeUnit.MILLISECONDS);
            if (added) {
                processedMessages.put(message.getId(), message);
                messageTimestamps.put(message.getId(), System.currentTimeMillis());
                messageCount.incrementAndGet();

                if (!(message instanceof LocationMessage) && messageListener != null) {
                    messageListener.onMessageReceived(message);
                }
            } else {
                Log.w(TAG, "Failed to add message to queue: " + message.getId());
            }

            return added;
        } catch (Exception e) {
            Log.e(TAG, "Error broadcasting message", e);
            return false;
        }
    }

    @NonNull
    public Map<String, Location> getNearbyUserLocations() {
        return locationCache.getActiveUserLocations();
    }

    @SuppressWarnings("unused")
    @Nullable
    public Location getCurrentLocation() {
        synchronized (locationLock) {
            return currentLocation;
        }
    }

    public void handleNewDevice(@NonNull BluetoothService.DeviceInfo deviceInfo) {
        nearbyDevices.put(deviceInfo.address, deviceInfo);
        cleanupOldDevices();
    }

    @NonNull
    public List<BluetoothService.DeviceInfo> getNearbyDevices() {
        return new ArrayList<>(nearbyDevices.values());
    }

    private void startMessageProcessor() {
        Thread processorThread = new Thread(() -> {
            while (isRunning.get()) {
                try {
                    Message message = messageQueue.poll(1, TimeUnit.SECONDS);
                    if (message != null) {
                        processMessage(message);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.e(TAG, "Message processor interrupted", e);
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error processing message", e);
                }
            }
        }, "MeshNode-MessageProcessor");

        processorThread.setDaemon(true);
        processorThread.start();
    }

    private void startCacheCleanup() {
        scheduler.scheduleAtFixedRate(() -> {
            if (isRunning.get()) {
                try {
                    cleanupCache();
                } catch (Exception e) {
                    Log.e(TAG, "Error in cache cleanup", e);
                }
            }
        }, 0, 60, TimeUnit.SECONDS);
    }

    private void processMessage(@NonNull Message message) {
        try {
            if (message.canBeRelayed()) {
                message.incrementHopCount();
                relayMessageToNearbyNodes(message);
            }

            if (message instanceof LocationMessage) {
                processLocationMessage((LocationMessage) message);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing message: " + message.getId(), e);
            if (messageListener != null) {
                messageListener.onMessageDeliveryFailed(message, e);
            }
        }
    }

    private void processLocationMessage(@NonNull LocationMessage locationMessage) {
        locationCache.updateLocation(
                locationMessage.getUserId(),
                locationMessage.getLocation(),
                locationMessage.getUpdateTime()
        );
    }

    private byte[] serializeMessage(Message message) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(message);
        return bos.toByteArray();
    }

    private boolean isValidMessage(@NonNull Message message) {
        return message.getContent() != null
                && message.getSenderId() != null
                && !message.getSenderId().isEmpty()
                && message.getContent().length() <= 10240
                && (System.currentTimeMillis() - message.getTimestamp() <= MESSAGE_TIMEOUT);
    }

    private void cleanupCache() {
        long now = System.currentTimeMillis();

        messageTimestamps.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > MESSAGE_TIMEOUT) {
                String messageId = entry.getKey();
                processedMessages.remove(messageId);
                return true;
            }
            return false;
        });

        nearbyDevices.entrySet().removeIf(entry ->
                now - entry.getValue().lastSeen > LOCATION_TIMEOUT
        );

        while (processedMessages.size() > MAX_CACHE_SIZE) {
            String oldestMessageId = findOldestMessage();
            if (oldestMessageId != null) {
                processedMessages.remove(oldestMessageId);
                messageTimestamps.remove(oldestMessageId);
            }
        }
    }

    private void cleanupOldDevices() {
        long now = System.currentTimeMillis();
        nearbyDevices.entrySet().removeIf(entry ->
                now - entry.getValue().lastSeen > LOCATION_TIMEOUT
        );
    }

    @Nullable
    private String findOldestMessage() {
        long oldestTime = Long.MAX_VALUE;
        String oldestId = null;

        for (Map.Entry<String, Long> entry : messageTimestamps.entrySet()) {
            if (entry.getValue() < oldestTime) {
                oldestTime = entry.getValue();
                oldestId = entry.getKey();
            }
        }

        return oldestId;
    }

    @Override
    public void close() {
        isRunning.set(false);
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(1, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (bluetoothCommunicationService != null) {
            bluetoothCommunicationService.stop();
        }
    }
}