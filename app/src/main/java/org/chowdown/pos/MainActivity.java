package Food.Order.App;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    public static final String APP_VERSION = "v9.11.01";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String PRINTER_PREFS = "pos_printer_prefs";
    private static final String KEY_SAVED_PRINTER_MAC = "saved_printer_mac";
    private static final String KEY_SAVED_PRINTER_NAME = "saved_printer_name";
    private static final int LINE_WIDTH_58MM = 32;

    private final ExecutorService printExecutor = Executors.newSingleThreadExecutor();
    private WebView webView;
    private String pendingReceiptJson = null;

    private final ActivityResultLauncher<String[]> bluetoothPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean connectGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                        || Boolean.TRUE.equals(result.get(Manifest.permission.BLUETOOTH_CONNECT));

                if (connectGranted) {
                    if (pendingReceiptJson != null) {
                        String payload = pendingReceiptJson;
                        pendingReceiptJson = null;
                        initiatePrintWithSavedOrPickedPrinter(payload);
                    }
                } else {
                    pendingReceiptJson = null;
                    Toast.makeText(this, "Bluetooth permission is required to print receipts.", Toast.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (LicenseManager.isLicenseActive(this)) {
            startPosApp();
        } else {
            showActivationDialog();
        }
    }

    private void showActivationDialog() {
        String deviceId = LicenseManager.getDeviceId(this);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("App Activation Required (" + APP_VERSION + ")");
        builder.setCancelable(false);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (18 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad, pad, pad);

        TextView idLabel = new TextView(this);
        idLabel.setText("Device ID:\n" + deviceId);
        idLabel.setTextIsSelectable(true);
        idLabel.setPadding(0, 0, 0, pad / 2);
        container.addView(idLabel);

        final EditText keyInput = new EditText(this);
        keyInput.setHint("Enter license key (YYYYMMDD-XXXXXXXXXXXX)");
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        container.addView(keyInput);

        builder.setView(container);

        builder.setPositiveButton("Activate", (dialog, which) -> {
            String enteredKey = keyInput.getText().toString().trim();
            LicenseManager.ValidationResult result = LicenseManager.activateKey(this, enteredKey);
            Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();

            if (result.valid) {
                startPosApp();
            } else {
                showActivationDialog();
            }
        });

        builder.setNegativeButton("Exit", (dialog, which) -> finish());

        builder.show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void startPosApp() {
        // TODO: Start your Chaquopy Python server here if not already started before WebView loads
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new PrinterBridge(), "AndroidPrinter");

        setContentView(webView);
        webView.loadUrl("http://127.0.0.1:8080");
    }

    public class PrinterBridge {
        @JavascriptInterface
        public void printReceipt(String receiptJson) {
            runOnUiThread(() -> requestPrint(receiptJson));
        }

        @JavascriptInterface
        public void choosePrinter() {
            runOnUiThread(() -> {
                if (!hasBluetoothConnectPermission()) {
                    pendingReceiptJson = null;
                    requestBluetoothPermissions();
                    return;
                }
                showPairedPrinterPicker(null);
            });
        }
    }

    private void requestPrint(String receiptJson) {
        if (!hasBluetoothConnectPermission()) {
            pendingReceiptJson = receiptJson;
            requestBluetoothPermissions();
            return;
        }
        initiatePrintWithSavedOrPickedPrinter(receiptJson);
    }

    private boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestBluetoothPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH);
            }
        }

        if (needed.isEmpty()) {
            if (pendingReceiptJson != null) {
                String payload = pendingReceiptJson;
                pendingReceiptJson = null;
                initiatePrintWithSavedOrPickedPrinter(payload);
            }
        } else {
            bluetoothPermissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    @SuppressLint("MissingPermission")
    private BluetoothAdapter getBluetoothAdapterSafe() {
        BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        return manager != null ? manager.getAdapter() : BluetoothAdapter.getDefaultAdapter();
    }

    @SuppressLint("MissingPermission")
    private void initiatePrintWithSavedOrPickedPrinter(String receiptJson) {
        BluetoothAdapter adapter = getBluetoothAdapterSafe();
        if (adapter == null) {
            Toast.makeText(this, "This device does not support Bluetooth.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!adapter.isEnabled()) {
            Toast.makeText(this, "Please turn on Bluetooth first.", Toast.LENGTH_LONG).show();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PRINTER_PREFS, MODE_PRIVATE);
        String savedMac = prefs.getString(KEY_SAVED_PRINTER_MAC, null);

        if (savedMac != null && !savedMac.isEmpty()) {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice device : bonded) {
                    if (savedMac.equalsIgnoreCase(device.getAddress())) {
                        sendReceiptToDevice(device, receiptJson);
                        return;
                    }
                }
            }
        }

        showPairedPrinterPicker(receiptJson);
    }

    @SuppressLint("MissingPermission")
    private void showPairedPrinterPicker(String receiptJson) {
        BluetoothAdapter adapter = getBluetoothAdapterSafe();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "Turn on Bluetooth and pair your 58mm printer first.", Toast.LENGTH_LONG).show();
            return;
        }

        Set<BluetoothDevice> bondedSet = adapter.getBondedDevices();
        List<BluetoothDevice> pairedDevices = bondedSet != null
                ? new ArrayList<>(bondedSet)
                : new ArrayList<>();

        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth printer found. Pair it in Android Bluetooth settings first.", Toast.LENGTH_LONG).show();
            return;
        }

        String[] labels = new String[pairedDevices.size()];
        for (int i = 0; i < pairedDevices.size(); i++) {
            BluetoothDevice d = pairedDevices.get(i);
            String name = d.getName() != null ? d.getName() : "Unknown Printer";
            labels[i] = name + " (" + d.getAddress() + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle("Select 58mm Bluetooth Printer")
                .setItems(labels, (dialog, which) -> {
                    BluetoothDevice chosen = pairedDevices.get(which);
                    String chosenName = chosen.getName() != null ? chosen.getName() : "Thermal Printer";
                    getSharedPreferences(PRINTER_PREFS, MODE_PRIVATE)
                            .edit()
                            .putString(KEY_SAVED_PRINTER_MAC, chosen.getAddress())
                            .putString(KEY_SAVED_PRINTER_NAME, chosenName)
                            .apply();

                    if (receiptJson != null) {
                        sendReceiptToDevice(chosen, receiptJson);
                    } else {
                        Toast.makeText(this, "Saved printer: " + chosenName, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @SuppressLint("MissingPermission")
    private void sendReceiptToDevice(BluetoothDevice device, String receiptJson) {
        Toast.makeText(this, "Printing to " + (device.getName() != null ? device.getName() : "printer") + "...", Toast.LENGTH_SHORT).show();

        printExecutor.execute(() -> {
            BluetoothSocket socket = null;
            try {
                BluetoothAdapter adapter = getBluetoothAdapterSafe();
                if (adapter != null && adapter.isDiscovering()) {
                    adapter.cancelDiscovery();
                }

                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();

                byte[] payload = buildEscPos58mmReceipt(receiptJson);
                OutputStream out = socket.getOutputStream();
                out.write(payload);
                out.flush();

                try {
                    Thread.sleep(350);
                } catch (InterruptedException ignored) {
                }

                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Receipt sent to printer.", Toast.LENGTH_SHORT).show()
                );
            } catch (Exception e) {
                final String errMsg = e.getMessage() != null ? e.getMessage() : "Connection failed";
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Print failed: " + errMsg, Toast.LENGTH_LONG).show();
                    getSharedPreferences(PRINTER_PREFS, MODE_PRIVATE).edit().remove(KEY_SAVED_PRINTER_MAC).apply();
                });
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private byte[] buildEscPos58mmReceipt(String receiptJson) throws Exception {
        JSONObject obj = new JSONObject(receiptJson);
        String storeName = obj.optString("storeName", "ORDER RECEIPT");
        String orderNum = obj.optString("orderNum", "");
        String phone = obj.optString("phone", "");
        String timeStr = obj.optString("timeStr", "");
        String deviceLabel = obj.optString("device", "DEVICE");
        String totalStr = obj.optString("total", "0");
        JSONArray items = obj.optJSONArray("items");

        StringBuilder sb = new StringBuilder();
        sb.append("\u001b@");
        sb.append("\u001ba\u0001");
        sb.append("\u001bE\u0001\u001d!\u0011");
        sb.append("ORDER #").append(orderNum).append("\n");
        sb.append("\u001d!\u0000\u001bE\u0001");
        sb.append(fitLine(storeName, LINE_WIDTH_58MM)).append("\n");
        if (!phone.isEmpty()) {
            sb.append(fitLine("CELL: " + phone, LINE_WIDTH_58MM)).append("\n");
        }
        sb.append("\u001bE\u0000");

        sb.append("\u001ba\u0000");
        sb.append(repeatChar('-', LINE_WIDTH_58MM)).append("\n");
        if (!timeStr.isEmpty() || !deviceLabel.isEmpty()) {
            sb.append(fitLine(timeStr + "  " + deviceLabel, LINE_WIDTH_58MM)).append("\n");
            sb.append(repeatChar('-', LINE_WIDTH_58MM)).append("\n");
        }

        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                int qty = item.optInt("qty", 1);
                String name = item.optString("name", "Item");
                double lineTotal = item.optDouble("lineTotal", item.optDouble("price", 0) * qty);
                String rightAmt = "R" + formatRand(lineTotal);
                String leftPart = qty + "x " + name;
                sb.append(formatColumns(leftPart, rightAmt, LINE_WIDTH_58MM)).append("\n");
            }
        }

        sb.append(repeatChar('=', LINE_WIDTH_58MM)).append("\n");
        sb.append("\u001bE\u0001\u001d!\u0001");
        sb.append(formatColumns("TOTAL", "R" + totalStr, LINE_WIDTH_58MM)).append("\n");
        sb.append("\u001d!\u0000\u001bE\u0000");
        sb.append(repeatChar('=', LINE_WIDTH_58MM)).append("\n");

        sb.append("\u001ba\u0001");
        sb.append(fitLine("Thank you for your order!", LINE_WIDTH_58MM)).append("\n");
        sb.append("\n\n\n");

        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private String fitLine(String text, int maxLen) {
        if (text == null) return "";
        String clean = text.trim();
        return clean.length() <= maxLen ? clean : clean.substring(0, maxLen);
    }

    private String formatColumns(String left, String right, int width) {
        String l = left != null ? left.trim() : "";
        String r = right != null ? right.trim() : "";
        if (l.length() + r.length() + 1 > width) {
            int allowedLeft = Math.max(1, width - r.length() - 1);
            l = l.substring(0, Math.min(l.length(), allowedLeft));
        }
        int spaces = Math.max(1, width - l.length() - r.length());
        return l + repeatChar(' ', spaces) + r;
    }

    private String formatRand(double amount) {
        if (Math.abs(amount - Math.round(amount)) < 0.005) {
            return String.valueOf(Math.round(amount));
        }
        return String.format(Locale.US, "%.2f", amount);
    }

    private String repeatChar(char ch, int count) {
        StringBuilder b = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            b.append(ch);
        }
        return b.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        printExecutor.shutdownNow();
    }

    public static class LicenseManager {
        private static final String PREFS_NAME = "pos_license_prefs";
        private static final String KEY_SAVED_LICENSE = "saved_license_key";
        private static final String KEY_LAST_SEEN_DATE = "last_seen_date_yyyymmdd";

        public static class ValidationResult {
            public final boolean valid;
            public final String message;

            public ValidationResult(boolean valid, String message) {
                this.valid = valid;
                this.message = message;
            }
        }

        public static String getDeviceId(Context context) {
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
            return androidId == null ? "unknown_device" : androidId.trim();
        }

        public static ValidationResult activateKey(Context context, String rawKey) {
            if (rawKey == null || rawKey.trim().isEmpty()) {
                return new ValidationResult(false, "Please enter a license key.");
            }

            String normalizedKey = rawKey.trim().toUpperCase(Locale.US);
            String[] parts = normalizedKey.split("-");
            if (parts.length != 2) {
                return new ValidationResult(false, "Invalid key format.");
            }

            String expiryStr = parts[0].trim();
            String providedSig = parts[1].trim();

            if (!expiryStr.matches("\\d{8}") || providedSig.length() != 12) {
                return new ValidationResult(false, "Invalid key format or length.");
            }

            Date expiryDate = parseYyyyMmDd(expiryStr);
            Date todayDate = getTodayDateOnly();
            String todayStr = formatYyyyMmDd(todayDate);

            if (expiryDate == null) {
                return new ValidationResult(false, "Invalid expiration date in key.");
            }

            String expectedSig = computeShortHmac(getDeviceId(context), expiryStr);
            if (!MessageDigest.isEqual(
                    expectedSig.getBytes(StandardCharsets.UTF_8),
                    providedSig.getBytes(StandardCharsets.UTF_8))) {
                return new ValidationResult(false, "License key does not match this device.");
            }

            if (todayDate.after(expiryDate)) {
                return new ValidationResult(false, "This license key has expired.");
            }

            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String lastSeenStr = prefs.getString(KEY_LAST_SEEN_DATE, null);
            if (lastSeenStr != null) {
                Date lastSeenDate = parseYyyyMmDd(lastSeenStr);
                if (lastSeenDate != null && todayDate.before(lastSeenDate)) {
                    return new ValidationResult(false, "System clock change detected. Please check device date.");
                }
            }

            String updatedLastSeen = (lastSeenStr == null || todayStr.compareTo(lastSeenStr) > 0)
                    ? todayStr
                    : lastSeenStr;

            prefs.edit()
                    .putString(KEY_SAVED_LICENSE, normalizedKey)
                    .putString(KEY_LAST_SEEN_DATE, updatedLastSeen)
                    .apply();

            return new ValidationResult(true, "License activated successfully.");
        }

        public static boolean isLicenseActive(Context context) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Date todayDate = getTodayDateOnly();
            String todayStr = formatYyyyMmDd(todayDate);

            String lastSeenStr = prefs.getString(KEY_LAST_SEEN_DATE, null);
            if (lastSeenStr != null) {
                Date lastSeenDate = parseYyyyMmDd(lastSeenStr);
                if (lastSeenDate != null && todayDate.before(lastSeenDate)) {
                    return false;
                }
            }

            String savedKey = prefs.getString(KEY_SAVED_LICENSE, null);
            if (savedKey == null || savedKey.isEmpty()) {
                return false;
            }

            String[] parts = savedKey.split("-");
            if (parts.length != 2) {
                return false;
            }

            String expiryStr = parts[0].trim();
            String savedSig = parts[1].trim();
            Date expiryDate = parseYyyyMmDd(expiryStr);
            if (expiryDate == null || todayDate.after(expiryDate)) {
                return false;
            }

            String expectedSig = computeShortHmac(getDeviceId(context), expiryStr);
            boolean sigMatches = MessageDigest.isEqual(
                    expectedSig.getBytes(StandardCharsets.UTF_8),
                    savedSig.getBytes(StandardCharsets.UTF_8)
            );
            if (!sigMatches) {
                return false;
            }

            if (lastSeenStr == null || todayStr.compareTo(lastSeenStr) > 0) {
                prefs.edit().putString(KEY_LAST_SEEN_DATE, todayStr).apply();
            }

            return true;
        }

        private static String computeShortHmac(String deviceId, String expiryStr) {
            try {
                String cleanId = deviceId == null ? "" : deviceId.trim();
                String payload = cleanId + ":" + expiryStr;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hashBytes = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
                String hex = bytesToHex(hashBytes).toUpperCase(Locale.US);
                return hex.substring(0, 12);
            } catch (Exception e) {
                return "";
            }
        }

        private static Date getTodayDateOnly() {
            String todayStr = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            return parseYyyyMmDd(todayStr);
        }

        private static String formatYyyyMmDd(Date date) {
            return new SimpleDateFormat("yyyyMMdd", Locale.US).format(date);
        }

        private static Date parseYyyyMmDd(String yyyymmdd) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", Locale.US);
                sdf.setLenient(false);
                return sdf.parse(yyyymmdd);
            } catch (ParseException e) {
                return null;
            }
        }

        private static String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        }
    }
}
