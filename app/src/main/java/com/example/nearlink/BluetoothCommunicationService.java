package com.example.nearlink;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import androidx.core.app.ActivityCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.StreamCorruptedException;
import java.util.UUID;

public class BluetoothCommunicationService {
    private static final String TAG = "BluetoothCommService";
    private static final String APP_NAME = "NearLink";
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final BluetoothAdapter bluetoothAdapter;
    private final Handler handler;
    private AcceptThread acceptThread;
    private ConnectThread connectThread;
    private ConnectedThread connectedThread;
    private int state;
    private final Context context;


    public static final int STATE_NONE = 0;       // 未连接
    public static final int STATE_LISTEN = 1;     // 监听连接
    public static final int STATE_CONNECTING = 2; // 正在连接
    public static final int STATE_CONNECTED = 3;  // 已连接

    private static final int MAX_BUFFER_SIZE = 1024 * 1024; // 1MB

    public BluetoothCommunicationService(Context context, Handler handler) {
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        this.state = STATE_NONE;
        this.handler = handler;
        this.context = context;
    }

    private synchronized void setState(int state) {
        Log.d(TAG, "setState() " + this.state + " -> " + state);
        this.state = state;
        handler.obtainMessage(MainActivity.MESSAGE_STATE_CHANGE, state, -1).sendToTarget();
    }

    public synchronized int getState() {
        return state;
    }

    public synchronized void start() {
        Log.d(TAG, "Starting Bluetooth Communication Service");

        // Cancel any thread attempting to make a connection
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        // Cancel any thread currently running a connection
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        setState(STATE_LISTEN);

        // Start the thread to listen on a BluetoothServerSocket
        if (acceptThread == null) {
            acceptThread = new AcceptThread();
            acceptThread.start();
        }
    }

    public synchronized void connect(BluetoothDevice device) {
        String deviceName = "Unknown Device";
        try {
            if (checkBluetoothPermissions()) {
                deviceName = device.getName() != null ? device.getName() : "Unknown Device";
            }
            Log.d(TAG, "Connecting to: " + deviceName);
        } catch (SecurityException e) {
            Log.e(TAG, "Security Exception while getting device name", e);
        }

        // Cancel any thread attempting to make a connection
        if (state == STATE_CONNECTING) {
            if (connectThread != null) {
                connectThread.cancel();
                connectThread = null;
            }
        }

        // Cancel any thread currently running a connection
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        // Start the thread to connect with the given device
        connectThread = new ConnectThread(device);
        connectThread.start();
        setState(STATE_CONNECTING);
    }

    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        String deviceName = "Unknown Device";
        try {
            if (checkBluetoothPermissions()) {
                deviceName = device.getName() != null ? device.getName() : "Unknown Device";
            }
            Log.d(TAG, "Connected to: " + deviceName);
        } catch (SecurityException e) {
            Log.e(TAG, "Security Exception while getting device name", e);
        }

        // Cancel the thread that completed the connection
        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        // Cancel any thread currently running a connection
        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        // Cancel the accept thread because we only want to connect to one device
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        connectedThread = new ConnectedThread(socket);
        connectedThread.start();

        // Send the name of the connected device back to the UI Activity
        Bundle bundle = new Bundle();
        try {
            if (checkBluetoothPermissions()) {
                deviceName = device.getName() != null ? device.getName() : "Unknown Device";
            }
            bundle.putString("device_name", deviceName);
        } catch (SecurityException e) {
            Log.e(TAG, "Security Exception while getting device name for UI", e);
            bundle.putString("device_name", "Unknown Device");
        }
        handler.obtainMessage(MainActivity.MESSAGE_DEVICE_NAME, bundle).sendToTarget();

