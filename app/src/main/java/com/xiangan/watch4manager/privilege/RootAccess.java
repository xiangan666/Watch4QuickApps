package com.xiangan.watch4manager.privilege;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class RootAccess {
    private RootAccess() {}
    public static String run(Context context, String operation) throws Exception {
        if (!operation.equals("import") && !operation.equals("stop") && !operation.equals("diagnose")) throw new IllegalArgumentException("固定操作无效");
        String apk = context.getApplicationInfo().sourceDir;
        int userId = context.getApplicationInfo().uid / 100000;
        String quotedApk = "'" + apk.replace("'", "'\"'\"'") + "'";
        String command = "CLASSPATH=" + quotedApk + " /system/bin/app_process /system/bin "
                + RootEntry.class.getName() + " " + operation + " " + userId;
        Process process;
        // Android 的应用数据隔离仍可在 uid=0 时遮住其它应用目录。
        // APatch / Magisk / KernelSU 的 mount-master 让此固定读取进入全局挂载命名空间。
        try { process = new ProcessBuilder("su", "--mount-master", "-c", command).start(); }
        catch (java.io.IOException error) { throw new IllegalStateException("未找到可用 Root，请使用 Root 模式 Shizuku 或手动填写密钥"); }
        FutureTask<String> output = new FutureTask<>(() -> {
            try (java.io.InputStream in = process.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096]; int n;
                while ((n = in.read(chunk)) != -1) {
                    if (out.size() + n > 512 * 1024) throw new java.io.IOException("配对信息过大");
                    out.write(chunk, 0, n);
                }
                return out.toString(StandardCharsets.UTF_8.name()).trim();
            }
        });
        Thread reader = new Thread(output, "root-private-pipe"); reader.setDaemon(true); reader.start();
        // 厂商 app_process 会把无关系统警告写到 stderr；它不属于 JSON 协议。
        // 独立持续排空，避免管道塞满，敏感结果只从 stdout 解析。
        Thread errors = new Thread(() -> {
            try (java.io.InputStream in = process.getErrorStream()) {
                byte[] ignored = new byte[4096]; while (in.read(ignored) != -1) { /* 不写日志 */ }
            } catch (java.io.IOException ignored) { /* 进程关闭 */ }
        }, "root-system-errors");
        errors.setDaemon(true); errors.start();
        try {
            if (!process.waitFor(50, TimeUnit.SECONDS)) throw new IllegalStateException("等待 Root 授权超时，请在 Root 管理器中允许本应用");
            String value = output.get(3, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                String reason = "Root 授权被拒绝或读取失败";
                try { reason = new JSONObject(value).optString("error", reason); } catch (org.json.JSONException ignored) { /* 不输出进程原始内容 */ }
                throw new IllegalStateException(reason);
            }
            return value;
        } finally { process.destroy(); output.cancel(true); }
    }
}
