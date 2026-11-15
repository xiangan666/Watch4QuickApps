package com.xiangan.watch4.protocol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

public final class AppProtocol {
    private AppProtocol() {}
    public static byte[] command(int module, int sub, int bodyField, byte[] body) {
        Proto.Writer w = new Proto.Writer().number(1, module).number(2, sub);
        if (body != null) w.bytes(bodyField, body);
        return w.build();
    }
    public static byte[] list() { return command(20, 0, 0, null); }
    public static byte[] prepareInstall(String pkg, int version, int size) {
        if (!QuickApp.validPackage(pkg) || version < 1 || size < 1) throw new IllegalArgumentException("RPK 元信息无效");
        byte[] info = new Proto.Writer().text(1, pkg).number(2, version).number(3, size).build();
        return command(20, 1, 22, new Proto.Writer().bytes(2, info).build());
    }
    private static byte[] ref(QuickApp app) {
        if (!QuickApp.validPackage(app.packageName) || app.fingerprint.length == 0)
            throw new IllegalArgumentException("需先读取设备上的应用和指纹");
        return new Proto.Writer().text(1, app.packageName).bytes(2, app.fingerprint).build();
    }
    public static byte[] delete(QuickApp app) {
        return command(20, 3, 22, new Proto.Writer().bytes(5, ref(app)).build());
    }
    public static byte[] launch(QuickApp app) {
        byte[] launch = new Proto.Writer().bytes(1, ref(app)).text(2, app.packageName).build();
        return command(20, 4, 22, new Proto.Writer().bytes(6, launch).build());
    }
    public static List<QuickApp> parseList(Proto.Message command) throws IOException {
        if (command.number(1, -1) != 20 || command.number(2, -1) != 0) throw new IOException("不是应用列表应答");
        List<QuickApp> apps = new ArrayList<>();
        Proto.Message list = command.message(22).message(1);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Proto.Field field : list.fields()) {
            if (field.id != 1 || field.wire != 2) continue;
            Proto.Message info = Proto.parse(field.data);
            String pkg = info.text(1, ""), name = info.text(5, "");
            if (!QuickApp.validPackage(pkg) || !seen.add(pkg)) throw new IOException("设备应用列表包含无效或重复包名");
            byte[] fp = info.bytes(2);
            if (fp == null) fp = new byte[0];
            if (fp.length > 512 || name.length() > 256 || apps.size() >= 512) throw new IOException("设备应用列表过大");
            apps.add(new QuickApp(pkg, name, fp, info.number(3, -1), info.number(4, -1)));
        }
        return apps;
    }
    public static byte[] prepareMass(byte[] rpk) throws GeneralSecurityException {
        byte[] body = new Proto.Writer().number(1, 64).bytes(2, MessageDigest.getInstance("MD5").digest(rpk)).number(3, rpk.length).build();
        return command(22, 0, 24, new Proto.Writer().bytes(1, body).build());
    }
    public static List<byte[]> massChunks(byte[] rpk, int chunkSize) throws GeneralSecurityException {
        if (rpk.length == 0 || rpk.length > 16 * 1024 * 1024 || chunkSize < 64 || chunkSize > 32768)
            throw new IllegalArgumentException("传输大小无效");
        ByteBuffer payload = ByteBuffer.allocate(rpk.length + 26).order(ByteOrder.LITTLE_ENDIAN);
        payload.put((byte) 0).put((byte) 64).put(MessageDigest.getInstance("MD5").digest(rpk));
        payload.putInt(rpk.length).put(rpk);
        CRC32 crc = new CRC32(); crc.update(payload.array(), 0, payload.position());
        payload.putInt((int) crc.getValue());
        int partSize = chunkSize - 4, count = (payload.capacity() + partSize - 1) / partSize;
        if (count > 65535) throw new IllegalArgumentException("分片数过多");
        List<byte[]> chunks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int start = i * partSize, size = Math.min(partSize, payload.capacity() - start);
            chunks.add(ByteBuffer.allocate(size + 4).order(ByteOrder.LITTLE_ENDIAN).putShort((short) count)
                    .putShort((short) (i + 1)).put(payload.array(), start, size).array());
        }
        return chunks;
    }
}