        setState(STATE_CONNECTED);
    }

    public synchronized void stop() {
        Log.d(TAG, "Stopping Bluetooth Communication Service");

        if (connectThread != null) {
            connectThread.cancel();
            connectThread = null;
        }

        if (connectedThread != null) {
            connectedThread.cancel();
            connectedThread = null;
        }

        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }

        setState(STATE_NONE);
    }

    public void write(byte[] out) {
        ConnectedThread r;
        synchronized (this) {
            if (state != STATE_CONNECTED) return;
            r = connectedThread;
        }
        r.write(out);
    }

    private void connectionFailed() {
        Log.d(TAG, "Connection Failed");
        setState(STATE_LISTEN);

        // Send a failure message back to the Activity
        Bundle bundle = new Bundle();
        bundle.putString("toast", "無法接続デバイス");
        handler.obtainMessage(MainActivity.MESSAGE_TOAST, bundle).sendToTarget();
    }

    private void connectionLost() {
        Log.d(TAG, "Connection Lost");
        setState(STATE_LISTEN);

        // Send a failure message back to the Activity
        Bundle bundle = new Bundle();
        bundle.putString("toast", "デバイス接続が切断されました");
        handler.obtainMessage(MainActivity.MESSAGE_TOAST, bundle).sendToTarget();
    }

    private class AcceptThread extends Thread {
        private BluetoothServerSocket serverSocket = null;

        public AcceptThread() {
            try {
                if (!checkBluetoothPermissions()) {
                    Log.e(TAG, "Required Bluetooth permissions not granted");
                    return;
                }

                serverSocket = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(APP_NAME, MY_UUID);
            } catch (SecurityException e) {
                Log.e(TAG, "Security Exception in AcceptThread constructor", e);
            } catch (IOException e) {
                Log.e(TAG, "Socket listen() failed", e);
            }
        }

        public void run() {
            if (serverSocket == null) {
                Log.e(TAG, "ServerSocket was not initialized");
                return;
            }

            Log.d(TAG, "BEGIN AcceptThread");
            setName("AcceptThread");

            while (state != STATE_CONNECTED) {
                BluetoothSocket socket = null;
                try {
                    socket = serverSocket.accept();
                } catch (SecurityException e) {
                    Log.e(TAG, "Security Exception during accept", e);
                    break;
                } catch (IOException e) {
                    Log.e(TAG, "Socket accept() failed", e);
                    break;
                }

                if (socket != null) {
                    synchronized (BluetoothCommunicationService.this) {
                        try {
                            switch (state) {
                                case STATE_LISTEN:
                                case STATE_CONNECTING:
                                    connected(socket, socket.getRemoteDevice());
                                    break;
                                case STATE_NONE:
                                case STATE_CONNECTED:
                                    try {
                                        socket.close();
                                    } catch (IOException e) {
                                        Log.e(TAG, "Could not close unwanted socket", e);
                                    }
                                    break;
                            }
                        } catch (SecurityException e) {
                            Log.e(TAG, "Security Exception while handling connection", e);
                        }
                    }
                }
            }
            Log.i(TAG, "END AcceptThread");
        }

        public void cancel() {
            Log.d(TAG, "Cancel AcceptThread");
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    Log.e(TAG, "Close of server failed", e);
                }
            }
        }
    }


    private class ConnectThread extends Thread {
        private BluetoothSocket socket = null;
        private final BluetoothDevice device;

        public ConnectThread(BluetoothDevice device) {
            this.device = device;

            try {
                if (!checkBluetoothPermissions()) {
                    Log.e(TAG, "Required Bluetooth permissions not granted");
                    return;
                }
                socket = device.createInsecureRfcommSocketToServiceRecord(MY_UUID);
            } catch (SecurityException e) {
                Log.e(TAG, "Security Exception in ConnectThread constructor", e);
            } catch (IOException e) {
                Log.e(TAG, "Socket create() failed", e);
            }
        }

        public void run() {
            if (socket == null) {
                Log.e(TAG, "Socket was not initialized");
                connectionFailed();
                return;
            }

            Log.i(TAG, "BEGIN ConnectThread");
            setName("ConnectThread");

            try {
                if (!checkBluetoothPermissions()) {
                    Log.e(TAG, "Required Bluetooth permissions not granted");
                    connectionFailed();
                    return;
                }

                // Always cancel discovery because it will slow down a connection
                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }

                // Make a connection to the BluetoothSocket
                socket.connect();
            } catch (SecurityException e) {
                Log.e(TAG, "Security Exception during connection", e);
                connectionFailed();
                try {
                    socket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "Unable to close socket during connection failure", e2);
                }
                return;
            } catch (IOException e) {
                connectionFailed();
                try {
                    socket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "Unable to close socket during connection failure", e2);
                }
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BluetoothCommunicationService.this) {
                connectThread = null;
            }

            try {
                // Start the connected thread
                if (socket != null && socket.isConnected()) {
                    connected(socket, device);
                } else {
                    Log.e(TAG, "Socket is null or not connected");
                    connectionFailed();
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Security Exception during final connection phase", e);
                connectionFailed();
            }
        }

        public void cancel() {
            if (socket != null) {
                try {
                    socket.close();
                } catch (SecurityException e) {
                    Log.e(TAG, "Security Exception closing socket", e);
                } catch (IOException e) {
                    Log.e(TAG, "Close of connect socket failed", e);
                }
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket socket;
        private InputStream inputStream = null;
        private OutputStream outputStream = null;
        private final ByteArrayOutputStream messageBuffer;

        public ConnectedThread(BluetoothSocket socket) {
            Log.d(TAG, "Create ConnectedThread");
            this.socket = socket;
            this.messageBuffer = new ByteArrayOutputStream();

            if (!checkBluetoothPermissions()) {
                Log.e(TAG, "Required Bluetooth permissions not granted");
                return;
            }

            try {
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
            } catch (SecurityException e) {
                Log.e(TAG, "Security Exception getting socket streams", e);
            } catch (IOException e) {
                Log.e(TAG, "Temp sockets not created", e);
            }
        }

        public void run() {
            if (inputStream == null || outputStream == null) {
                Log.e(TAG, "Socket streams were not initialized");
                return;
            }

            Log.i(TAG, "BEGIN ConnectedThread");
            byte[] buffer = new byte[1024];
            int bytes;

            while (true) {
                try {
                    bytes = inputStream.read(buffer);
                    if (bytes > 0) {
                        handleReadMessage(buffer, bytes);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Disconnected", e);
                    connectionLost();
                    break;
                }
            }
        }

        private void handleReadMessage(byte[] buffer, int bytes) {
            try {
                messageBuffer.write(buffer, 0, bytes);

                // 尝试反序列化完整消息
                byte[] messageData = messageBuffer.toByteArray();
                try {
                    ByteArrayInputStream bis = new ByteArrayInputStream(messageData);
                    ObjectInputStream ois = new ObjectInputStream(bis);
                    Message message = (Message) ois.readObject();

                    // 消息成功反序列化，清空缓冲区
                    messageBuffer.reset();

                    // 发送消息到handler
                    handler.obtainMessage(MainActivity.MESSAGE_READ, -1, -1, message)
                            .sendToTarget();

                } catch (StreamCorruptedException e) {
                    // 数据不完整，继续等待更多数据
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing message", e);
                messageBuffer.reset();
            }
        }

        public void write(byte[] buffer) {
            if (outputStream == null) {
                Log.e(TAG, "OutputStream is null, cannot write");
                return;
            }

            try {
                outputStream.write(buffer);
                handler.obtainMessage(MainActivity.MESSAGE_WRITE, -1, -1, buffer)
                        .sendToTarget();
            } catch (SecurityException e) {
                Log.e(TAG, "Security Exception during write", e);
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                socket.close();
            } catch (SecurityException e) {
                Log.e(TAG, "Security Exception closing socket", e);
            } catch (IOException e) {
                Log.e(TAG, "Close of connect socket failed", e);
            }
        }
    }

    private boolean checkBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                            PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
                    PackageManager.PERMISSION_GRANTED;
        }
    }
}