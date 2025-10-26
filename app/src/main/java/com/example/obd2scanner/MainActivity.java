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

        Button testButton = findViewById(R.id.testButton);

        testButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                statusText.append("Reading fault codes...\n");

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            // Send command
                            String command = "03\r";
                            outputStream.write(command.getBytes());
                            outputStream.flush();

                            // Wait longer and read multiple times to get complete response
                            Thread.sleep(500);

                            StringBuilder response = new StringBuilder();
                            byte[] buffer = new byte[1024];
                            int bytes;
                            int readAttempts = 0;

                            // Keep reading until we get the '>' prompt or timeout
                            while (readAttempts < 10) {
                                if (inputStream.available() > 0) {
                                    bytes = inputStream.read(buffer);
                                    String chunk = new String(buffer, 0, bytes);
                                    response.append(chunk);

                                    // Check if we got the prompt (means response is complete)
                                    if (chunk.contains(">")) {
                                        break;
                                    }
                                }
                                Thread.sleep(200);
                                readAttempts++;
                            }

                            final String rawResponse = response.toString();
                            final String parsedDTCs = parseDTCResponse(rawResponse);

                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    statusText.append("\n=== RAW RESPONSE ===\n");
                                    statusText.append(rawResponse);
                                    statusText.append("\n=== PARSED CODES ===\n");
                                    statusText.append(parsedDTCs);
                                    statusText.append("\n==================\n\n");
                                }
                            });

                        } catch (Exception e) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    statusText.append("Error: " + e.getMessage() + "\n");
                                }
                            });
                        }
                    }
                }).start();
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

                            initializeOBD2();
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

    private void sendOBD2Command(String command) {
        if (bluetoothSocket == null || !bluetoothSocket.isConnected()) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    statusText.append("Not connected to device.\n");
                }
            });
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Send command (ELM327 expects commands ending with \r)
                    String cmdToSend = command + "\r";
                    outputStream.write(cmdToSend.getBytes());
                    outputStream.flush();

                    // Wait a bit for response
                    Thread.sleep(200);

                    // Read response
                    StringBuilder response = new StringBuilder();
                    byte[] buffer = new byte[1024];
                    int bytes;

                    while (inputStream.available() > 0) {
                        bytes = inputStream.read(buffer);
                        response.append(new String(buffer, 0, bytes));
                    }

                    // Display on UI thread
                    final String finalResponse = response.toString();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.append("Sent: " + command + "\n");
                            statusText.append("Response: " + finalResponse + "\n\n");
                        }
                    });

                } catch (IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.append("Error sending command: " + e.getMessage() + "\n");
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }).start();
    }

    private void initializeOBD2() {
        statusText.append("Initializing OBD2 adapter...\n\n");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Reset
                    sendCommandAndWait("ATZ", 2000);

                    // Turn echo off
                    sendCommandAndWait("ATE0", 500);

                    // Turn linefeeds off
                    sendCommandAndWait("ATL0", 500);

                    // Set auto protocol
                    sendCommandAndWait("ATSP0", 500);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.append("Initialization complete!\n\n");
                        }
                    });

                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.append("Initialization error: " + e.getMessage() + "\n");
                        }
                    });
                }
            }
        }).start();
    }

    private void sendCommandAndWait(String command, int waitMs) throws IOException, InterruptedException {
        String cmdToSend = command + "\r";
        outputStream.write(cmdToSend.getBytes());
        outputStream.flush();

        Thread.sleep(waitMs);

        // Read and discard response (just for initialization)
        byte[] buffer = new byte[1024];
        while (inputStream.available() > 0) {
            inputStream.read(buffer);
        }

        final String cmd = command;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                statusText.append("Sent: " + cmd + " ✓\n");
            }
        });
    }

    private String parseDTCResponse(String response) {
        StringBuilder result = new StringBuilder();

        // Remove line numbers and formatting
        String cleaned = response.replaceAll("\\d+:\\s*", "")
                .replaceAll("\\s+", "")
                .replaceAll(">", "")
                .replaceAll("SEARCHING\\.\\.\\.", "");

        // Find the 43 response code
        int startIndex = cleaned.indexOf("43");
        if (startIndex == -1) {
            return "No DTC response found";
        }

        try {
            // Skip past "43"
            String data = cleaned.substring(startIndex + 2);

            // Get the count byte
            if (data.length() < 2) {
                return "Response too short";
            }

            String countStr = data.substring(0, 2);
            int count = Integer.parseInt(countStr, 16);

            result.append("ECU reports " + count + " code(s):\n\n");

            // Skip the count byte
            data = data.substring(2);

            // Only parse the number of codes specified by count
            int codesToParse = Math.min(count, data.length() / 4);

            for (int i = 0; i < codesToParse; i++) {
                int pos = i * 4;
                if (pos + 4 > data.length()) break;

                String byte1Str = data.substring(pos, pos + 2);
                String byte2Str = data.substring(pos + 2, pos + 4);

                // Skip if we hit another "43" (new frame marker)
                if (byte1Str.equals("43")) {
                    result.append("\n[Skipping continuation frame]\n");
                    break;
                }

                try {
                    int byte1 = Integer.parseInt(byte1Str, 16);
                    int byte2 = Integer.parseInt(byte2Str, 16);

                    // Check for padding
                    if (byte1 == 0 && byte2 == 0) continue;
                    if (byte1 == 0x55 && byte2 == 0x55) continue;

                    String dtc = decodeDTC(byte1, byte2);
                    result.append(dtc).append("\n");

                } catch (NumberFormatException e) {
                    continue;
                }
            }

            // Also show what came after for debugging
            if (codesToParse * 4 < data.length()) {
                String remaining = data.substring(codesToParse * 4);
                result.append("\n[Remaining data: " + remaining + "]\n");
            }

            return result.toString();

        } catch (Exception e) {
            return "Error parsing: " + e.getMessage();
        }
    }

    private String decodeDTC(int byte1, int byte2) {
        // Top 2 bits determine the letter
        int topBits = (byte1 >> 6) & 0x03;
        char letter;
        switch (topBits) {
            case 0: letter = 'P'; break;
            case 1: letter = 'C'; break;
            case 2: letter = 'B'; break;
            case 3: letter = 'U'; break;
            default: letter = 'P';
        }

        // Next 2 bits (bits 5-4) are first digit
        int digit1 = (byte1 >> 4) & 0x03;

        // Bits 3-0 of byte1 are second digit
        int digit2 = byte1 & 0x0F;

        // byte2 splits into two digits
        int digit3 = (byte2 >> 4) & 0x0F;
        int digit4 = byte2 & 0x0F;

        return String.format("%c%d%X%X%X", letter, digit1, digit2, digit3, digit4);
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