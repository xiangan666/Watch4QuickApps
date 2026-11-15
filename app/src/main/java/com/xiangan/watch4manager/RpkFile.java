package com.xiangan.watch4manager;

import com.xiangan.watch4.protocol.Crypto;
import com.xiangan.watch4.protocol.QuickApp;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class RpkFile {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public final byte[] bytes;
    public final String packageName, name, versionName, sha256;
    public final int versionCode;
    public final boolean hasSignature;
    private RpkFile(byte[] bytes, JSONObject manifest, boolean signature) throws Exception {
        this.bytes = bytes;
        packageName = manifest.getString("package"); name = manifest.optString("name", packageName);
        versionName = manifest.optString("versionName", "未提供");
        versionCode = manifest.getInt("versionCode"); hasSignature = signature;
        if (!QuickApp.validPackage(packageName) || versionCode < 1 || name.length() > 256 || versionName.length() > 128)
            throw new IOException("RPK 包名或版本无效");
        sha256 = Crypto.hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    public static RpkFile read(InputStream input) throws Exception {
        byte[] bytes = bounded(input, MAX_BYTES);
        JSONObject manifest = null; boolean signature = false;
        Set<String> paths = new HashSet<>();
        long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String path = entry.getName();
                if (paths.size() >= 4096 || !paths.add(path) || path.startsWith("/") || path.contains("\\") || path.contains("../"))
                    throw new IOException("RPK 包含无效或重复路径");
                byte[] content = bounded(zip, path.equals("manifest.json") ? 256 * 1024 : MAX_BYTES);
                expanded += content.length;
                if (expanded > 64 * 1024 * 1024) throw new IOException("RPK 解压体积过大");
                if (path.equals("manifest.json")) manifest = new JSONObject(new String(content, StandardCharsets.UTF_8));
                if (path.equals("META-INF/CERT") && content.length > 0) signature = true;
            }
        }
        if (manifest == null) throw new IOException("文件中没有根目录 manifest.json，不是可识别的 RPK");
        // 检查签名存在性；签名有效性和证书信任由手表决定。
        return new RpkFile(bytes, manifest, signature);
    }
    private static byte[] bounded(InputStream input, int limit) throws IOException {
        if (input == null) throw new IOException("无法打开文件");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] chunk = new byte[8192]; int n;
        while ((n = input.read(chunk)) != -1) {
            if (out.size() + n > limit) throw new IOException("文件或解压条目超过大小限制");
            out.write(chunk, 0, n);
        }
        return out.toByteArray();
    }
}
