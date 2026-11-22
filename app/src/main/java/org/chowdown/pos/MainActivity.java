
package org.chowdown.pos;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private WebView webView;
    private SharedPreferences prefs;
    private String androidId;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("FoodOrderPrefs", MODE_PRIVATE);
        boolean hasShownInstalledMessage = prefs.getBoolean("has_shown_installed_message", false);
        if (!hasShownInstalledMessage) {
            Toast.makeText(this, "App installed!", Toast.LENGTH_LONG).show();
            prefs.edit().putBoolean("has_shown_installed_message", true).apply();
        }

        showStartupProgress("Preparing app & starting local server...");
        initPython();

        androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) {
            androidId = "UNKNOWN_DEVICE";
        }

        boolean isLicensed = prefs.getBoolean("is_licensed", false);

        if (!isLicensed) {
            showActivationDialog();
        } else {
            checkServerConfig();
        }
    }

    private void showStartupProgress(String message) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(60, 60, 60, 60);

        ProgressBar spinner = new ProgressBar(this);
        layout.addView(spinner);

        TextView tvStatus = new TextView(this);
        tvStatus.setText(message);
        tvStatus.setGravity(Gravity.CENTER);
        tvStatus.setPadding(0, 30, 0, 0);
        layout.addView(tvStatus);

        setContentView(layout);
    }

    private void initPython() {
        try {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(this));
            }
            Python py = Python.getInstance();
            py.getModule("server_app").callAttr("start_server");
        } catch (Exception e) {
            Toast.makeText(this, "Embedded Python note: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String getPythonStatus() {
        try {
            if (Python.isStarted()) {
                Object status = Python.getInstance().getModule("server_app").callAttr("get_status");
                if (status != null) {
                    return status.toString();
                }
            }
        } catch (Exception e) {
            return "Status check error: " + e.getMessage();
        }
        return "Python not started";
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format(Locale.US, "%02x", b));
            }
            return hex.toString().toUpperCase(Locale.US);
        } catch (Exception e) {
            return "";
        }
    }

    private String computeExpectedKey(String deviceId, String datePart) {
        String payload = deviceId + ":" + datePart;
        String fullHash = sha256Hex(payload);
        String code12 = fullHash.length() >= 12 ? fullHash.substring(0, 12) : fullHash;
        return datePart + "-" + code12;
    }

    private void showActivationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Food Order App - Activation Required");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        TextView tvInfo = new TextView(this);
        tvInfo.setText("Please provide this Device ID to activate your license:\n\n" + androidId);
        tvInfo.setTextIsSelectable(true);
        layout.addView(tvInfo);

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("YYYYMMDD-XXXXXXXXXXXX");
        layout.addView(input);

        builder.setView(layout);

        builder.setPositiveButton("Activate", (dialog, which) -> {
            String key = input.getText().toString().trim().toUpperCase(Locale.US);
            String datePart = key.length() >= 8 ? key.substring(0, 8) : "";
            String expectedKey = computeExpectedKey(androidId, datePart);

            if (key.matches("\\d{8}-[A-F0-9]{12}") && key.equals(expectedKey)) {
                prefs.edit().putBoolean("is_licensed", true).apply();
                checkServerConfig();
            } else {
                new AlertDialog.Builder(this)
                    .setTitle("Invalid Key")
                    .setMessage("The activation key entered is incorrect. Please try again.\n\nDevice ID: " + androidId + "\nExpected: " + expectedKey)
                    .setPositiveButton("OK", (d, w) -> showActivationDialog())
                    .setCancelable(false)
                    .show();
            }
        });

        builder.setNegativeButton("Exit", (dialog, which) -> finish());
        builder.setCancelable(false);
        builder.show();
    }

    private void checkServerConfig() {
        String savedUrl = prefs.getString("server_url", null);
        if (savedUrl == null) {
            showServerConfigurationDialog();
        } else {
            loadWebView(savedUrl);
        }
    }

    private void showServerConfigurationDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Food Order App - How Will You Run?");
        builder.setMessage("Choose how you want to use the app:\n\n1. Run on This Device (uses built-in local server on 127.0.0.1:8080, no Pydroid 3 needed)\n2. Connect to Another Device (enter the server's IP address on your network)");

        builder.setPositiveButton("This Device (Local Server)", (dialog, which) -> {
            String url = "http://127.0.0.1:8080";
            prefs.edit().putString("server_url", url).apply();
            loadWebView(url);
        });

        builder.setNegativeButton("Another Device (Enter IP)", (dialog, which) -> {
            showIpInputDialog();
        });

        builder.setCancelable(false);
        builder.show();
    }

    private void showIpInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Enter Server IP Address");

        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("e.g. 192.168.1.119 or 172.20.10.2");
        input.setText("192.168.1.119");
        builder.setView(input);

        builder.setPositiveButton("Connect", (dialog, which) -> {
            String ip = input.getText().toString().trim();
            if (ip.isEmpty()) {
                ip = "192.168.1.119";
            }
            String url = "http://" + ip + ":8080";
            prefs.edit().putString("server_url", url).apply();
            loadWebView(url);
        });

        builder.setNegativeButton("Back", (dialog, which) -> {
            showServerConfigurationDialog();
        });

        builder.setCancelable(false);
        builder.show();
    }

    private void loadWebView(String url) {
        webView = new WebView(this);
        setContentView(webView);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showConnectionFailedDialog(url);
                }
            }
        });
        if ("http://127.0.0.1:8080".equals(url)) {
            mainHandler.postDelayed(() -> {
                if (!isFinishing() && !isDestroyed() && webView != null) {
                    webView.loadUrl(url);
                }
            }, 2000);
        } else {
            webView.loadUrl(url);
        }
    }

    private void showConnectionFailedDialog(String currentUrl) {
        if (isFinishing() || isDestroyed()) return;
        String pyStatus = getPythonStatus();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Server Not Reachable");
        builder.setMessage("Could not connect to:\n" + currentUrl + "\n\nStatus: " + pyStatus + "\n\nCheck that the server is running or switch how you connect.");

        builder.setPositiveButton("Retry", (dialog, which) -> loadWebView(currentUrl));
        builder.setNeutralButton("Change Setup", (dialog, which) -> {
            prefs.edit().remove("server_url").apply();
            showServerConfigurationDialog();
        });
        builder.setNegativeButton("Exit", (dialog, which) -> finish());
        builder.setCancelable(false);
        builder.show();
    }
}
