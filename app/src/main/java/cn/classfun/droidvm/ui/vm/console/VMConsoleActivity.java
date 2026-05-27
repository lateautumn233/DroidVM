package cn.classfun.droidvm.ui.vm.console;

import static android.view.HapticFeedbackConstants.KEYBOARD_TAP;
import static android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT;
import static android.widget.Toast.LENGTH_SHORT;
import static java.util.Objects.requireNonNull;
import static cn.classfun.droidvm.lib.ui.MaterialMenu.setupToolbarMenu;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;

public final class VMConsoleActivity extends AppCompatActivity
    implements DaemonConnection.EventListener {
    private static final String TAG = "VMConsoleActivity";
    public static final String EXTRA_VM_ID = "vm_id";
    public static final String EXTRA_VM_NAME = "vm_name";
    public static final String EXTRA_STREAM = "stream";
    public static final String EXTRA_LOGS = "logs";
    private static final String DEFAULT_STREAM = "uart";
    private static final String TERMINAL_URL = "file:///android_asset/terminal/index.html";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder pendingOutput = new StringBuilder();
    private final Runnable fitRunnable = () -> evaluateTerminal("fit");
    private final Rect tmpRect = new Rect();
    private View consoleRoot;
    private int lastImePadding = 0;
    private ActivityResultLauncher<String> saveLogLauncher;
    private WebView terminalView;
    private volatile boolean ctrlDown = false;
    private volatile boolean altDown = false;
    private boolean terminalReady = false;
    private int savedFontSize = TerminalPrefs.DEFAULT_FONT_SIZE;
    public String vmId;
    public String vmName;
    public String streamName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_vm_console);
        var contract = new ActivityResultContracts.CreateDocument("text/plain");
        saveLogLauncher = registerForActivityResult(contract, this::onSaveLogResult);
        var intent = getIntent();
        vmId = intent.getStringExtra(EXTRA_VM_ID);
        vmName = intent.getStringExtra(EXTRA_VM_NAME);
        streamName = intent.getStringExtra(EXTRA_STREAM);
        var logs = intent.getBooleanExtra(EXTRA_LOGS, false);
        if (vmId == null) vmId = "";
        if (vmName == null) vmName = "";
        if (streamName == null || streamName.isEmpty()) streamName = DEFAULT_STREAM;
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(fmt("%s - %s", vmName, streamName));
        toolbar.setNavigationOnClickListener(v -> finish());
        setupToolbarMenu(toolbar, R.menu.menu_vm_console, this::onMenuItemClicked);
        terminalView = findViewById(R.id.terminal_view);
        setupWindowInsets();
        setupTerminalView();
        setupExtraKeys();
        DaemonConnection.getInstance().addListener(this);
        loadHistory(logs);
    }

    private void setupWindowInsets() {
        consoleRoot = findViewById(R.id.console_root);
        ViewCompat.setOnApplyWindowInsetsListener(consoleRoot, (v, insets) -> {
            Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottom = Math.max(bars.bottom, ime.bottom);
            v.setPadding(bars.left, bars.top, bars.right, bottom);
            lastImePadding = ime.bottom;
            return WindowInsetsCompat.CONSUMED;
        });
        consoleRoot.getViewTreeObserver().addOnGlobalLayoutListener(globalLayoutListener);
        consoleRoot.post(() -> ViewCompat.requestApplyInsets(consoleRoot));
    }

    private final ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener = () -> {
        if (consoleRoot == null) return;
        View decor = getWindow().getDecorView();
        decor.getWindowVisibleDisplayFrame(tmpRect);
        int imeHeight = Math.max(0, decor.getHeight() - tmpRect.bottom);
        if (Math.abs(imeHeight - lastImePadding) < 80) return;
        int currentBottom = consoleRoot.getPaddingBottom();
        int barsBottom = Math.max(0, currentBottom - lastImePadding);
        int newBottom = Math.max(barsBottom, imeHeight);
        if (newBottom == currentBottom) return;
        consoleRoot.setPadding(
            consoleRoot.getPaddingLeft(),
            consoleRoot.getPaddingTop(),
            consoleRoot.getPaddingRight(),
            newBottom
        );
        lastImePadding = imeHeight;
    };

    @SuppressLint("SetJavaScriptEnabled")
    private void setupTerminalView() {
        WebSettings settings = terminalView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(false);
        terminalView.setBackgroundColor(getColor(android.R.color.black));
        terminalView.setFocusable(true);
        terminalView.setFocusableInTouchMode(true);
        terminalView.addJavascriptInterface(new ConsoleBridge(), "DroidVMConsole");
        terminalView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !TERMINAL_URL.equals(request.getUrl().toString());
            }

            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(
                WebView view,
                WebResourceRequest request
            ) {
                var url = request.getUrl().toString();
                if (url.startsWith("file:///android_asset/terminal/")) return null;
                if (url.equals("about:blank")) return null;
                return new WebResourceResponse("text/plain", "UTF-8", null);
            }
        });
        terminalView.setOnClickListener(v -> focusTerminal());
        terminalView.addOnLayoutChangeListener(
            (v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l == or - ol && b - t == ob - ot) return;
                mainHandler.removeCallbacks(fitRunnable);
                mainHandler.postDelayed(fitRunnable, 80);
            }
        );
        terminalView.loadUrl(TERMINAL_URL);
        focusTerminal();
    }

    private void applySavedFontSize() {
        int size = TerminalPrefs.getFontSize(this);
        savedFontSize = size;
        evaluateTerminalNumber("setFontSize", size);
    }

    private void focusTerminal() {
        if (terminalView == null) return;
        terminalView.requestFocus();
        evaluateTerminal("focus");
        var imm = getSystemService(InputMethodManager.class);
        if (imm != null)
            imm.showSoftInput(terminalView, SHOW_IMPLICIT);
    }

    private boolean onMenuItemClicked(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_save_log) {
            saveLogToFile();
            return true;
        } else if (id == R.id.action_clear_log) {
            clearLog();
            return true;
        }
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        DaemonConnection.getInstance().removeListener(this);
        mainHandler.removeCallbacksAndMessages(null);
        if (consoleRoot != null) {
            consoleRoot.getViewTreeObserver().removeOnGlobalLayoutListener(globalLayoutListener);
            consoleRoot = null;
        }
        if (terminalView != null) {
            terminalView.removeJavascriptInterface("DroidVMConsole");
            terminalView.destroy();
            terminalView = null;
        }
    }

    private void loadHistory(boolean logs) {
        DaemonConnection.OnError err = e -> appendStatus(
            getString(R.string.vm_info_logs_no_logs)
        );
        DaemonConnection.OnUnsuccessful failed = resp ->
            err.onError(new Exception(resp.optString("message", "Unknown error")));
        DaemonConnection.OnResponse success = resp -> {
            var data = logs ? resp.optString(streamName, "") : resp.optString(streamName, "");
            var text = URLDecoder.decode(data, StandardCharsets.UTF_8);
            if (logs && !text.endsWith("\r\n") && !text.endsWith("\n"))
                text += "\r\n";
            appendTerminal(text);
        };
        runOnPool(() -> DaemonConnection.getInstance().buildRequest("vm_console_history")
            .put("vm_id", vmId)
            .put("stream", streamName)
            .onResponse(success)
            .onUnsuccessful(failed)
            .onError(err)
            .invoke());
    }

    private void sendInput(@NonNull String data) {
        if (data.isEmpty()) return;
        runOnPool(() -> DaemonConnection.getInstance().buildRequest("vm_console_write")
            .put("vm_id", vmId)
            .put("stream", streamName)
            .put("data", data)
            .invoke());
    }

    private void appendTerminal(@NonNull String data) {
        if (data.isEmpty()) return;
        mainHandler.post(() -> {
            if (!terminalReady || terminalView == null) {
                pendingOutput.append(data);
                return;
            }
            writeTerminal(data);
        });
    }

    private void appendStatus(@NonNull String message) {
        appendTerminal(fmt("\r\n[droidvm] %s\r\n", message));
    }

    private void flushPendingOutput() {
        if (pendingOutput.length() == 0) return;
        var data = pendingOutput.toString();
        pendingOutput.setLength(0);
        writeTerminal(data);
    }

    private void writeTerminal(@NonNull String data) {
        evaluateTerminal("write", data);
    }

    private void evaluateTerminal(@NonNull String method) {
        evaluateTerminal(method, null);
    }

    private void evaluateTerminal(@NonNull String method, @Nullable String arg) {
        if (terminalView == null) return;
        var js = arg == null
            ? fmt("window.DroidVMTerminal && window.DroidVMTerminal.%s();", method)
            : fmt(
                "window.DroidVMTerminal && window.DroidVMTerminal.%s(%s);",
                method, JSONObject.quote(arg)
            );
        terminalView.evaluateJavascript(js, null);
    }

    private void evaluateTerminalNumber(@NonNull String method, int arg) {
        if (terminalView == null) return;
        var js = fmt(
            "window.DroidVMTerminal && window.DroidVMTerminal.%s(%d);",
            method, arg
        );
        terminalView.evaluateJavascript(js, null);
    }

    @Override
    public void onDaemonEvent(@NonNull JSONObject msg) {
        var type = msg.optString("type", "");
        if (!type.equals("event")) return;
        var data = msg.optJSONObject("data");
        if (data == null) return;
        var eventVmId = data.optString("vm_id", "");
        if (!eventVmId.equals(vmId)) return;
        var event = data.optString("event", "");
        if (event.equals("output")) {
            var eventStream = data.optString("stream", "");
            if (!eventStream.equals(streamName)) return;
            var text = URLDecoder.decode(data.optString("data", ""), StandardCharsets.UTF_8);
            appendTerminal(text);
        } else if (event.equals("exited") || event.equals("state")) {
            var state = data.optString("state", "");
            if (state.equals("stopped")) {
                int code = data.optInt("exit_code", -1);
                appendStatus(fmt("VM exited (code %d).", code));
            }
        }
    }

    @Override
    public void onDaemonConnected() {
    }

    @Override
    public void onDaemonDisconnected() {
        appendStatus(getString(R.string.daemon_disconnected));
    }

    private void sendExtraKey(@NonNull String data) {
        evaluateTerminal("input", data);
    }

    private String applyModifiers(@NonNull String data) {
        if (!ctrlDown && !altDown) return data;
        int cp = data.codePointAt(0);
        int cpLen = Character.charCount(cp);
        StringBuilder sb = new StringBuilder(data.length() + 1);
        if (altDown) sb.append((char) 0x1b);
        if (ctrlDown) {
            sb.append(toCtrlChar(cp));
        } else {
            sb.appendCodePoint(cp);
        }
        sb.append(data, cpLen, data.length());
        ctrlDown = false;
        altDown = false;
        mainHandler.post(this::updateToggleButtons);
        return sb.toString();
    }

    private static char toCtrlChar(int cp) {
        if (cp >= 'a' && cp <= 'z') return (char) (cp - 'a' + 1);
        if (cp >= 'A' && cp <= 'Z') return (char) (cp - 'A' + 1);
        if (cp == ' ' || cp == '@') return 0;
        if (cp == '[') return 0x1b;
        if (cp == '\\') return 0x1c;
        if (cp == ']') return 0x1d;
        if (cp == '^') return 0x1e;
        if (cp == '_' || cp == '?') return 0x1f;
        return (char) cp;
    }

    private void updateToggleButtons() {
        setToggleStyle(findViewById(R.id.btn_ctrl), ctrlDown);
        setToggleStyle(findViewById(R.id.btn_alt), altDown);
    }

    private void setToggleStyle(Button btn, boolean active) {
        if (btn == null) return;
        if (active) {
            btn.setBackgroundColor(getColor(R.color.extra_key_bg_active));
            btn.setTextColor(getColor(R.color.extra_key_text_active));
        } else {
            btn.setBackground(null);
            btn.setTextColor(getColor(R.color.extra_key_text));
        }
    }

    private void setupExtraKeys() {
        setExtraKeyClick(R.id.btn_esc, v -> sendExtraKey("\u001b"));
        setExtraKeyClick(R.id.btn_slash, v -> sendExtraKey("/"));
        setExtraKeyClick(R.id.btn_dash, v -> sendExtraKey("-"));
        setExtraKeyClick(R.id.btn_home, v -> sendExtraKey("\u001b[H"));
        setExtraKeyClick(R.id.btn_up, v -> sendExtraKey("\u001b[A"));
        setExtraKeyClick(R.id.btn_end, v -> sendExtraKey("\u001b[F"));
        setExtraKeyClick(R.id.btn_pgup, v -> sendExtraKey("\u001b[5~"));
        setExtraKeyClick(R.id.btn_tab, v -> sendExtraKey("\t"));
        setExtraKeyClick(R.id.btn_ctrl, v -> {
            ctrlDown = !ctrlDown;
            updateToggleButtons();
        });
        setExtraKeyClick(R.id.btn_alt, v -> {
            altDown = !altDown;
            updateToggleButtons();
        });
        setExtraKeyClick(R.id.btn_left, v -> sendExtraKey("\u001b[D"));
        setExtraKeyClick(R.id.btn_down, v -> sendExtraKey("\u001b[B"));
        setExtraKeyClick(R.id.btn_right, v -> sendExtraKey("\u001b[C"));
        setExtraKeyClick(R.id.btn_pgdn, v -> sendExtraKey("\u001b[6~"));
    }

    private void setExtraKeyClick(int id, View.OnClickListener listener) {
        findViewById(id).setOnClickListener(v -> {
            v.performHapticFeedback(KEYBOARD_TAP);
            listener.onClick(v);
            focusTerminal();
        });
    }

    private void saveLogToFile() {
        var sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
        saveLogLauncher.launch(fmt(
            "droidvm_console_%s_%s_%s.txt",
            vmName, streamName, sdf.format(new Date())
        ));
    }

    private void onSaveLogResult(@Nullable Uri uri) {
        if (uri == null) return;
        Consumer<Integer> showToast = resId -> runOnUiThread(() ->
            Toast.makeText(this, resId, LENGTH_SHORT).show());
        DaemonConnection.OnError err = e -> {
            Log.w(TAG, fmt("Failed to fetch log for %s stream %s", vmName, streamName), e);
            showToast.accept(R.string.vm_info_logs_no_logs);
        };
        DaemonConnection.OnUnsuccessful failed = resp ->
            err.onError(new Exception(resp.optString("message", "Unknown error")));
        DaemonConnection.OnResponse success = resp -> {
            var data = resp.optString(streamName, "");
            var text = URLDecoder.decode(data, StandardCharsets.UTF_8);
            try (var os = requireNonNull(getContentResolver().openOutputStream(uri))) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
                os.flush();
                showToast.accept(R.string.logs_save_success);
            } catch (Exception e) {
                Log.e(TAG, "Failed to save log file", e);
                showToast.accept(R.string.vm_info_logs_save_failed);
            }
        };
        runOnPool(() -> DaemonConnection.getInstance().buildRequest("vm_console_history")
            .put("vm_id", vmId)
            .put("stream", streamName)
            .onResponse(success)
            .onUnsuccessful(failed)
            .onError(err)
            .invoke());
    }

    private void clearLog() {
        runOnPool(() -> DaemonConnection.getInstance().buildRequest("vm_console_clear")
            .put("vm_id", vmId)
            .put("stream", streamName)
            .invoke());
        mainHandler.post(() -> {
            pendingOutput.setLength(0);
            evaluateTerminal("clear");
        });
    }

    private final class ConsoleBridge {
        @JavascriptInterface
        public void onReady() {
            mainHandler.post(() -> {
                terminalReady = true;
                applySavedFontSize();
                flushPendingOutput();
                focusTerminal();
                mainHandler.postDelayed(fitRunnable, 50);
            });
        }

        @JavascriptInterface
        public void onData(String data) {
            if (data == null || data.isEmpty()) return;
            String out = applyModifiers(data);
            if (!out.isEmpty()) sendInput(out);
        }

        @JavascriptInterface
        public void onResize(int cols, int rows) {
            Log.d(TAG, fmt("Terminal resized to %dx%d", cols, rows));
        }

        @JavascriptInterface
        public void onFontSize(int size) {
            if (size <= 0) return;
            if (size == savedFontSize) return;
            savedFontSize = size;
            TerminalPrefs.setFontSize(VMConsoleActivity.this, size);
        }
    }
}
