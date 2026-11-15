package com.xiangan.watch4manager;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import com.xiangan.watch4.protocol.QuickApp;
import com.xiangan.watch4.protocol.DeviceRejectedException;
import com.xiangan.watch4.protocol.WatchSession;
import com.xiangan.watch4.protocol.Crypto;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 前台服务维持一次管理会话，退出界面不打断已经开始的安装。 */
public final class WatchService extends Service {
    public interface Listener {
        void changed();
        default void operationRejected(String message) {}
    }
    public final class LocalBinder extends Binder { public WatchService service() { return WatchService.this; } }
    private final LocalBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor();
    private volatile WatchSession session;
    private volatile BluetoothSocket socket;
    private volatile boolean busy;
    private volatile int progress;
    private volatile String status = "未连接", operation = "";
    private volatile List<QuickApp> apps = Collections.emptyList();
    private DiagnosticLog diagnostics;
    private Listener listener;

    @Override public void onCreate() {
        super.onCreate();
        diagnostics = DiagnosticLog.get(this);
        diagnostics.info("管理服务启动 v" + BuildConfig.VERSION_NAME);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("watch4", "手表连接", NotificationManager.IMPORTANCE_LOW));
        main.postDelayed(health, 2000);
    }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent != null && "disconnect".equals(intent.getAction())) { disconnect(); return START_NOT_STICKY; }
        showForeground(); return START_NOT_STICKY;
    }
    public void listen(Listener next) { listener = next; if (next != null) next.changed(); }
    public boolean connected() { WatchSession s = session; return s != null && s.isAuthenticated(); }
    public boolean busy() { return busy; }
    public String status() { return status; }
    public String operation() { return operation; }
    public int progress() { return progress; }
    public List<QuickApp> apps() { return apps; }
    public String logs() { return diagnostics.text(); }
    public void clearLogs() { diagnostics.clear(); changed(); }
    public boolean detailedLogs() { return diagnostics.detailed(); }
    public void setDetailedLogs(boolean enabled) { diagnostics.setDetailed(enabled); changed(); }

    public void connect(String address, byte[] key) {
        if (busy || connected()) { Arrays.fill(key, (byte) 0); return; }
        busy = true; progress = 0; status = "正在连接"; operation = "连接与鉴权"; changed();
        worker.execute(() -> {
            try {
                BluetoothSocket pending = openTransport(address);
                WatchSession active = new WatchSession(pending.getInputStream(), pending.getOutputStream(), pending, new WatchSession.Events() {
                    @Override public void log(String message) { addLog(message); }
                    @Override public void detail(String message) { diagnostics.detail(message); changed(); }
                    @Override public void progress(int percent) { progress = percent; changed(); }
                });
                session = active;
                status = "正在鉴权"; changed();
                active.authenticate(key, android.os.Build.VERSION.SDK_INT, android.os.Build.MODEL);
                apps = Collections.unmodifiableList(active.listApps());
                status = "已连接 · Redmi Watch 4";
                addLog("已读取 " + apps.size() + " 个快应用");
            } catch (Exception error) { failed(error, true); }
            finally { Arrays.fill(key, (byte) 0); busy = false; operation = ""; changed(); }
        });
    }

    private BluetoothSocket openTransport(String address) throws java.io.IOException {
        String stage = "检查蓝牙";
        try {
            if (!BluetoothAccess.granted(this)) throw new SecurityException("未授予 " + BluetoothAccess.requiredPermission());
            diagnostics.detail("蓝牙权限：附近设备=" + BluetoothAccess.granted(this) + "；旧版兼容="
                    + (checkSelfPermission(android.Manifest.permission.BLUETOOTH) == android.content.pm.PackageManager.PERMISSION_GRANTED));
            BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
            if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("请先打开手机蓝牙");
            stage = "检查手表配对";
            BluetoothDevice device = adapter.getRemoteDevice(address);
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) throw new IllegalStateException("请先在小米运动健康中完成与 Watch 4 的配对");
            stage = "创建蓝牙连接";
            BluetoothSocket pending = device.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"));
            socket = pending;
            java.util.concurrent.ScheduledFuture<?> timeout = deadlines.schedule(() -> {
                try { pending.close(); } catch (java.io.IOException ignored) { /* 关闭解除阻塞 */ }
            }, 18, TimeUnit.SECONDS);
            stage = "建立蓝牙连接";
            addLog("正在建立手表蓝牙连接");
            try { pending.connect(); } finally { timeout.cancel(false); }
            addLog("手表蓝牙连接已建立");
            return pending;
        } catch (SecurityException denied) {
            addLog(stage + "被系统拒绝（SecurityException）：" + safe(denied.getMessage()));
            throw new IllegalStateException(BluetoothAccess.rejection(this), denied);
        }
    }

    public void refresh() { submit("读取应用列表", () -> {
        apps = Collections.unmodifiableList(requireSession().listApps()); addLog("应用列表已更新：" + apps.size() + " 个");
    }); }
    public void launch(QuickApp app) { submit("启动应用", () -> requireSession().launch(app)); }
    public void uninstall(QuickApp app) { submit("卸载应用", () -> {
        boolean removed = requireSession().uninstall(app);
        apps = Collections.unmodifiableList(requireSession().listApps());
        addLog(removed ? "已核对卸载完成：" + app.name : "卸载尚未确认，目标包仍在设备列表中");
        status = removed ? "卸载已确认" : "卸载待确认";
    }); }
    public void install(RpkFile file) { submit("安装 " + file.name, () -> {
        WatchSession.InstallResult result = requireSession().install(file.bytes, file.packageName, file.versionCode);
        apps = Collections.unmodifiableList(requireSession().listApps());
        status = result == WatchSession.InstallResult.CONFIRMED ? "安装已确认" : "安装待确认，请查看手表";
        addLog(status + "：" + file.packageName);
    }); }

    private interface Work { void run() throws Exception; }
    private void submit(String name, Work work) {
        if (busy) return;
        busy = true; operation = name; progress = 0; changed();
        worker.execute(() -> {
            long started = android.os.SystemClock.elapsedRealtime();
            addLog("开始操作：" + name);
            try { work.run(); }
            catch (DeviceRejectedException rejected) {
                status = connected() ? "已连接 · 安装未开始" : "安装未开始 · 连接已中断";
                diagnostics.warn(rejected.getMessage());
                addLog("设备拒绝安装，保留当前蓝牙连接，可继续刷新列表或重试");
                main.post(() -> { if (listener != null) listener.operationRejected(safe(rejected.getMessage())); });
            }
            catch (Exception error) { failed(error, true); }
            finally {
                diagnostics.detail("操作结束：" + name + "；耗时=" + (android.os.SystemClock.elapsedRealtime() - started)
                        + "ms；连接=" + connected());
                busy = false; operation = ""; changed();
            }
        });
    }
    private WatchSession requireSession() {
        WatchSession s = session;
        if (s == null || !s.isAuthenticated()) throw new IllegalStateException("请先连接手表"); return s;
    }
    private void failed(Exception error, boolean close) {
        String message = safe(error.getMessage());
        status = "操作失败：" + message; addLog(status);
        diagnostics.error(operation.isEmpty() ? "连接操作" : operation, error);
        if (close) closeTransport();
    }
    public void disconnect() {
        closeTransport(); status = "已断开"; apps = Collections.emptyList();
        addLog("连接已关闭；可重新打开小米运动健康恢复同步");
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); changed();
    }
    private void closeTransport() {
        WatchSession old = session; session = null;
        if (old != null) try { old.close(); } catch (java.io.IOException ignored) { /* 已关闭 */ }
        BluetoothSocket oldSocket = socket; socket = null;
        if (oldSocket != null) try { oldSocket.close(); } catch (java.io.IOException ignored) { /* 已关闭 */ }
    }
    private void addLog(String message) {
        diagnostics.info(message);
        changed();
    }
    public static String safe(String message) {
        if (message == null || message.isEmpty()) return "未返回详细原因";
        String redacted = message.replaceAll("(?i)(?<![0-9a-f])[0-9a-f]{32,}(?![0-9a-f])", "[已隐藏]");
        return redacted.substring(0, Math.min(redacted.length(), 500));
    }
    private void changed() { main.post(() -> { if (listener != null) listener.changed(); }); }
    private final Runnable health = new Runnable() {
        @Override public void run() {
            WatchSession active = session;
            if (!busy && active != null && !active.isAuthenticated()) {
                closeTransport(); status = "蓝牙连接已中断"; addLog("检测到蓝牙传输已中断"); changed();
            }
            main.postDelayed(this, 2000);
        }
    };
    private void showForeground() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent disconnect = new Intent(this, WatchService.class).setAction("disconnect");
        PendingIntent close = PendingIntent.getService(this, 1, disconnect, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, "watch4").setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Watch 4 快应用管理").setContentText("管理会话正在运行，完成后可断开")
                .setContentIntent(pending).addAction(new Notification.Action.Builder(null, "断开", close).build())
                .setOngoing(true).build();
        startForeground(40, notification);
    }
    @Override public void onDestroy() {
        main.removeCallbacks(health); listener = null; closeTransport();
        worker.shutdownNow(); deadlines.shutdownNow(); super.onDestroy();
    }
}
