package com.xiangan.watch4manager.privilege;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.Locale;

/** 仅返回 Watch 4 配对信息，不复制数据库或读取健康记录。 */
public final class PairingReader {
    private PairingReader() {}
    public static final String[] COMPANIONS = {"com.mi.health", "com.xiaomi.wearable"};

    public static String read(int userId) throws Exception {
        if (userId < 0 || userId > 9999) throw new IllegalArgumentException("Android 用户无效");
        JSONArray found = new JSONArray();
        boolean readable = false;
        for (String companion : COMPANIONS) {
            File file = database(userId, companion);
            if (!file.canRead()) continue;
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
                 Cursor cursor = db.rawQuery("SELECT * FROM device", null)) {
                readable = true;
                int rows = 0;
                while (cursor.moveToNext()) {
                    if (++rows > 512) throw new IllegalStateException("配对设备记录过多");
                    JSONObject row = new JSONObject();
                    for (int c = 0; c < cursor.getColumnCount(); c++) {
                        if (cursor.getType(c) != Cursor.FIELD_TYPE_BLOB && !cursor.isNull(c))
                            row.put(cursor.getColumnName(c), cursor.getString(c));
                    }
                    JSONObject detail = parseObject(row.optString("detail", "{}"));
                    String model = first(row, "model", "deviceModel");
                    String name = first(row, "name", "device_name", "deviceName", "display_name");
                    if (name.isEmpty()) name = first(detail, "name", "device_name");
                    // Watch 4 的内部型号可能是 miwear.watch.n65；始终结合明确的设备名判断。
                    String identity = (name + " " + model).toLowerCase(Locale.ROOT).replace("_", " ");
                    if (!(identity.contains("redmi watch 4") || identity.contains("redmi watch4")
                            || identity.contains("红米手表4") || model.equals("miwear.watch.n65")
                            || model.equals("miwear.watch.n65gl"))) continue;
                    if (identity.contains("active")) continue;
                    String key = first(detail, "encrypt_key", "encryptKey", "auth_key");
                    if (key.isEmpty()) key = first(row, "encrypt_key", "encryptKey");
                    if (key.isEmpty()) key = detail.optString("token", "");
                    if (!key.matches("(?i)[0-9a-f]{32}")) continue;
                    String mac = first(detail, "mac", "address", "bt_mac");
                    if (mac.isEmpty()) mac = first(row, "mac", "address", "device_mac");
                    if (!mac.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) continue;
                    JSONObject item = new JSONObject().put("name", name.isEmpty() ? "Redmi Watch 4" : name)
                            .put("model", model).put("mac", mac.toUpperCase(Locale.ROOT))
                            .put("key", key.toLowerCase(Locale.ROOT)).put("source", companion);
                    found.put(item);
                }
            } catch (android.database.sqlite.SQLiteException error) {
                throw new IllegalStateException("配对数据库结构不匹配或正在占用；请同步完成后重试", error);
            }
        }
        if (!readable) throw new IllegalStateException("无权读取配对数据库（Android 用户 " + userId + "，进程 UID " + android.os.Process.myUid()
                + "）。需要 Root 或 Root 模式 Shizuku；普通 ADB 模式 Shizuku 无法读取应用私有数据");
        return found.toString();
    }

    private static File database(int userId, String companion) {
        return new File(android.os.Environment.getDataDirectory(), "user/" + userId + "/" + companion + "/databases/device_db");
    }
    public static String diagnose(int userId) throws Exception {
        JSONObject result = new JSONObject().put("uid", android.os.Process.myUid()).put("androidUser", userId);
        JSONArray databases = new JSONArray();
        for (String pkg : COMPANIONS) {
            File file = database(userId, pkg);
            databases.put(new JSONObject().put("package", pkg).put("exists", file.exists()).put("readable", file.canRead()));
        }
        return result.put("databases", databases).toString();
    }

    public static boolean stop(int userId) throws Exception {
        if (userId < 0 || userId > 9999) throw new IllegalArgumentException("Android 用户无效");
        for (String pkg : COMPANIONS) {
            Process process = new ProcessBuilder("/system/bin/am", "force-stop", "--user", Integer.toString(userId), pkg)
                    .redirectErrorStream(true).start();
            try {
                if (!process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("停止运动健康超时");
                if (process.exitValue() != 0) throw new IllegalStateException("没有停止运动健康的权限");
            } finally { process.destroy(); }
        }
        return true;
    }

    private static JSONObject parseObject(String value) {
        try { return new JSONObject(value); } catch (org.json.JSONException ignored) { return new JSONObject(); }
    }
    private static String first(JSONObject object, String... names) {
        for (String name : names) {
            String value = object.optString(name, "");
            if (!value.isEmpty() && !value.equals("null")) return value;
        }
        return "";
    }
}
