package com.xiangan.watch4.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/** 旧版 SPP V1 帧；先协商版本，绝不把 Watch 5 的 V2 帧发给 Watch 4。 */
public final class SppFrame {
    public final int channel, flags, opcode, serial, dataType;
    public final byte[] payload;
    public SppFrame(int channel, int flags, int opcode, int serial, int dataType, byte[] payload) {
        this.channel = channel; this.flags = flags; this.opcode = opcode;
        this.serial = serial; this.dataType = dataType; this.payload = payload.clone();
    }
    public byte[] encode() {
        if (payload.length > 65532 || channel < 0 || channel > 15 || dataType < 0 || dataType > 2)
            throw new IllegalArgumentException("SPP 帧参数无效");
        byte[] out = new byte[payload.length + 11];
        out[0] = (byte) 0xba; out[1] = (byte) 0xdc; out[2] = (byte) 0xfe;
        out[3] = (byte) channel; out[4] = (byte) flags;
        int length = payload.length + 3; out[5] = (byte) length; out[6] = (byte) (length >>> 8);
        out[7] = (byte) opcode; out[8] = (byte) serial; out[9] = (byte) dataType;
        System.arraycopy(payload, 0, out, 10, payload.length); out[out.length - 1] = (byte) 0xef;
        return out;
    }
    public static SppFrame read(InputStream input) throws IOException {
        int matched = 0;
        int[] magic = {0xba, 0xdc, 0xfe};
        for (int scanned = 0; matched < 3; scanned++) {
            if (scanned > 4096) throw new IOException("未找到 Watch 4 SPP V1 帧头");
            int b = input.read(); if (b < 0) throw new EOFException("蓝牙连接已关闭");
            matched = b == magic[matched] ? matched + 1 : b == magic[0] ? 1 : 0;
        }
        byte[] header = exact(input, 7);
        int length = (header[2] & 255) | ((header[3] & 255) << 8);
        if (length < 3 || (header[0] & 0xf0) != 0 || (header[1] & 15) != 0 || (header[6] & 255) > 2)
            throw new IOException("SPP 帧头无效");
        byte[] body = exact(input, length - 3);
        if (input.read() != 0xef) throw new IOException("SPP 帧尾无效");
        return new SppFrame(header[0] & 15, header[1] & 255, header[4] & 255, header[5] & 255, header[6] & 255, body);
    }
    private static byte[] exact(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length]; int read = 0;
        while (read < length) {
            int n = input.read(bytes, read, length - read);
            if (n < 0) throw new EOFException("SPP 帧被截断");
            if (n == 0) continue;
            read += n;
        }
        return bytes;
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof SppFrame)) return false;
        SppFrame f = (SppFrame) other;
        return channel == f.channel && flags == f.flags && opcode == f.opcode && serial == f.serial
                && dataType == f.dataType && Arrays.equals(payload, f.payload);
    }
    @Override public int hashCode() { return serial * 31 + Arrays.hashCode(payload); }
}
