package com.xiangan.watch4.protocol;

import java.io.IOException;

/** 设备在传输开始前明确拒绝操作；该应答不代表蓝牙会话失效。 */
public final class DeviceRejectedException extends IOException {
    public final long statusCode;

    DeviceRejectedException(long statusCode) {
        super(statusCode == 3
                ? "手表报告存储空间不足（状态码 3），安装尚未开始。请在小米运动健康中清理不需要的表盘或应用后重试"
                : "手表拒绝安装（状态码 " + statusCode + "），安装尚未开始");
        this.statusCode = statusCode;
    }
}
