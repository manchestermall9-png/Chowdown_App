package Food.Order.App;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends AppCompatActivity {

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
        builder.setTitle("App Activation Required");
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

    private void startPosApp() {
        // TODO: Put your normal POS startup code here (e.g., setContentView / WebView / server start)
        TextView tv = new TextView(this);
        tv.setText("POS Licensed & Active");
        setContentView(tv);
    }

    public static class LicenseManager {
        private static final String SECRET_SALT = "1962_1969_1993_2001_xyxy";
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
            return androidId == null ? "unknown_device" : androidId.trim().toLowerCase(Locale.US);
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
                String payload = deviceId.trim().toLowerCase(Locale.US) + ":" + expiryStr;
                Mac mac = Mac.getInstance("HmacSHA256");
                SecretKeySpec secretKey = new SecretKeySpec(
                        SECRET_SALT.getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256"
                );
                mac.init(secretKey);
                byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
                String hex = bytesToHex(rawHmac).toUpperCase(Locale.US);
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