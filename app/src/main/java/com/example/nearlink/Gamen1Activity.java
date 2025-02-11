package com.example.nearlink;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import android.widget.Button;
import android.widget.Toast;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Gamen1Activity extends AppCompatActivity {
    private static final String TAG = "Gamen1Activity";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int REQUEST_ENABLE_BT = 101;

    private TextView broadcastText;
    private Button chatButton;
    private MeshNode meshNode;
    private BluetoothService bluetoothService;
    private LocationTracker locationTracker;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_gamen1);

        initializeViews();

        // MeshNodeの初期化
        String nodeId = UUID.randomUUID().toString();
        meshNode = new MeshNode(nodeId, getApplicationContext());  // 修改这里

        // その他の初期化処理...
        checkPermissions();
    }

    private void initializeViews() {
        broadcastText = findViewById(R.id.broadcastText);
        chatButton = findViewById(R.id.chatButton);

        chatButton.setOnClickListener(v -> {
            Intent intent = new Intent(Gamen1Activity.this, Gamen2Activity.class);
            intent.putExtra("NODE_ID", meshNode.getNodeId());
            startActivity(intent);
        });
    }

    private void checkPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            };
        } else {
            permissions = new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            };
        }

        boolean allPermissionsGranted = true;
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
                break;
            }
        }

        if (allPermissionsGranted) {
            initializeServices();
            startNearbyUsersUpdate();
        } else {
            requestPermissions(permissions, PERMISSION_REQUEST_CODE);
        }
    }

    private boolean initializeServices() {
        try {
            String nodeId = UUID.randomUUID().toString();
            meshNode = new MeshNode(nodeId, this);

            BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
            if (bluetoothAdapter == null) {
                Toast.makeText(this, "Bluetoothがサポートされていません", Toast.LENGTH_SHORT).show();
                return false;
            }

            if (!bluetoothAdapter.isEnabled()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                            PackageManager.PERMISSION_GRANTED) {
                        Log.w(TAG, "Bluetooth connect permission not granted");
                        return false;
                    }
                }
                try {
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
                } catch (SecurityException e) {
                    Log.e(TAG, "Security Exception when enabling Bluetooth", e);
                    Toast.makeText(this, "Bluetoothの権限が必要です", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return false;
            }

            bluetoothService = new BluetoothService(bluetoothAdapter, meshNode, this);
            locationTracker = new LocationTracker(this, meshNode);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing services", e);
            Toast.makeText(this, "サービスの初期化に失敗しました", Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void updateNearbyUsersDisplay() {
        Map<String, Location> nearbyLocations = meshNode.getNearbyUserLocations();
        List<BluetoothService.DeviceInfo> nearbyDevices = meshNode.getNearbyDevices();

        StringBuilder displayText = new StringBuilder();
        Location myLocation = locationTracker.getLastLocation();

        if (!nearbyDevices.isEmpty()) {
            displayText.append("発見されたデバイス:\n");
            for (BluetoothService.DeviceInfo device : nearbyDevices) {
                displayText.append(String.format("デバイス名: %s\n", device.name));
            }
            displayText.append("\n");
        }

        if (myLocation != null) {
            for (Map.Entry<String, Location> entry : nearbyLocations.entrySet()) {
                String userId = entry.getKey();
                Location userLocation = entry.getValue();

                float[] results = new float[1];
                Location.distanceBetween(
                        myLocation.getLatitude(),
                        myLocation.getLongitude(),
                        userLocation.getLatitude(),
                        userLocation.getLongitude(),
                        results
                );
                float distance = results[0];

                String userInfo = String.format(
                        "ユーザー: %s\n距離: %.0fm\n\n",
                        userId.substring(0, 8),
                        distance
                );
                displayText.append(userInfo);
            }
        }

        if (displayText.length() == 0) {
            displayText.append("近くにユーザーはいません");
        }

        runOnUiThread(() -> {
            broadcastText.setText(displayText.toString());
            Log.d(TAG, "Updated nearby users display: " + displayText);
        });
    }

    private void startNearbyUsersUpdate() {
        handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                updateNearbyUsersDisplay();
                handler.postDelayed(this, 1000);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initializeServices();
                startNearbyUsersUpdate();
            } else {
                Toast.makeText(this, "権限が必要です", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (bluetoothService != null) {
            bluetoothService.startDiscovery();
        }
        if (locationTracker != null) {
            locationTracker.startTracking();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (bluetoothService != null) {
            bluetoothService.stopDiscovery();
        }
        if (locationTracker != null) {
            locationTracker.stopTracking();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
    }
}