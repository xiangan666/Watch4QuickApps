package com.xiangan.watch4.protocol;

public final class QuickApp {
    public final String packageName, name;
    public final byte[] fingerprint;
    // 上游字段未明确承诺为 versionCode；不把它显示成已验证的应用版本。
    public final long reported3, reported4;
    public QuickApp(String packageName, String name, byte[] fingerprint, long reported3, long reported4) {
        this.packageName = packageName;
        this.name = name.isEmpty() ? packageName : name;
        this.fingerprint = fingerprint.clone(); this.reported3 = reported3; this.reported4 = reported4;
    }
    public static boolean validPackage(String value) {
        return value != null && value.length() <= 200 && value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }
}
