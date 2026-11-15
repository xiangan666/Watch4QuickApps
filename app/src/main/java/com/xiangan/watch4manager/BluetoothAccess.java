package com.xiangan.watch4manager;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** 运行时授权与厂商蓝牙拒绝须分开报告；Root 不能代替附近设备授权。 */
final class BluetoothAccess {
    private BluetoothAccess() {}

    static String requiredPermission() {
        return Build.VERSION.SDK_INT >= 31 ? Manifest.permission.BLUETOOTH_CONNECT : Manifest.permission.BLUETOOTH;
    }

    static boolean granted(Context context) {
        return context.checkSelfPermission(requiredPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    static String rejection(Context context) {
        if (!granted(context)) return "蓝牙连接权限未授权，请在系统设置中允许附近设备权限";
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED)
            return "系统要求旧版蓝牙兼容权限，请覆盖安装最新版管理器";
        return "蓝牙权限已授予，但系统拒绝连接；请查看操作记录中的具体原因";
    }
}
