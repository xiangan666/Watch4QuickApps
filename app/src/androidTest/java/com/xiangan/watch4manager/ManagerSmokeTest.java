package com.xiangan.watch4manager;

import android.app.Instrumentation;
import android.os.Bundle;
import com.xiangan.watch4manager.privilege.PairingReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 使用真实 Android JSON、ZIP 与 Keystore 实现验证边界。 */
public final class ManagerSmokeTest extends Instrumentation {
    private Bundle args;
    @Override public void onCreate(Bundle arguments) { args = arguments; super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int passed = 0;
        try {
            byte[] good = rpk("manifest.json", "{\"package\":\"com.example.smoke\",\"versionCode\":1,\"name\":\"测试\"}", true);
            RpkFile file = RpkFile.read(new ByteArrayInputStream(good));
            check(file.hasSignature && file.versionCode == 1 && file.name.equals("测试"), "RPK metadata"); passed++;
            check(!RpkFile.read(new ByteArrayInputStream(rpk("manifest.json", "{\"package\":\"com.example.smoke\",\"versionCode\":1}", false))).hasSignature, "unsigned"); passed++;
            reject(() -> RpkFile.read(new ByteArrayInputStream(rpk("../manifest.json", "{}", true)))); passed++;
            reject(() -> RpkFile.read(new ByteArrayInputStream(rpk("manifest.json", "{\"package\":\"bad/path\",\"versionCode\":1}", true)))); passed++;
            reject(() -> RpkFile.read(new ByteArrayInputStream(new byte[]{1, 2, 3}))); passed++;
            SecretStore store = new SecretStore(getTargetContext(), "smoke-connection", "watch4-smoke-test-v1");
            store.clear(); store.save("AA:BB:CC:DD:EE:FF", "0123456789abcdef0123456789abcdef");
            check(store.key().equals("0123456789abcdef0123456789abcdef"), "keystore round trip"); passed++;
            String stored = getTargetContext().getSharedPreferences("smoke-connection", 0).getString("sealed", "");
            check(!stored.contains("0123456789abcdef"), "encrypted persistence"); passed++;
            store.clear(); check(store.key().isEmpty() && store.address().isEmpty(), "clear credentials"); passed++;
            reject(() -> PairingReader.read(-1)); passed++;
            check(!WatchService.safe("key=0123456789abcdef0123456789abcdef").contains("0123456789abcdef"), "log redaction"); passed++;
            check(getTargetContext().checkSelfPermission(android.Manifest.permission.BLUETOOTH)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED, "legacy Bluetooth compatibility permission"); passed++;
            android.content.Context denied = new android.content.ContextWrapper(getTargetContext()) {
                @Override public int checkSelfPermission(String permission) { return android.content.pm.PackageManager.PERMISSION_DENIED; }
            };
            check(!BluetoothAccess.granted(denied) && BluetoothAccess.rejection(denied).contains("未授权"), "missing permission diagnostic"); passed++;
            android.content.Context allowed = new android.content.ContextWrapper(getTargetContext()) {
                @Override public int checkSelfPermission(String permission) { return android.content.pm.PackageManager.PERMISSION_GRANTED; }
            };
            check(BluetoothAccess.granted(allowed) && BluetoothAccess.rejection(allowed).contains("权限已授予")
                    && !BluetoothAccess.rejection(allowed).contains("撤销"), "vendor rejection is not permission revocation"); passed++;
            android.content.SharedPreferences logSettings = getTargetContext().getSharedPreferences("smoke-diagnostics", 0);
            logSettings.edit().clear().commit();
            java.io.File logFile = new java.io.File(getTargetContext().getCacheDir(), "smoke-diagnostics.log");
            DiagnosticLog diagnosticLog = new DiagnosticLog(logFile, logSettings); diagnosticLog.clear();
            diagnosticLog.detail("hidden debug marker");
            check(!diagnosticLog.text().contains("hidden debug marker"), "detail off"); passed++;
            diagnosticLog.setDetailed(true); diagnosticLog.detail("TX 指令：20/1");
            check(diagnosticLog.text().contains("TX 指令：20/1"), "detail on"); passed++;
            diagnosticLog.warn("key=0123456789abcdef0123456789abcdef mac=AA:BB:CC:DD:EE:FF");
            diagnosticLog.flush();
            DiagnosticLog reopened = new DiagnosticLog(logFile, logSettings);
            check(reopened.text().contains("TX 指令：20/1") && reopened.detailed(), "persistent logs and setting"); passed++;
            ByteArrayOutputStream export = new ByteArrayOutputStream(); reopened.export(export);
            String exported = export.toString(java.nio.charset.StandardCharsets.UTF_8.name());
            check(exported.contains("v" + BuildConfig.VERSION_NAME) && exported.contains("20/1")
                    && !exported.contains("0123456789abcdef") && !exported.contains("AA:BB:CC:DD:EE:FF"), "UTF-8 export is redacted"); passed++;
            diagnosticLog.error("安装", new java.io.IOException("read failed", new SecurityException("vendor rejection")));
            check(diagnosticLog.text().contains("SecurityException") && diagnosticLog.text().contains("read failed"), "exception chain"); passed++;
            for (int i = 0; i < 2100; i++) diagnosticLog.info("bounded record " + i);
            check(diagnosticLog.text().contains("bounded record 2099") && !diagnosticLog.text().contains("bounded record 0\n"), "bounded history"); passed++;
            diagnosticLog.clear(); diagnosticLog.flush();
            String extra = "";
            if (args != null && args.getString("bluetooth", "false").equals("true")) {
                extra += bluetoothProbe();
            }
            if (args != null && args.getString("service", "false").equals("true")) {
                extra += serviceProbe();
            }
            if (args != null && args.getString("root", "false").equals("true")) {
                android.os.Bundle diagnostic = new Bundle();
                diagnostic.putString("stream", "Root access diagnostics: " + com.xiangan.watch4manager.privilege.RootAccess.run(getTargetContext(), "diagnose") + "\n");
                sendStatus(1, diagnostic);
                String records = com.xiangan.watch4manager.privilege.RootAccess.run(getTargetContext(), "import");
                org.json.JSONArray parsed = new org.json.JSONArray(records);
                check(parsed.length() > 0, "real root import");
                extra = "Root import: valid JSON, Watch 4 records=" + parsed.length() + " (credentials not printed)\n";
            }
            result.putString("stream", "Android smoke checks: " + passed + "/19 passed\n" + extra);
            finish(android.app.Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "Failed after " + passed + " checks: " + error.getClass().getSimpleName() + " / " + WatchService.safe(error.getMessage()));
            finish(android.app.Activity.RESULT_CANCELED, result);
        }
    }
    private interface Attempt { void run() throws Exception; }
    private String serviceProbe() throws Exception {
        android.content.Context context = getTargetContext();
        DiagnosticLog.get(context).setDetailed(true);
        context.startActivity(new android.content.Intent(context, MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        java.util.concurrent.CountDownLatch bound = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<WatchService> reference = new java.util.concurrent.atomic.AtomicReference<>();
        android.content.ServiceConnection connection = new android.content.ServiceConnection() {
            @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
                reference.set(((WatchService.LocalBinder) binder).service()); bound.countDown();
            }
            @Override public void onServiceDisconnected(android.content.ComponentName name) { reference.set(null); }
        };
        check(context.bindService(new android.content.Intent(context, WatchService.class), connection, android.content.Context.BIND_AUTO_CREATE), "bind watch service");
        try {
            check(bound.await(5, java.util.concurrent.TimeUnit.SECONDS), "watch service ready");
            WatchService service = reference.get();
            SecretStore credentials = new SecretStore(context);
            byte[] key = com.xiangan.watch4.protocol.Crypto.unhex(credentials.key());
            runOnMainSync(() -> {
                context.startForegroundService(new android.content.Intent(context, WatchService.class));
                service.connect(credentials.address(), key);
            });
            long end = android.os.SystemClock.elapsedRealtime() + 65000;
            while (service.busy() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(100);
            String status = service.status();
            String trace = "Connection result: " + status + "\n" + service.logs() + "\n";
            Bundle update = new Bundle(); update.putString("stream", trace); sendStatus(1, update);
            check(!service.busy(), "service connection deadline");
            check(service.connected(), "service connection: " + status);
            if (args.getString("install", "false").equals("true")) {
                RpkFile rpk;
                try (java.io.InputStream input = new java.io.FileInputStream(new java.io.File(context.getCacheDir(), "watch4-smoke-install.rpk"))) {
                    rpk = RpkFile.read(input);
                }
                check(rpk.packageName.equals("com.xiangan.watch.xiangqi") && rpk.hasSignature, "known chess test package");
                runOnMainSync(() -> service.install(rpk));
                long installEnd = android.os.SystemClock.elapsedRealtime() + 90000;
                while (service.busy() && android.os.SystemClock.elapsedRealtime() < installEnd) Thread.sleep(100);
                check(!service.busy(), "install deadline");
                check(service.connected(), "install preserves connection: " + service.status());
                Bundle installed = new Bundle(); installed.putString("stream", "Install result: " + service.status() + "\n" + service.logs() + "\n");
                sendStatus(1, installed);
                runOnMainSync(service::refresh);
                long listEnd = android.os.SystemClock.elapsedRealtime() + 15000;
                while (service.busy() && android.os.SystemClock.elapsedRealtime() < listEnd) Thread.sleep(100);
                check(!service.busy() && service.connected(), "list works after install result");
                return "Watch service probe: install result handled, connection retained, list refreshed, apps=" + service.apps().size() + "\n";
            }
            return "Watch service probe: authenticated, apps=" + service.apps().size() + "\n";
        } finally {
            context.unbindService(connection);
        }
    }
    private String bluetoothProbe() throws Exception {
        android.content.Context context = getTargetContext();
        String[] permissions = {"android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN"};
        Bundle diagnostic = new Bundle();
        StringBuilder grants = new StringBuilder("Bluetooth permissions:");
        for (String permission : permissions) grants.append(" ").append(permission.substring(permission.lastIndexOf('.') + 1))
                .append("=").append(context.checkSelfPermission(permission));
        diagnostic.putString("stream", grants + "\n"); sendStatus(1, diagnostic);
        String stage = "adapter";
        try {
            android.bluetooth.BluetoothAdapter adapter = context.getSystemService(android.bluetooth.BluetoothManager.class).getAdapter();
            check(adapter != null, "Bluetooth adapter");
            stage = "enabled"; check(adapter.isEnabled(), "Bluetooth enabled");
            String address = new SecretStore(context).address(); check(!address.isEmpty(), "saved pairing address");
            stage = "device"; android.bluetooth.BluetoothDevice device = adapter.getRemoteDevice(address);
            stage = "bond"; check(device.getBondState() == android.bluetooth.BluetoothDevice.BOND_BONDED, "bonded watch");
            stage = "socket";
            try (android.bluetooth.BluetoothSocket socket = device.createRfcommSocketToServiceRecord(
                    java.util.UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"))) {
                if (args.getString("connect", "false").equals("true")) {
                    java.util.concurrent.ScheduledExecutorService timer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
                    java.util.concurrent.ScheduledFuture<?> timeout = timer.schedule(() -> {
                        try { socket.close(); } catch (java.io.IOException ignored) { }
                    }, 18, java.util.concurrent.TimeUnit.SECONDS);
                    stage = "connect";
                    try { socket.connect(); }
                    finally { timeout.cancel(false); timer.shutdownNow(); }
                    return "Bluetooth API probe: RFCOMM connected\n";
                }
                return "Bluetooth API probe: enabled, bonded, RFCOMM socket created\n";
            }
        } catch (Exception error) {
            throw new IllegalStateException("Bluetooth stage=" + stage + ": " + error.getClass().getSimpleName() + " / " + error.getMessage(), error);
        }
    }
    private void reject(Attempt attempt) throws Exception {
        try { attempt.run(); } catch (Exception expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    private void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    private byte[] rpk(String path, String manifest, boolean signature) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(path)); zip.write(manifest.getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
            if (signature) { zip.putNextEntry(new ZipEntry("META-INF/CERT")); zip.write(new byte[]{1}); zip.closeEntry(); }
        }
        return output.toByteArray();
    }
}
