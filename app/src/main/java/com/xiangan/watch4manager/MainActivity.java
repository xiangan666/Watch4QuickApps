package com.xiangan.watch4manager;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.xiangan.watch4.protocol.Crypto;
import com.xiangan.watch4.protocol.QuickApp;
import com.xiangan.watch4manager.privilege.IPrivilegeService;
import com.xiangan.watch4manager.privilege.PrivilegeService;
import com.xiangan.watch4manager.privilege.RootAccess;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private static final int GREEN = 0xff116a59, INK = 0xff19382e, MUTED = 0xff67766e;
    private static final int REQUEST_BLUETOOTH = 71, REQUEST_RPK = 72, REQUEST_SHIZUKU = 73, REQUEST_LOG_EXPORT = 74;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SecretStore store;
    private WatchService service;
    private boolean bound, importing, dead;
    private int selectedTab;
    private String address = "", authKey = "", filter = "", access = "Root";
    private RpkFile imported;
    private LinearLayout root, body, appsList;
    private TextView connectionText, message, countText, logText;
    private ProgressBar progress;
    private EditText addressField, keyField;
    private CheckBox remember, stopOfficial;
    private Button connectButton, installButton, refreshButton;
    private final List<Button> navigation = new ArrayList<>();
    private List<QuickApp> renderedApps;
    private IPrivilegeService privilege;
    private CountDownLatch privilegeReady;
    private final Shizuku.UserServiceArgs privilegeArgs = new Shizuku.UserServiceArgs(
            new ComponentName("com.xiangan.watch4manager", PrivilegeService.class.getName()))
            .daemon(false).processNameSuffix("privilege").debuggable(false).version(BuildConfig.VERSION_CODE);

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((WatchService.LocalBinder) binder).service(); service.listen(new WatchService.Listener() {
                @Override public void changed() { update(); }
                @Override public void operationRejected(String reason) { showMessage(reason); }
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; update(); }
    };
    private final ServiceConnection privilegeConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            privilege = IPrivilegeService.Stub.asInterface(binder);
            if (privilegeReady != null) privilegeReady.countDown();
        }
        @Override public void onServiceDisconnected(ComponentName name) { privilege = null; }
    };
    private final Shizuku.OnRequestPermissionResultListener shizukuPermission = (request, grant) -> {
        if (request == REQUEST_SHIZUKU) {
            if (grant == PackageManager.PERMISSION_GRANTED) startShizukuImport();
            else showMessage("未获得 Shizuku 授权，可改用 Root 或手动填写");
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new SecretStore(this); address = store.address();
        try { authKey = store.key(); } catch (Exception e) { authKey = ""; }
        selectedTab = address.isEmpty() ? 1 : 0;
        if (state != null) selectedTab = state.getInt("tab", selectedTab);
        Shizuku.addRequestPermissionResultListener(shizukuPermission);
        createUi();
        bound = bindService(new Intent(this, WatchService.class), serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void createUi() {
        root = column(); root.setBackgroundColor(0xfff4f6f3);
        root.setPadding(dp(20), dp(12), dp(20), dp(8));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top; bottom = bars.bottom;
            } else { top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom(); }
            root.setPadding(dp(20), dp(12) + top, dp(20), dp(8) + bottom);
            return insets;
        });
        TextView eyebrow = text("REDMI WATCH 4", 11, GREEN); eyebrow.setLetterSpacing(.16f); root.addView(eyebrow);
        TextView heading = text("快应用管理", 29, INK); heading.setTypeface(null, Typeface.BOLD); margin(root, heading, 4);
        connectionText = text("未连接", 13, MUTED); margin(root, connectionText, 6);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100);
        margin(root, progress, 8); progress.setVisibility(View.GONE);
        message = text("", 13, GREEN); margin(root, message, 6); message.setVisibility(View.GONE);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1); scrollParams.topMargin = dp(16);
        root.addView(scroll, scrollParams); body = column(); body.setPadding(0, 0, 0, dp(20)); scroll.addView(body);
        LinearLayout nav = row(); nav.setPadding(0, dp(8), 0, 0);
        String[] labels = {"快应用", "连接", "日志"};
        for (int i = 0; i < labels.length; i++) {
            final int tab = i; Button button = button(labels[i], false, () -> switchTab(tab));
            navigation.add(button); nav.addView(button, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        root.addView(nav); setContentView(root); drawTab();
    }

    private void switchTab(int tab) { captureConnection(); selectedTab = tab; drawTab(); }
    private void drawTab() {
        if (selectedTab == 1) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        body.removeAllViews(); addressField = null; keyField = null; appsList = null; logText = null;
        installButton = null; refreshButton = null; connectButton = null; renderedApps = null;
        for (int i = 0; i < navigation.size(); i++) navigation.get(i).setTextColor(i == selectedTab ? GREEN : MUTED);
        if (selectedTab == 0) drawApps(); else if (selectedTab == 1) drawConnection(); else drawLogs();
        update();
    }

    private void drawApps() {
        LinearLayout hero = card(); body.addView(hero);
        hero.addView(text("你的手表，你的应用", 21, INK));
        margin(hero, text("从本机导入 RPK，连接后安装到 Watch 4。", 14, MUTED), 8);
        margin(hero, button("选择 RPK 文件", true, this::chooseRpk), 18);
        if (imported != null) {
            margin(hero, text(imported.name + " · " + imported.versionName, 17, INK), 16);
            margin(hero, text(imported.packageName + "\n" + (imported.bytes.length / 1024) + " KiB · versionCode " + imported.versionCode, 12, MUTED), 6);
            margin(hero, text(imported.hasSignature ? "包含签名信息 · 由手表校验证书" : "缺少可识别签名，不能安装", 12,
                    imported.hasSignature ? GREEN : 0xffaa3f32), 8);
            installButton = button("安装到手表", true, this::confirmInstall); margin(hero, installButton, 12);
        }
        LinearLayout title = row(); countText = text("已安装", 19, INK); title.addView(countText, new LinearLayout.LayoutParams(0, -2, 1));
        refreshButton = button("刷新", false, () -> { if (service != null) service.refresh(); }); title.addView(refreshButton);
        margin(body, title, 20);
        EditText search = field("搜索名称或包名", false); search.setText(filter);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter = s.toString(); renderApps(); }
            @Override public void afterTextChanged(Editable e) {}
        }); margin(body, search, 6);
        appsList = column(); margin(body, appsList, 12);
    }

    private void renderApps() {
        if (appsList == null) return;
        appsList.removeAllViews();
        List<QuickApp> apps = service == null ? Collections.emptyList() : service.apps();
        countText.setText("已安装 · " + apps.size()); renderedApps = apps;
        if (service == null || !service.connected()) {
            LinearLayout empty = card(); empty.addView(text("连接后查看快应用", 18, INK));
            margin(empty, text("从设备读取真实应用列表。先在「连接」中导入配对信息并建立连接。", 14, MUTED), 8);
            margin(empty, button("前往连接", true, () -> switchTab(1)), 16); appsList.addView(empty); return;
        }
        String query = filter.toLowerCase(Locale.ROOT).trim(); int count = 0;
        for (QuickApp app : apps) {
            if (!query.isEmpty() && !(app.name + " " + app.packageName).toLowerCase(Locale.ROOT).contains(query)) continue;
            count++;
            LinearLayout item = card(); item.addView(text(app.name, 18, INK));
            margin(item, text(app.packageName, 12, MUTED), 6);
            LinearLayout actions = row();
            Button launch = button("打开", false, () -> service.launch(app));
            Button remove = button("卸载", false, () -> new AlertDialog.Builder(this).setTitle("卸载「" + app.name + "」？")
                    .setMessage("将从手表删除 " + app.packageName + "。该应用的存档也可能删除。")
                    .setNegativeButton("取消", null).setPositiveButton("卸载", (d, w) -> service.uninstall(app)).show());
            launch.setEnabled(!service.busy() && app.fingerprint.length > 0); remove.setEnabled(!service.busy() && app.fingerprint.length > 0);
            remove.setTextColor(0xffa44335);
            actions.addView(launch, new LinearLayout.LayoutParams(0, dp(44), 1));
            actions.addView(remove, new LinearLayout.LayoutParams(0, dp(44), 1)); margin(item, actions, 10);
            TextView details = text("查看详情", 12, GREEN); details.setPadding(0, dp(8), 0, dp(8));
            details.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(app.name)
                    .setMessage("包名：" + app.packageName + "\n设备签名指纹：" + Crypto.hex(app.fingerprint)
                            + "\n\n设备列表未提供可确认的版本号。")
                    .setPositiveButton("关闭", null).show()); item.addView(details);
            margin(appsList, item, 10);
        }
        if (count == 0) appsList.addView(text(query.isEmpty() ? "设备未返回已安装快应用。" : "没有匹配的应用。", 15, MUTED));
    }

    private void drawConnection() {
        LinearLayout card = card(); body.addView(card);
        card.addView(text("连接 Redmi Watch 4", 21, INK));
        margin(card, text("保持手表与此手机已配对。可自动读取本机运动健康中的配对信息。", 14, MUTED), 8);
        LinearLayout imports = row();
        imports.addView(button("Root 导入", true, this::rootImport), new LinearLayout.LayoutParams(0, dp(50), 1));
        imports.addView(button("Shizuku 导入", false, this::shizukuImport), new LinearLayout.LayoutParams(0, dp(50), 1)); margin(card, imports, 16);
        margin(card, text("Shizuku 需以 Root 身份运行才能直接读取私有配对库；ADB 模式可用于临时停止运动健康。", 12, MUTED), 10);
        margin(card, text("手表蓝牙地址", 13, INK), 20);
        addressField = field("AA:BB:CC:DD:EE:FF", false); addressField.setText(address); margin(card, addressField, 6);
        margin(card, button("选择已配对的 Watch 4", false, this::chooseBonded), 6);
        margin(card, text("配对密钥", 13, INK), 14);
        keyField = field("自动导入，或填写 32 位十六进制密钥", true); keyField.setText(authKey); margin(card, keyField, 6);
        remember = new CheckBox(this); remember.setText("在本机加密保存配对信息"); remember.setTextSize(13); remember.setChecked(true); margin(card, remember, 10);
        stopOfficial = new CheckBox(this); stopOfficial.setText("连接前临时停止运动健康（需要授权）"); stopOfficial.setTextSize(13); stopOfficial.setChecked(false); margin(card, stopOfficial, 4);
        connectButton = button("连接手表", true, this::connect); margin(card, connectButton, 14);
        margin(card, button("断开 / 取消当前操作", false, () -> { if (service != null) service.disconnect(); }), 6);
        LinearLayout more = card(); margin(body, more, 16); more.addView(text("本机配对信息", 17, INK));
        margin(more, text("密钥仅在本机使用，经 Android Keystore 加密保存。管理完成后断开，再打开运动健康恢复同步。", 13, MUTED), 8);
        margin(more, button("清除已保存的密钥", false, () -> new AlertDialog.Builder(this).setTitle("清除本机密钥？")
                .setMessage("只删除管理器保存的连接信息，不解除手表绑定。")
                .setNegativeButton("取消", null).setPositiveButton("清除", (d, w) -> {
                    try { store.clear(); address = ""; authKey = ""; addressField.setText(""); keyField.setText(""); showMessage("已清除管理器保存的密钥"); }
                    catch (Exception e) { showMessage("清除失败，请重试"); }
                }).show()), 10);
        margin(more, button("打开小米运动健康", false, this::openOfficial), 4);
        margin(body, text("v" + BuildConfig.VERSION_NAME + " · 本地运行 · 相安", 12, MUTED), 18);
    }

    private void drawLogs() {
        body.addView(text("操作记录", 21, INK));
        margin(body, text("日志保存在本机，断开或重启应用后仍可导出。配对密钥和蓝牙地址会自动隐藏。", 13, MUTED), 8);
        CheckBox detailed = new CheckBox(this); detailed.setText("详细日志"); detailed.setTextColor(INK);
        detailed.setChecked(DiagnosticLog.get(this).detailed());
        detailed.setOnCheckedChangeListener((view, enabled) -> {
            if (service != null) service.setDetailedLogs(enabled); else DiagnosticLog.get(this).setDetailed(enabled);
            showMessage(enabled ? "已开启详细日志，请重新执行需要分析的操作" : "已关闭详细日志，继续保留基本操作记录");
        }); margin(body, detailed, 14);
        margin(body, text("详细模式记录连接阶段、指令编号、状态码、分片进度与异常链，不记录鉴权载荷或应用内容。最多保留最近 2000 条记录。", 12, MUTED), 4);
        LinearLayout actions = row(); actions.addView(button("导出日志文件", true, this::exportLogs));
        actions.addView(button("清空", false, () -> { if (service != null) service.clearLogs(); })); margin(body, actions, 12);
        logText = text("暂无操作记录", 12, INK); logText.setTypeface(Typeface.MONOSPACE); logText.setTextIsSelectable(true);
        LinearLayout card = card(); card.addView(logText); margin(body, card, 14);
    }

    private void captureConnection() {
        if (addressField != null) address = addressField.getText().toString().trim().toUpperCase(Locale.ROOT);
        if (keyField != null) authKey = keyField.getText().toString().trim();
    }
    private void update() {
        if (dead || connectionText == null) return;
        boolean busy = importing || service != null && service.busy();
        connectionText.setText(service == null ? "未连接" : service.status());
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        progress.setIndeterminate(importing || service != null && service.progress() == 0);
        if (service != null) progress.setProgress(service.progress());
        if (connectButton != null) connectButton.setEnabled(!busy && service != null && !service.connected());
        if (installButton != null) installButton.setEnabled(!busy && service != null && service.connected() && imported != null && imported.hasSignature);
        if (refreshButton != null) refreshButton.setEnabled(!busy && service != null && service.connected());
        if (appsList != null) renderApps();
        if (logText != null && service != null) logText.setText(service.logs().isEmpty() ? "暂无操作记录" : service.logs());
    }

    private void rootImport() {
        access = "Root";
        runImport(() -> RootAccess.run(this, "import"));
    }
    private void shizukuImport() {
        try {
            if (!Shizuku.pingBinder()) { showMessage("请先在 Shizuku 中启动服务，再返回导入"); return; }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(REQUEST_SHIZUKU); return; }
            startShizukuImport();
        } catch (Exception e) { showMessage("Shizuku 未就绪，请检查其服务和授权"); }
    }
    private void startShizukuImport() {
        access = "Shizuku";
        privilegeReady = new CountDownLatch(privilege == null ? 1 : 0);
        try { Shizuku.bindUserService(privilegeArgs, privilegeConnection); }
        catch (Exception e) { showMessage("无法建立 Shizuku 服务"); return; }
        runImport(() -> {
            if (!privilegeReady.await(12, TimeUnit.SECONDS) || privilege == null) throw new IllegalStateException("连接 Shizuku 用户服务超时");
            if (privilege.uid() != 0) throw new IllegalStateException("Shizuku 当前是 ADB / shell 模式，不能读取运动健康私有数据库。请使用 Root 导入，或将 Shizuku 切换为 Root 模式后重试");
            return privilege.readPairings(android.os.Process.myUid() / 100000);
        });
    }
    private interface ImportAction { String run() throws Exception; }
    private void runImport(ImportAction action) {
        if (importing || service != null && service.busy()) return;
        importing = true; showMessage("正在等待 " + access + " 授权并读取配对信息…"); update();
        io.execute(() -> {
            try {
                JSONArray devices = new JSONArray(action.run());
                if (devices.length() == 0) throw new IllegalStateException("未找到可用的 Watch 4 配对记录。请先在运动健康连接手表并完成同步");
                runOnUiThread(() -> selectPairing(devices));
            } catch (Exception e) { runOnUiThread(() -> showMessage(WatchService.safe(e.getMessage()))); }
            finally { importing = false; runOnUiThread(this::update); }
        });
    }
    private void selectPairing(JSONArray devices) {
        if (dead) return;
        if (devices.length() == 1) { fillPairing(devices.optJSONObject(0)); return; }
        String[] labels = new String[devices.length()];
        for (int i = 0; i < labels.length; i++) {
            JSONObject item = devices.optJSONObject(i); labels[i] = item.optString("name") + "\n" + item.optString("mac");
        }
        new AlertDialog.Builder(this).setTitle("选择自己的 Watch 4").setItems(labels, (d, which) -> fillPairing(devices.optJSONObject(which)))
                .setNegativeButton("取消", null).show();
    }
    private void fillPairing(JSONObject item) {
        address = item.optString("mac"); authKey = item.optString("key");
        if (addressField != null) addressField.setText(address);
        if (keyField != null) keyField.setText(authKey);
        try { if (remember == null || remember.isChecked()) store.save(address, authKey); showMessage("已导入 Watch 4 配对信息，密钥保留在本机"); }
        catch (Exception e) { showMessage("已读取配对信息，但加密保存失败。本次仍可连接"); }
    }

    private boolean bluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31 && !BluetoothAccess.granted(this)) {
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLUETOOTH); return false;
        }
        return true;
    }
    private void chooseBonded() {
        if (!bluetoothPermission()) return;
        try {
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null || !adapter.isEnabled()) { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return; }
        List<BluetoothDevice> devices = new ArrayList<>();
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            String name = device.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).contains("redmi watch 4") && !name.toLowerCase(Locale.ROOT).contains("active")) devices.add(device);
        }
        if (devices.isEmpty()) { showMessage("没有已配对的 Watch 4。请先用运动健康完成配对"); return; }
        String[] labels = new String[devices.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = devices.get(i).getName() + "\n" + devices.get(i).getAddress();
        new AlertDialog.Builder(this).setTitle("已配对的 Watch 4").setItems(labels, (d, which) -> {
            address = devices.get(which).getAddress(); addressField.setText(address);
        }).setNegativeButton("取消", null).show();
        } catch (SecurityException denied) { showMessage(BluetoothAccess.rejection(this)); }
    }
    private void connect() {
        captureConnection();
        if (!bluetoothPermission() || service == null || importing) return;
        if (!BluetoothAdapter.checkBluetoothAddress(address)) { showMessage("请导入或填写有效蓝牙地址"); return; }
        byte[] key;
        try { key = Crypto.unhex(authKey); if (key.length != 16) throw new IllegalArgumentException(); }
        catch (IllegalArgumentException e) { showMessage("配对密钥应为 32 位十六进制"); return; }
        try { if (remember.isChecked()) store.save(address, authKey); }
        catch (Exception e) { showMessage("加密保存失败，未开始连接"); java.util.Arrays.fill(key, (byte) 0); return; }
        boolean shouldStop = stopOfficial.isChecked();
        importing = true; update();
        io.execute(() -> {
            try {
                if (shouldStop) {
                    if (access.equals("Shizuku") && privilege != null) privilege.stopCompanion(android.os.Process.myUid() / 100000);
                    else RootAccess.run(this, "stop");
                    Thread.sleep(1200);
                }
                runOnUiThread(() -> {
                    if (dead || service == null) { java.util.Arrays.fill(key, (byte) 0); return; }
                    try {
                        startForegroundService(new Intent(this, WatchService.class));
                        service.connect(address, key);
                    } catch (RuntimeException error) {
                        java.util.Arrays.fill(key, (byte) 0);
                        showMessage("无法启动连接：" + WatchService.safe(error.getMessage()));
                    }
                });
            } catch (Exception e) { java.util.Arrays.fill(key, (byte) 0); runOnUiThread(() -> showMessage(WatchService.safe(e.getMessage()))); }
            finally { importing = false; runOnUiThread(this::update); }
        });
    }

    private void chooseRpk() {
        if (importing || service != null && service.busy()) { showMessage("请等待当前操作完成"); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(intent, REQUEST_RPK);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == REQUEST_RPK && result == RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri uri = data.getData(); importing = true; update();
            io.execute(() -> {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    RpkFile file = RpkFile.read(input); imported = file;
                    runOnUiThread(() -> { if (!dead) { selectedTab = 0; drawTab(); showMessage("已读取 RPK，请核对后安装"); } });
                } catch (Exception e) { runOnUiThread(() -> showMessage("无法导入：" + WatchService.safe(e.getMessage()))); }
                finally { importing = false; runOnUiThread(this::update); }
            });
        }
        if (request == REQUEST_LOG_EXPORT && result == RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri destination = data.getData();
            showMessage("正在导出日志…");
            io.execute(() -> {
                try (java.io.OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                    DiagnosticLog.get(this).export(output);
                    runOnUiThread(() -> showMessage("日志已保存到所选文件，可将该文件用于问题分析"));
                } catch (Exception error) {
                    DiagnosticLog.get(this).error("导出日志失败", error);
                    runOnUiThread(() -> showMessage("日志导出失败：" + WatchService.safe(error.getMessage())));
                }
            });
        }
    }
    private void confirmInstall() {
        RpkFile file = imported;
        if (file == null || service == null || !service.connected() || !file.hasSignature) return;
        boolean update = service.apps().stream().anyMatch(app -> app.packageName.equals(file.packageName));
        new AlertDialog.Builder(this).setTitle(update ? "覆盖安装「" + file.name + "」？" : "安装「" + file.name + "」？")
                .setMessage(file.packageName + "\n版本 " + file.versionName + " / " + file.versionCode
                        + "\n大小 " + file.bytes.length + " 字节\n\n签名和固件兼容性由手表检查。请保持手机与手表靠近。")
                .setNegativeButton("取消", null).setPositiveButton(update ? "覆盖安装" : "安装", (d, w) -> service.install(file)).show();
    }
    private void exportLogs() {
        String filename = "Watch4-diagnostics-" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt";
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/plain").putExtra(Intent.EXTRA_TITLE, filename);
        try { startActivityForResult(save, REQUEST_LOG_EXPORT); }
        catch (android.content.ActivityNotFoundException missing) { showMessage("未找到系统文件保存器，请启用系统文件管理组件后重试"); }
    }
    private void openOfficial() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.mi.health");
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage("com.xiaomi.wearable");
        if (launch != null) startActivity(launch); else showMessage("未找到运动健康，请从手机应用列表打开");
    }
    private void showMessage(String value) {
        if (dead || message == null) return;
        DiagnosticLog.get(this).info("界面提示：" + value);
        message.setText(WatchService.safe(value)); message.setVisibility(View.VISIBLE);
    }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout card() {
        LinearLayout view = column(); view.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(Color.WHITE); shape.setCornerRadius(dp(22)); view.setBackground(shape); return view;
    }
    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(sp); view.setTextColor(color); view.setLineSpacing(dp(3), 1);
        return view;
    }
    private EditText field(String hint, boolean secret) {
        EditText view = new EditText(this); view.setSingleLine(true); view.setTextSize(14); view.setHint(hint); view.setTextColor(INK);
        view.setInputType(secret ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); view.setSaveEnabled(false);
        if (secret) view.setTypeface(Typeface.MONOSPACE); view.setPadding(dp(10), dp(10), dp(10), dp(10));
        view.setMinHeight(dp(50)); return view;
    }
    private Button button(String label, boolean primary, Runnable click) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false); button.setTextSize(14);
        button.setTextColor(primary ? Color.WHITE : GREEN); button.setMinHeight(dp(46));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(primary ? GREEN : 0xffe7eee5); shape.setCornerRadius(dp(14));
        button.setBackgroundTintList(null); button.setBackground(shape); button.setPadding(dp(12), dp(4), dp(12), dp(4));
        button.setOnClickListener(v -> click.run()); return button;
    }
    private void margin(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); parent.addView(view, params);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == REQUEST_BLUETOOTH) showMessage(grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED
                ? "蓝牙权限已授权，请再次点击所需操作" : "蓝牙连接权限未授权");
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putInt("tab", selectedTab); super.onSaveInstanceState(state); }
    @Override protected void onDestroy() {
        dead = true;
        if (service != null) service.listen(null);
        if (bound) unbindService(serviceConnection);
        Shizuku.removeRequestPermissionResultListener(shizukuPermission);
        if (privilege != null) try { Shizuku.unbindUserService(privilegeArgs, privilegeConnection, true); } catch (Exception ignored) { /* 服务已退出 */ }
        authKey = ""; io.shutdownNow(); super.onDestroy();
    }
}
