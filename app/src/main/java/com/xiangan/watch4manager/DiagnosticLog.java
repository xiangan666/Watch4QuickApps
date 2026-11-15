package com.xiangan.watch4manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 有界本机日志；先脱敏再保存，通过原子文件保留断开与重启前的排错记录。 */
final class DiagnosticLog {
    private static final int MAX_RECORDS = 2000, MAX_CHARS = 400000, MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static DiagnosticLog instance;
    private final ArrayDeque<String> records = new ArrayDeque<>();
    private final AtomicFile file;
    private final SharedPreferences settings;
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
    private boolean scheduled;
    private int chars;
    private long discarded;
    private volatile String storageError = "";

    static synchronized DiagnosticLog get(Context context) {
        if (instance == null) instance = new DiagnosticLog(new File(context.getFilesDir(), "diagnostics.log"),
                context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE));
        return instance;
    }

    DiagnosticLog(File path, SharedPreferences settings) {
        this.file = new AtomicFile(path); this.settings = settings;
        try (InputStream input = file.openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] part = new byte[4096]; int n;
            while ((n = input.read(part)) != -1) {
                if (bytes.size() + n > MAX_FILE_BYTES) throw new IOException("日志文件超过读取上限");
                bytes.write(part, 0, n);
            }
            for (String line : bytes.toString(StandardCharsets.UTF_8.name()).split("\n")) {
                if (!line.isEmpty()) keep(redact(line));
            }
        } catch (java.io.FileNotFoundException empty) {
            // 首次启动还没有日志。
        } catch (IOException error) { storageError = "原有日志读取失败：" + redact(error.getMessage()); }
    }

    boolean detailed() { return settings.getBoolean("detailed", false); }
    void setDetailed(boolean enabled) {
        settings.edit().putBoolean("detailed", enabled).apply();
        info(enabled ? "已开启详细日志" : "已关闭详细日志");
    }
    void info(String message) { record("INFO", message); }
    void warn(String message) { record("WARN", message); }
    void detail(String message) { if (detailed()) record("DEBUG", message); }
    void error(String operation, Throwable error) {
        record("ERROR", operation + "：" + error.getClass().getSimpleName() + " / " + error.getMessage());
        if (!detailed()) return;
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 4; depth++, cause = cause.getCause()) {
            detail("异常链 " + depth + "：" + cause.getClass().getName() + " / " + cause.getMessage());
            StackTraceElement[] stack = cause.getStackTrace();
            for (int i = 0; i < Math.min(8, stack.length); i++) detail("  at " + stack[i]);
        }
    }
    private synchronized void record(String level, String message) {
        String safe = redact(message);
        if (safe.length() > 2000) safe = safe.substring(0, 2000) + "…";
        keep(ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + " [" + level + "] " + safe);
        if (!scheduled) {
            scheduled = true;
            writer.schedule(() -> {
                try { flush(); } catch (IOException error) { storageError = "日志保存失败：" + redact(error.getMessage()); }
            }, 300, TimeUnit.MILLISECONDS);
        }
    }
    private void keep(String line) {
        records.addLast(line); chars += line.length() + 1;
        while (records.size() > MAX_RECORDS || chars > MAX_CHARS) {
            chars -= records.removeFirst().length() + 1; discarded++;
        }
    }
    synchronized String text() {
        String notice = storageError.isEmpty() ? "" : "[WARN] " + storageError + "\n";
        if (discarded > 0) notice += "[INFO] 已滚动移除 " + discarded + " 条较早记录\n";
        return notice + String.join("\n", records);
    }
    synchronized void clear() { records.clear(); chars = 0; discarded = 0; storageError = ""; info("已清空旧日志"); }
    synchronized void flush() throws IOException {
        scheduled = false;
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(String.join("\n", records).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output); storageError = "";
        } catch (IOException error) { if (output != null) file.failWrite(output); throw error; }
    }
    void export(OutputStream output) throws IOException {
        if (output == null) throw new IOException("无法打开日志导出文件");
        String header = "Watch 4 快应用管理 v" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                + "导出时间：" + ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\n"
                + "Android " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + "\n"
                + "手机：" + redact(Build.MANUFACTURER + " " + Build.MODEL) + "\n"
                + "详细日志：" + (detailed() ? "开启" : "关闭") + "\n"
                + "敏感数据：配对密钥、蓝牙地址和鉴权载荷不记录\n\n";
        output.write((header + text() + "\n").getBytes(StandardCharsets.UTF_8)); output.flush();
    }
    static String redact(String value) {
        if (value == null) return "未返回详细原因";
        return value.replaceAll("(?i)(?<![0-9a-f])[0-9a-f]{32,}(?![0-9a-f])", "[密钥或摘要已隐藏]")
                .replaceAll("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])", "[蓝牙地址已隐藏]")
                .replace('\r', ' ').replace('\n', ' ');
    }
}
