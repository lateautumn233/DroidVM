package cn.classfun.droidvm.ui.agent;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;
import static cn.classfun.droidvm.lib.utils.ThreadUtils.runOnPool;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import cn.classfun.droidvm.DroidVMApp;
import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.ForegroundCallback;
import cn.classfun.droidvm.lib.store.disk.DiskStore;
import cn.classfun.droidvm.ui.agent.base.AgentVM;
import cn.classfun.droidvm.ui.agent.base.BaseAction;
import cn.classfun.droidvm.ui.vm.console.TerminalPrefs;

public final class AgentOperationActivity extends AppCompatActivity
    implements DaemonConnection.EventListener, ForegroundCallback {
    private static final String TAG = "AgentOperationActivity";
    public static final String EXTRA_AGENT_VM_JSON = "agent_vm_json";
    private static final String TERMINAL_URL = "file:///android_asset/terminal/index.html";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder pendingOutput = new StringBuilder();
    private final Runnable fitRunnable = () -> evaluateTerminal("fit");
    private final Rect tmpRect = new Rect();
    private final StringBuilder pendingLog = new StringBuilder();
    private boolean flushScheduled = false;
    private final Runnable flushLogRunnable = () -> {
        String text;
        synchronized (pendingLog) {
            flushScheduled = false;
            if (pendingLog.length() == 0) return;
            text = pendingLog.toString();
            pendingLog.setLength(0);
        }
        appendTerminal(text);
    };
    private View agentRoot;
    private int lastImePadding = 0;
    private ProgressBar progressSpinner;
    private ImageView ivStatus;
    private TextView tvTitle;
    private TextView tvStatus;
    private WebView terminalView;
    private MaterialButton btnCancel;
    private MaterialToolbar toolbar;
    private boolean terminalReady = false;
    private int savedFontSize = TerminalPrefs.DEFAULT_FONT_SIZE;
    private boolean finished = false;
    private String vmId = null;
    private AgentVM agentVM = null;
    private BaseAction action = null;

    @NonNull
    public static Intent createIntent(
        @NonNull Context context,
        @NonNull AgentVM agentVM
    ) {
        var intent = new Intent(context, AgentOperationActivity.class);
        try {
            intent.putExtra(EXTRA_AGENT_VM_JSON, agentVM.toJson().toString());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize AgentVM", e);
        }
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_agent_operation);
        toolbar = findViewById(R.id.toolbar);
        progressSpinner = findViewById(R.id.progress_spinner);
        ivStatus = findViewById(R.id.iv_status);
        tvTitle = findViewById(R.id.tv_title);
        tvStatus = findViewById(R.id.tv_status);
        terminalView = findViewById(R.id.terminal_view);
        btnCancel = findViewById(R.id.btn_cancel);
        btnCancel.setOnClickListener(v -> confirmCancel());
        setupWindowInsets();
        setupTerminalView();
        initialize();
    }

    private void setupWindowInsets() {
        agentRoot = findViewById(R.id.agent_root);
        ViewCompat.setOnApplyWindowInsetsListener(agentRoot, (v, insets) -> {
            Insets bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
            );
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottom = Math.max(bars.bottom, ime.bottom);
            v.setPadding(bars.left, bars.top, bars.right, bottom);
            lastImePadding = ime.bottom;
            return WindowInsetsCompat.CONSUMED;
        });
        agentRoot.getViewTreeObserver().addOnGlobalLayoutListener(globalLayoutListener);
        agentRoot.post(() -> ViewCompat.requestApplyInsets(agentRoot));
    }

    private final ViewTreeObserver.OnGlobalLayoutListener globalLayoutListener = () -> {
        if (agentRoot == null) return;
        View decor = getWindow().getDecorView();
        decor.getWindowVisibleDisplayFrame(tmpRect);
        int imeHeight = Math.max(0, decor.getHeight() - tmpRect.bottom);
        if (Math.abs(imeHeight - lastImePadding) < 80) return;
        int currentBottom = agentRoot.getPaddingBottom();
        int barsBottom = Math.max(0, currentBottom - lastImePadding);
        int newBottom = Math.max(barsBottom, imeHeight);
        if (newBottom == currentBottom) return;
        agentRoot.setPadding(
            agentRoot.getPaddingLeft(),
            agentRoot.getPaddingTop(),
            agentRoot.getPaddingRight(),
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
        terminalView.addOnLayoutChangeListener(
            (v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l == or - ol && b - t == ob - ot) return;
                mainHandler.removeCallbacks(fitRunnable);
                mainHandler.postDelayed(fitRunnable, 80);
            }
        );
        terminalView.loadUrl(TERMINAL_URL);
    }

    private void initialize() {
        toolbar.setTitle(R.string.agent_operation_title);
        toolbar.setNavigationOnClickListener(v -> confirmFinish());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                confirmFinish();
            }
        });
        var intent = getIntent();
        var agentVmJson = intent.getStringExtra(EXTRA_AGENT_VM_JSON);
        if (agentVmJson == null) {
            Log.e(TAG, "Missing agent_vm_json extra");
            finish();
            return;
        }
        try {
            var diskStore = new DiskStore();
            diskStore.load(this);
            agentVM = new AgentVM(diskStore, new JSONObject(agentVmJson));
            action = BaseAction.createAction(agentVM);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse AgentVM", e);
            finish();
            return;
        }
        tvTitle.setText(R.string.agent_operation_title);
        tvStatus.setText(R.string.agent_operation_preparing);
        appendTerminal(getString(R.string.agent_operation_log_preparing));
        runOnPool(this::startAgent);
    }

    private void scheduleAppendLog(@NonNull String text) {
        synchronized (pendingLog) {
            pendingLog.append(text);
            if (flushScheduled) return;
            flushScheduled = true;
        }
        mainHandler.postDelayed(flushLogRunnable, 16);
    }

    private void appendTerminal(@NonNull String data) {
        if (data.isEmpty()) return;
        String text = data;
        if (text.contains("\n") && !text.contains("\r"))
            text = text.replace("\n", "\r\n");
        final String finalText = text;
        mainHandler.post(() -> {
            if (!terminalReady || terminalView == null) {
                pendingOutput.append(finalText);
                return;
            }
            writeTerminal(finalText);
        });
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

    private void applySavedFontSize() {
        int size = TerminalPrefs.getFontSize(this);
        savedFontSize = size;
        evaluateTerminalNumber("setFontSize", size);
    }

    private void startAgent() {
        try {
            agentVM.prepareVars();
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare agent", e);
            runOnUiThread(() -> showFailed(getString(R.string.agent_operation_prepare_failed)));
            return;
        }
        runOnUiThread(() -> {
            tvStatus.setText(R.string.agent_operation_creating_vm);
            appendTerminal(getString(R.string.agent_operation_log_creating_vm));
        });
        var vmConfig = agentVM.buildVM();
        var conn = DaemonConnection.getInstance();
        try {
            registerEventListeners();
            var createReq = new JSONObject();
            createReq.put("command", "vm_create");
            createReq.put("config", vmConfig.toJson());
            var createResp = conn.request(createReq);
            if (!createResp.optBoolean("success", false)) {
                var msg = createResp.optString("message", "unknown error");
                throw new RuntimeException(fmt("vm_create failed: %s", msg));
            }
            vmId = createResp.optString("vm_id", "");
            if (vmId.isEmpty()) throw new RuntimeException("vm_create returned empty vm_id");
            runOnUiThread(() -> {
                tvStatus.setText(R.string.agent_operation_starting_vm);
                appendTerminal(getString(R.string.agent_operation_log_starting_vm));
            });
            var startReq = new JSONObject();
            startReq.put("command", "vm_start");
            startReq.put("vm_id", vmId);
            var startResp = conn.request(startReq);
            if (!startResp.optBoolean("success", false)) {
                var msg = startResp.optString("message", "unknown error");
                throw new RuntimeException(fmt("vm_start failed: %s", msg));
            }
            runOnUiThread(() -> {
                tvStatus.setText(R.string.agent_operation_running);
                appendTerminal(getString(R.string.agent_operation_log_running));
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to create/start agent VM", e);
            runOnUiThread(() -> showFailed(
                getString(R.string.agent_operation_start_failed, e.getMessage())
            ));
            cleanupVM();
        }
    }

    private void registerEventListeners() {
        DaemonConnection.getInstance().addListener(this);
        var app = (DroidVMApp) getApplication();
        app.getVMEventHandler().addForegroundCallback(TAG, this);
    }

    private void unregisterEventListeners() {
        DaemonConnection.getInstance().removeListener(this);
        var app = (DroidVMApp) getApplication();
        app.getVMEventHandler().removeForegroundCallback(TAG);
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
            var stream = data.optString("stream", "");
            if (!stream.equals("uart")) return;
            var text = URLDecoder.decode(data.optString("data", ""), StandardCharsets.UTF_8);
            if (!text.isEmpty()) scheduleAppendLog(text);
        } else if (event.equals("exited")) {
            int exitCode = data.optInt("exit_code", -1);
            mainHandler.post(() -> onVMFinished(exitCode));
        }
    }

    @Override
    public void onDaemonConnected() {
    }

    @Override
    public void onDaemonDisconnected() {
        if (!finished) {
            mainHandler.post(() -> showFailed(getString(R.string.agent_operation_daemon_disconnected)));
        }
    }

    private void onVMFinished(int exitCode) {
        if (finished) return;
        finished = true;
        appendTerminal(fmt(
            "\n--- %s (exit code: %d) ---\n",
            getString(R.string.agent_operation_vm_exited), exitCode
        ));
        runOnPool(() -> {
            String resultMessage = null;
            boolean success = false;
            try {
                if (action != null) {
                    action.checkResult();
                    success = true;
                }
            } catch (Exception e) {
                Log.e(TAG, "Agent result check failed", e);
                resultMessage = e.getMessage();
            }
            cleanupVM();
            final boolean finalSuccess = success;
            final String finalMsg = resultMessage;
            runOnUiThread(() -> {
                progressSpinner.setVisibility(GONE);
                ivStatus.setVisibility(VISIBLE);
                btnCancel.setText(android.R.string.ok);
                btnCancel.setOnClickListener(v -> finish());
                if (finalSuccess) {
                    ivStatus.setImageResource(R.drawable.ic_large_success);
                    tvStatus.setText(R.string.agent_operation_success);
                    appendTerminal(getString(R.string.agent_operation_log_success));
                } else {
                    ivStatus.setImageResource(R.drawable.ic_large_error);
                    if (finalMsg != null) {
                        tvStatus.setText(getString(R.string.agent_operation_failed_detail, finalMsg));
                    } else {
                        tvStatus.setText(getString(R.string.agent_operation_failed, exitCode));
                    }
                    appendTerminal(getString(R.string.agent_operation_log_failed));
                }
            });
        });
    }

    private void cleanupVM() {
        unregisterEventListeners();
        if (vmId != null && !vmId.isEmpty()) {
            try {
                var conn = DaemonConnection.getInstance();
                var destroyReq = new JSONObject();
                destroyReq.put("command", "vm_delete");
                destroyReq.put("vm_id", vmId);
                conn.request(destroyReq);
                Log.i(TAG, fmt("Temporary VM %s destroyed", vmId));
            } catch (Exception e) {
                Log.w(TAG, fmt("Failed to destroy temporary VM %s", vmId), e);
            }
        }
        if (agentVM != null) {
            try {
                agentVM.cleanupVars();
            } catch (Exception e) {
                Log.w(TAG, "Failed to cleanup vars", e);
            }
        }
    }

    private void showFailed(@NonNull String message) {
        finished = true;
        progressSpinner.setVisibility(GONE);
        ivStatus.setVisibility(VISIBLE);
        ivStatus.setImageResource(R.drawable.ic_large_error);
        tvStatus.setText(message);
        btnCancel.setText(android.R.string.ok);
        btnCancel.setOnClickListener(v -> finish());
    }

    private void confirmCancel() {
        if (finished) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.agent_operation_cancel_title)
            .setMessage(R.string.agent_operation_cancel_message)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                finished = true;
                runOnPool(this::cleanupVM);
                finish();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void confirmFinish() {
        if (finished) {
            finish();
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.agent_operation_cancel_title)
            .setMessage(R.string.agent_operation_cancel_message)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                finished = true;
                runOnPool(this::cleanupVM);
                finish();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!finished) {
            runOnPool(this::cleanupVM);
        }
        mainHandler.removeCallbacksAndMessages(null);
        if (agentRoot != null) {
            agentRoot.getViewTreeObserver().removeOnGlobalLayoutListener(globalLayoutListener);
            agentRoot = null;
        }
        if (terminalView != null) {
            terminalView.removeJavascriptInterface("DroidVMConsole");
            terminalView.destroy();
            terminalView = null;
        }
    }

    private final class ConsoleBridge {
        @JavascriptInterface
        public void onReady() {
            mainHandler.post(() -> {
                terminalReady = true;
                applySavedFontSize();
                flushPendingOutput();
                mainHandler.postDelayed(fitRunnable, 50);
            });
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
            TerminalPrefs.setFontSize(AgentOperationActivity.this, size);
        }
    }
}
