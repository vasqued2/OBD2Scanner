package com.example.obd2scanner;

import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.widget.Button;
import android.widget.TextView;
import android.view.View;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.bluetooth.BluetoothSocket;
import android.widget.EditText;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.Set;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 1;
    private BluetoothAdapter bluetoothAdapter;
    private TextView statusText;
    private boolean isScanning = false;
    private EditText macAddressInput;
    private BluetoothSocket bluetoothSocket;
    private OutputStream outputStream;
    private InputStream inputStream;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // BroadcastReceiver to handle discovered Bluetooth devices
    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    try {
                        if (ActivityCompat.checkSelfPermission(MainActivity.this,
                                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            String deviceName = device.getName();
                            String deviceAddress = device.getAddress();
                            statusText.append("Found: " +
                                    (deviceName != null ? deviceName : "Unknown") +
                                    "\n  Address: " + deviceAddress + "\n\n");
                        }
                    } catch (SecurityException e) {
                        statusText.append("Error accessing device: " + e.getMessage() + "\n");
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                isScanning = false;
                statusText.append("Scan complete.\n");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Get references to UI elements
        Button scanButton = findViewById(R.id.scanButton);
        Button connectButton = findViewById(R.id.connectButton);
        statusText = findViewById(R.id.statusText);
        macAddressInput = findViewById(R.id.macAddressInput);

        // Initialize Bluetooth adapter
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = bluetoothManager.getAdapter();

        if (bluetoothAdapter == null) {
            statusText.setText("Bluetooth is not supported on this device.\n");
            scanButton.setEnabled(false);
            return;
        }

        // Register broadcast receiver for Bluetooth discovery
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(bluetoothReceiver, filter);

        // Set up click listener for Scan button
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPairedDevices();
            }
        });

// Set up click listener for Connect button
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String macAddress = macAddressInput.getText().toString().trim();
                if (macAddress.isEmpty()) {
                    statusText.append("Please enter a MAC address.\n");
                } else {
                    connectToDevice(macAddress);
                }
            }
        });

        // Check permissions
        checkBluetoothPermissions();
    }

    private void startBluetoothScan() {
        // Check if we have permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            statusText.setText("Bluetooth permissions not granted.\n");
            return;
        }

        // Check if Bluetooth is enabled
        if (!bluetoothAdapter.isEnabled()) {
            statusText.setText("Bluetooth is disabled. Please enable Bluetooth and try again.\n");
            return;
        }

        // Cancel any ongoing discovery
        if (bluetoothAdapter.isDiscovering()) {
            bluetoothAdapter.cancelDiscovery();
        }

        // Clear status and start scanning
        statusText.setText("Scanning for Bluetooth devices...\n\n");
        isScanning = true;

        boolean started = bluetoothAdapter.startDiscovery();
        if (!started) {
            statusText.setText("Failed to start Bluetooth scan.\n");
            isScanning = false;
        }
    }

    private void showPairedDevices() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            statusText.append("Bluetooth permissions not granted.\n");
            return;
        }

        statusText.setText("Paired Devices:\n\n");

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                String deviceName = device.getName();
                String deviceAddress = device.getAddress();
                statusText.append((deviceName != null ? deviceName : "Unknown") +
                        "\n  " + deviceAddress + "\n\n");
            }
        } else {
            statusText.append("No paired devices found.\n");
        }
    }

    private void connectToDevice(String macAddress) {
        // Check permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            statusText.append("Bluetooth connect permission not granted.\n");
            return;
        }

        // Disconnect if already connected
        if (bluetoothSocket != null && bluetoothSocket.isConnected()) {
            try {
                bluetoothSocket.close();
            } catch (IOException e) {
                // Ignore
            }
        }

        statusText.append("Connecting to " + macAddress + "...\n");

        // Run connection in background thread to avoid blocking UI
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    BluetoothDevice device = bluetoothAdapter.getRemoteDevice(macAddress);

                    // Cancel discovery to improve connection reliability
                    if (bluetoothAdapter.isDiscovering()) {
                        bluetoothAdapter.cancelDiscovery();
                    }

                    // Create socket and connect
                    bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    bluetoothSocket.connect();

                    // Get input/output streams
                    outputStream = bluetoothSocket.getOutputStream();
                    inputStream = bluetoothSocket.getInputStream();

                    // Update UI on main thread
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.append("Connected successfully!\n");
                            statusText.append("Ready to send OBD2 commands.\n\n");
                        }
                    });

                } catch (SecurityException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.append("Security error: " + e.getMessage() + "\n");
                        }
                    });
                } catch (IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.append("Connection failed: " + e.getMessage() + "\n");
                            statusText.append("Make sure device is paired and in range.\n");
                        }
                    });
                }
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Unregister receiver
        try {
            unregisterReceiver(bluetoothReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver was not registered, ignore
        }

        // Close Bluetooth connection
        if (bluetoothSocket != null) {
            try {
                if (inputStream != null) inputStream.close();
                if (outputStream != null) outputStream.close();
                bluetoothSocket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    private void checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires BLUETOOTH_SCAN and BLUETOOTH_CONNECT
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this,
                        new String[]{
                                Manifest.permission.BLUETOOTH_SCAN,
                                Manifest.permission.BLUETOOTH_CONNECT,
                                Manifest.permission.ACCESS_FINE_LOCATION
                        },
                        PERMISSION_REQUEST_CODE);
            }
        } else {
            // Android 11 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            TextView statusText = findViewById(R.id.statusText);
            boolean allGranted = true;

            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                statusText.setText("Bluetooth permissions granted. Ready to scan.\n");
            } else {
                statusText.setText("Bluetooth permissions denied. Cannot scan for devices.\n");
            }
        }
    }
}