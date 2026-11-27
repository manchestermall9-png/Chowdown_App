package org.chowdown.pos;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.chaquo.python.PyException;
import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    public static final String APP_VERSION = "v9.11.12";
    private static final int LOCAL_PORT = 8080;
    private static final String RENEWAL_EMAIL = "willowton7@gmail.com";

    private final ExecutorService pythonExecutor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean pythonStarted = false;
    private volatile boolean pythonReady = false;

    private FrameLayout rootLayout;
    private LinearLayout loadingOverlay;
    private ProgressBar spinner;
    private TextView loadingText;
    private Button retryButton;
    private WebView webView;

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
    private void ensurePosViewsCreated() {
        if (rootLayout != null) {
            return;
        }

        rootLayout = new FrameLayout(this);

        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                if (isFinishing() || isDestroyed()) {
                    result.cancel();
                    return true;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", (d, w) -> result.confirm())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                if (isFinishing() || isDestroyed()) {
                    result.cancel();
                    return true;
                }
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("OK", (d, w) -> result.confirm())
                        .setNegativeButton("Cancel", (d, w) -> result.cancel())
                        .setOnCancelListener(d -> result.cancel())
                        .show();
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleCustomSchemeUrl(request.getUrl() != null ? request.getUrl().toString() : "");
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleCustomSchemeUrl(url);
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (url != null && url.startsWith("http")) {
                    showLoadingSpinner("Loading POS screen, please wait...", false);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (!pythonReady) {
                    showLoadingSpinner("Finishing server startup, please wait...", false);
                    return;
                }
                mainHandler.postDelayed(() -> {
                    if (pythonReady && !isFinishing() && !isDestroyed()) {
                        hideLoadingSpinner();
                    }
                }, 800);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                if (failingUrl != null && failingUrl.startsWith("rawbt:")) {
                    handleCustomSchemeUrl(failingUrl);
                    return;
                }
                if (!pythonReady) {
                    showLoadingSpinner("Waiting for local POS server on port " + LOCAL_PORT + "...", true);
                }
            }
        });

        rootLayout.addView(
                webView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        loadingOverlay = new LinearLayout(this);
        loadingOverlay.setOrientation(LinearLayout.VERTICAL);
        loadingOverlay.setGravity(Gravity.CENTER);
        loadingOverlay.setBackgroundColor(Color.parseColor("#121212"));
        loadingOverlay.setPadding(pad, pad, pad, pad);
        loadingOverlay.setClickable(true);

        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        LinearLayout.LayoutParams spinnerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        spinnerParams.bottomMargin = (int) (16 * getResources().getDisplayMetrics().density);
        loadingOverlay.addView(spinner, spinnerParams);

        TextView titleView = new TextView(this);
        titleView.setText("Loading POS (" + APP_VERSION + ")");
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18f);
        titleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.bottomMargin = (int) (8 * getResources().getDisplayMetrics().density);
        loadingOverlay.addView(titleView, titleParams);

        loadingText = new TextView(this);
        loadingText.setText("Starting local POS server, please wait...");
        loadingText.setTextColor(Color.parseColor("#CCCCCC"));
        loadingText.setTextSize(14f);
        loadingText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        textParams.bottomMargin = (int) (18 * getResources().getDisplayMetrics().density);
        loadingOverlay.addView(loadingText, textParams);

        retryButton = new Button(this);
        retryButton.setText("Retry Startup");
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(v -> {
            pythonStarted = false;
            pythonReady = false;
            startPythonAndLoadUrl("http://127.0.0.1:" + LOCAL_PORT);
        });
        loadingOverlay.addView(retryButton);

        rootLayout.addView(
                loadingOverlay,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );
    }

    private boolean handleCustomSchemeUrl(String url) {
        if (url != null && url.startsWith("rawbt:")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(
                        this,
                        "Please install and open RawBT to print receipts.",
                        Toast.LENGTH_LONG
                ).show();
            }
            return true;
        }
        return false;
    }

    private void showLoadingSpinner(String message, boolean showRetry) {
        runOnUiThread(() -> {
            if (loadingText != null && message != null) {
                loadingText.setText(message);
            }
            if (spinner != null) {
                spinner.setVisibility(showRetry ? View.GONE : View.VISIBLE);
            }
            if (retryButton != null) {
                retryButton.setVisibility(showRetry ? View.VISIBLE : View.GONE);
            }
            if (loadingOverlay != null) {
                loadingOverlay.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideLoadingSpinner() {
        runOnUiThread(() -> {
            if (loadingOverlay != null) {
                loadingOverlay.setVisibility(View.GONE);
            }
        });
    }

    private String buildExpirationNotice(int daysLeft) {
        if (daysLeft < 0 || daysLeft > 7) {
            return null;
        }
        switch (daysLeft) {
            case 7:
                return "License notice: 7 days left before your activation key expires. Request a new activation code at " + RENEWAL_EMAIL + ".";
            case 6:
                return "License notice: 6 days left before your activation key expires. Request a new activation code at " + RENEWAL_EMAIL + ".";
            case 5:
                return "License notice: 5 days left before your activation key expires. Request a new activation code at " + RENEWAL_EMAIL + ".";
            case 4:
                return "License notice: 4 days left before your activation key expires. Request a new activation code at " + RENEWAL_EMAIL + ".";
            case 3:
                return "License notice: 3 days left before your activation key expires. Request a new activation code at " + RENEWAL_EMAIL + ".";
            case 2:
                return "License notice: 2 days left before your activation key expires. Request a new activation code at " + RENEWAL_EMAIL + ".";
            case 1:
                return "Urgent notice: Your activation key expires in 1 day (tomorrow)! Request a new code at " + RENEWAL_EMAIL + ".";
            case 0:
            default:
                return "FINAL WARNING: Your activation key expires TODAY and the app will cease to work if not renewed! Contact " + RENEWAL_EMAIL + " for a new code.";
        }
    }

    private void startPosApp() {
        ensurePosViewsCreated();
        setContentView(rootLayout);

        int daysLeft = LicenseManager.getDaysUntilExpiration(this);
        String notice = buildExpirationNotice(daysLeft);
        if (notice != null) {
            Toast.makeText(this, notice, Toast.LENGTH_LONG).show();
        }

        showLoadingSpinner("Starting local POS server, please wait...", false);
        startPythonAndLoadUrl("http://127.0.0.1:" + LOCAL_PORT);
    }

    private boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void startPythonAndLoadUrl(String targetUrl) {
        if (pythonStarted && Python.isStarted() && pythonReady) {
            showLoadingSpinner("Opening POS screen...", false);
            webView.loadUrl(targetUrl);
            return;
        }

        showLoadingSpinner("Starting local POS server, please wait...", false);

        pythonExecutor.execute(() -> {
            final String[] startupError = new String[]{null};
            try {
                showLoadingSpinner("Initializing Python engine...", false);
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(getApplicationContext()));
                }

                File dataDir = new File(getFilesDir(), "pos_data");
                if (!dataDir.exists()) {
                    dataDir.mkdirs();
                }

                Python py = Python.getInstance();
                PyObject osModule = py.getModule("os");
                PyObject environ = osModule.get("environ");
                environ.callAttr("__setitem__", "POS_DATA_DIR", dataDir.getAbsolutePath());
                environ.callAttr("__setitem__", "POS_APP_VERSION", APP_VERSION);

                if (!pythonStarted) {
                    pythonStarted = true;
                    pythonExecutor.execute(() -> {
                        try {
                            PyObject module = py.getModule("pos_server");
                            if (module.containsKey("start_server")) {
                                module.callAttr("start_server");
                            } else if (module.containsKey("run_server")) {
                                module.callAttr("run_server");
                            } else if (module.containsKey("main")) {
                                module.callAttr("main");
                            }
                        } catch (Throwable t) {
                            startupError[0] = t.getMessage() != null ? t.getMessage() : t.toString();
                        }
                    });
                }

                boolean portListening = false;
                for (int attempt = 0; attempt < 30; attempt++) {
                    if (startupError[0] != null) {
                        break;
                    }
                    if (isPortOpen("127.0.0.1", LOCAL_PORT)) {
                        portListening = true;
                        break;
                    }
                    Thread.sleep(500);
                }

                if (!portListening) {
                    pythonReady = false;
                    pythonStarted = false;
                    String detail = startupError[0] != null
                            ? "Python error: " + startupError[0]
                            : "Local server did not start on port " + LOCAL_PORT + ".";
                    showLoadingSpinner(detail, true);
                    return;
                }

                pythonReady = true;
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed() && webView != null) {
                        showLoadingSpinner("Opening POS screen...", false);
                        webView.loadUrl(targetUrl);
                    }
                });
            } catch (PyException pyEx) {
                pythonStarted = false;
                final String err = pyEx.getMessage() != null ? pyEx.getMessage() : "Python error";
                showLoadingSpinner("Python startup error: " + err, true);
            } catch (Exception ex) {
                pythonStarted = false;
                final String err = ex.getMessage() != null ? ex.getMessage() : "Failed to start Python";
                showLoadingSpinner("Startup error: " + err, true);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pythonExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
    }

    public static class LicenseManager {
        private static final String PREFS_NAME = "pos_license_prefs";
        private static final String KEY_SAVED_LICENSE = "saved_license_key";
        private static final String KEY_LAST_SEEN_DATE = "last_seen_date_yyyymmdd";
        private static final String[] WHITELISTED_DEVICE_IDS = {
                "Bddb4abb6d76b0e8"
        };

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

        private static boolean isWhitelistedDevice(Context context) {
            String currentId = getDeviceId(context);
            for (String id : WHITELISTED_DEVICE_IDS) {
                if (id != null && id.trim().equalsIgnoreCase(currentId)) {
                    return true;
                }
            }
            return false;
        }

        public static ValidationResult activateKey(Context context, String rawKey) {
            if (isWhitelistedDevice(context)) {
                String cleanKey = rawKey == null ? "" : rawKey.trim().toUpperCase(Locale.US);
                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit()
                        .putString(KEY_SAVED_LICENSE, cleanKey)
                        .putString(KEY_LAST_SEEN_DATE, formatYyyyMmDd(getTodayDateOnly()))
                        .apply();
                return new ValidationResult(true, "License activated successfully.");
            }

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
            if (isWhitelistedDevice(context)) {
                return true;
            }

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

        public static int getDaysUntilExpiration(Context context) {
            if (isWhitelistedDevice(context)) {
                return Integer.MAX_VALUE;
            }

            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String savedKey = prefs.getString(KEY_SAVED_LICENSE, null);
            if (savedKey == null || savedKey.trim().isEmpty()) {
                return -1;
            }

            String[] parts = savedKey.trim().toUpperCase(Locale.US).split("-");
            if (parts.length != 2) {
                return -1;
            }

            Date expiryDate = parseYyyyMmDd(parts[0].trim());
            Date todayDate = getTodayDateOnly();
            if (expiryDate == null || todayDate == null) {
                return -1;
            }

            long diffMillis = expiryDate.getTime() - todayDate.getTime();
            return (int) (diffMillis / (1000L * 60L * 60L * 24L));
        }

        private static String computeShortHmac(String deviceId, String expiryStr) {
            try {
                String cleanId = deviceId == null ? "" : deviceId.trim();
                String pepper = BuildConfig.LICENSE_PEPPER == null ? "" : BuildConfig.LICENSE_PEPPER.trim();
                String payload = cleanId + ":" + expiryStr + ":" + pepper;
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
