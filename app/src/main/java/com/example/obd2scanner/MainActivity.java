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
import java.util.ArrayList;
import java.util.List;
import android.content.SharedPreferences;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Build;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import androidx.core.app.NotificationCompat;
import android.content.Context;

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
        createNotificationChannel();

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

        Button forgetButton = findViewById(R.id.forgetButton);

        forgetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                forgetDevice();
                macAddressInput.setText(""); // Clear the input field
                statusText.append("You can now select a new device.\n");
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
                            final String errorMsg = e.getMessage();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    statusText.append("Error: " + errorMsg + "\n");
                                    showScanErrorDialog(errorMsg);
                                }
                            });
                        }
                    }
                }).start();
            }
        });

        // Check permissions
        checkBluetoothPermissions();

        // Check for saved device and auto-connect
        String savedMac = getSavedDeviceMacAddress();
        if (savedMac != null) {
            statusText.append("Saved device found: " + savedMac + "\n");
            statusText.append("Connecting automatically...\n\n");
            macAddressInput.setText(savedMac);
            // Auto-connect
            connectToDevice(savedMac);
        } else {
            statusText.append("No saved device. Please select your OBD2 adapter.\n\n");
        }
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

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

        if (pairedDevices.size() == 0) {
            statusText.append("No paired devices found. Please pair your OBD2 adapter in Bluetooth settings first.\n");
            return;
        }

        // Create arrays for device names and addresses
        final String[] deviceNames = new String[pairedDevices.size()];
        final String[] deviceAddresses = new String[pairedDevices.size()];

        int i = 0;
        for (BluetoothDevice device : pairedDevices) {
            String name = device.getName();
            deviceNames[i] = (name != null ? name : "Unknown Device");
            deviceAddresses[i] = device.getAddress();
            i++;
        }

        // Show selection dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select OBD2 Device");
        builder.setItems(deviceNames, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String selectedMac = deviceAddresses[which];
                String selectedName = deviceNames[which];

                statusText.append("Selected: " + selectedName + "\n");
                statusText.append("MAC: " + selectedMac + "\n\n");

                macAddressInput.setText(selectedMac);

                // Automatically connect to selected device
                connectToDevice(selectedMac);
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
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

                            saveDeviceMacAddress(macAddress);
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

                            // Show error dialog with option to forget device
                            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                            builder.setTitle("Connection Failed");
                            builder.setMessage("Could not connect to saved device.\n\n" +
                                    "Device: " + macAddress + "\n\n" +
                                    "Possible issues:\n" +
                                    "• Device is out of range\n" +
                                    "• Car is not running\n" +
                                    "• OBD2 adapter is not powered\n\n" +
                                    "Would you like to forget this device and select a different one?");

                            builder.setPositiveButton("Forget & Reselect", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    forgetDevice();
                                    macAddressInput.setText("");
                                    statusText.append("\nReady to select a new device.\n\n");
                                }
                            });

                            builder.setNegativeButton("Retry Later", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    statusText.append("Connection cancelled. You can retry when ready.\n\n");
                                }
                            });

                            builder.show();
                        }
                    });
                }
            }
        }).start();
    }

    private void autoScanForCodes() {
        statusText.append("Auto-scanning for fault codes...\n");

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Wait a bit for initialization to complete
                    Thread.sleep(500);

                    // Send command
                    String command = "03\r";
                    outputStream.write(command.getBytes());
                    outputStream.flush();

                    // Wait for full response
                    Thread.sleep(1500);

                    // Read response
                    StringBuilder response = new StringBuilder();
                    byte[] buffer = new byte[1024];
                    int bytes;
                    int readAttempts = 0;

                    while (readAttempts < 10) {
                        if (inputStream.available() > 0) {
                            bytes = inputStream.read(buffer);
                            String chunk = new String(buffer, 0, bytes);
                            response.append(chunk);

                            if (chunk.contains(">")) {
                                break;
                            }
                        }
                        Thread.sleep(200);
                        readAttempts++;
                    }

                    final String rawResponse = response.toString();
                    final List<String> codes = parseDTCResponseToList(rawResponse);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            handleCodeResults(codes);
                        }
                    });

                } catch (Exception e) {
                    final String errorMsg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            statusText.append("Auto-scan error: " + errorMsg + "\n");
                            showScanErrorDialog(errorMsg);
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
                            autoScanForCodes();
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

    private void showScanErrorDialog(String errorMessage) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Scan Failed");
        builder.setMessage("Could not read fault codes from the vehicle.\n\n" +
                "Error: " + errorMessage + "\n\n" +
                "Possible issues:\n" +
                "• Vehicle communication lost\n" +
                "• OBD2 adapter malfunction\n\n" +
                "Would you like to forget this device and select a different one?");

        builder.setPositiveButton("Forget & Reselect", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                forgetDevice();
                macAddressInput.setText("");
                statusText.append("\nReady to select a new device.\n\n");
            }
        });

        builder.setNegativeButton("Keep Device", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                statusText.append("Device kept. You can try scanning again later.\n\n");
            }
        });

        builder.show();
    }

    private List<String> parseDTCResponseToList(String response) {
        List<String> allCodes = new ArrayList<>();

        // Remove line numbers and formatting
        String cleaned = response.replaceAll("\\d+:\\s*", "")
                .replaceAll("\\s+", "")
                .replaceAll(">", "")
                .replaceAll("SEARCHING\\.\\.\\.", "");

        // Parse ALL occurrences of "43" as potential DTC response frames
        int searchStart = 0;

        while (true) {
            int frameStart = cleaned.indexOf("43", searchStart);
            if (frameStart == -1) break;

            try {
                String data = cleaned.substring(frameStart + 2);
                if (data.length() < 2) break;
                data = data.substring(2); // Skip count byte

                int nextFrame = data.indexOf("43");
                if (nextFrame > 0) {
                    data = data.substring(0, nextFrame);
                }

                for (int i = 0; i < data.length() - 3; i += 4) {
                    String byte1Str = data.substring(i, i + 2);
                    String byte2Str = data.substring(i + 2, i + 4);

                    try {
                        int byte1 = Integer.parseInt(byte1Str, 16);
                        int byte2 = Integer.parseInt(byte2Str, 16);

                        if (byte1 == 0 && byte2 == 0) continue;

                        String dtc = decodeDTC(byte1, byte2);
                        if (!allCodes.contains(dtc)) {
                            allCodes.add(dtc);
                        }

                    } catch (NumberFormatException e) {
                        break;
                    }
                }

                searchStart = frameStart + 4;

            } catch (Exception e) {
                break;
            }
        }

        return allCodes;
    }
    private String parseDTCResponse(String response) {
        StringBuilder result = new StringBuilder();
        List<String> allCodes = new ArrayList<>();

        // Remove line numbers and formatting
        String cleaned = response.replaceAll("\\d+:\\s*", "")
                .replaceAll("\\s+", "")
                .replaceAll(">", "")
                .replaceAll("SEARCHING\\.\\.\\.", "");

        // Parse ALL occurrences of "43" as potential DTC response frames
        int searchStart = 0;

        while (true) {
            int frameStart = cleaned.indexOf("43", searchStart);
            if (frameStart == -1) break;

            try {
                // Skip past "43"
                String data = cleaned.substring(frameStart + 2);

                // Get the count/header byte (skip it)
                if (data.length() < 2) break;
                data = data.substring(2);

                // Parse up to next "43" or end of string
                int nextFrame = data.indexOf("43");
                if (nextFrame > 0) {
                    data = data.substring(0, nextFrame);
                }

                // Parse all valid DTC pairs in this frame
                for (int i = 0; i < data.length() - 3; i += 4) {
                    String byte1Str = data.substring(i, i + 2);
                    String byte2Str = data.substring(i + 2, i + 4);

                    try {
                        int byte1 = Integer.parseInt(byte1Str, 16);
                        int byte2 = Integer.parseInt(byte2Str, 16);

                        // Only skip 00 00 padding (not 55 55!)
                        if (byte1 == 0 && byte2 == 0) {
                            continue;
                        }

                        String dtc = decodeDTC(byte1, byte2);

                        // Avoid duplicates
                        if (!allCodes.contains(dtc)) {
                            allCodes.add(dtc);
                        }

                    } catch (NumberFormatException e) {
                        break;
                    }
                }

                // Move search position past this frame
                searchStart = frameStart + 4;

            } catch (Exception e) {
                break;
            }
        }

        // Build result
        if (allCodes.isEmpty()) {
            return "No fault codes found (vehicle is healthy!)";
        }

        result.append("Found " + allCodes.size() + " fault code(s):\n\n");
        for (String code : allCodes) {
            result.append(code + "\n");
        }

        return result.toString();
    }

    private void handleCodeResults(List<String> codes) {
        statusText.append("\n=== SCAN RESULTS ===\n");

        if (codes.isEmpty()) {
            // No codes detected
            statusText.append("No fault codes detected.\n\n");
            showSilentNotification("No issues detected", "OBD2 scan complete - no fault codes found.");
            return;
        }

        // Filter out P0420 to check for unexpected codes
        List<String> unexpectedCodes = new ArrayList<>();
        boolean hasP0420 = false;

        for (String code : codes) {
            if (code.equals("P0420")) {
                hasP0420 = true;
            } else {
                unexpectedCodes.add(code);
            }
        }

        if (unexpectedCodes.isEmpty()) {
            // Only P0420 detected
            statusText.append("Only P0420 detected (expected).\n\n");
            showSilentNotification("Only P0420 detected (expected)",
                    "OBD2 scan complete - catalyst efficiency code present as expected.");
        } else {
            // Unexpected codes found - show alert
            statusText.append("UNEXPECTED CODES DETECTED!\n");
            for (String code : codes) {
                statusText.append("  " + code + "\n");
            }
            statusText.append("\n");

            showUnexpectedCodesAlert(codes, hasP0420);
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

        // Check notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "OBD2 Scanner";
            String description = "Fault code scan notifications";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel("obd2_channel", name, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void showSilentNotification(String title, String message) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "obd2_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(1, builder.build());

        statusText.append("Silent notification posted.\n\n");
    }

    private void showUnexpectedCodesAlert(List<String> allCodes, boolean hasP0420) {
        StringBuilder message = new StringBuilder();
        message.append("The following fault codes were detected:\n\n");

        for (String code : allCodes) {
            if (code.equals("P0420")) {
                message.append("• ").append(code).append(" (expected)\n");
            } else {
                message.append("• ").append(code).append(" ⚠️\n");
            }
        }

        message.append("\nUnexpected codes require attention!");

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("⚠️ Unexpected Fault Codes");
        builder.setMessage(message.toString());
        builder.setCancelable(false); // Must dismiss explicitly

        builder.setPositiveButton("Dismiss", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                statusText.append("Alert dismissed. Closing app.\n");
                // Close app after dismissal
                finish();
            }
        });

        builder.show();
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

    // SharedPreferences methods for storing device MAC address
    private void saveDeviceMacAddress(String macAddress) {
        SharedPreferences prefs = getSharedPreferences("OBD2Settings", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("device_mac", macAddress);
        editor.apply();
        statusText.append("Device saved: " + macAddress + "\n");
    }

    private String getSavedDeviceMacAddress() {
        SharedPreferences prefs = getSharedPreferences("OBD2Settings", MODE_PRIVATE);
        return prefs.getString("device_mac", null);
    }

    private void forgetDevice() {
        SharedPreferences prefs = getSharedPreferences("OBD2Settings", MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove("device_mac");
        editor.apply();
        statusText.append("Saved device forgotten.\n");
    }
}