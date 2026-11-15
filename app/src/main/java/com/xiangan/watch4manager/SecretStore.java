package com.xiangan.watch4manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 配对密钥只以 Android Keystore 加密形式持久化，禁止备份。 */
public final class SecretStore {
    private final String alias;
    private final SharedPreferences prefs;
    public SecretStore(Context context) { this(context, "connection", "watch4-pairing-v1"); }
    SecretStore(Context context, String preferenceName, String alias) {
        prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE); this.alias = alias;
    }
    public String address() { return prefs.getString("address", ""); }
    public String key() throws Exception {
        String value = prefs.getString("sealed", ""); if (value.isEmpty()) return "";
        byte[] all = Base64.decode(value, Base64.NO_WRAP);
        if (all.length < 28) throw new IllegalStateException("本地密钥存储无效，请重新导入");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, localKey(), new GCMParameterSpec(128, Arrays.copyOf(all, 12)));
        cipher.updateAAD(address().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Arrays.copyOfRange(all, 12, all.length)), java.nio.charset.StandardCharsets.US_ASCII);
    }
    public void save(String address, String key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, localKey());
        cipher.updateAAD(address.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] sealed = cipher.doFinal(key.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        byte[] all = com.xiangan.watch4.protocol.Crypto.concat(cipher.getIV(), sealed);
        if (!prefs.edit().putString("address", address).putString("sealed", Base64.encodeToString(all, Base64.NO_WRAP)).commit())
            throw new IllegalStateException("密钥保存失败");
    }
    public void clear() throws Exception {
        if (!prefs.edit().clear().commit()) throw new IllegalStateException("清除密钥失败");
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(alias)) ks.deleteEntry(alias);
    }
    private SecretKey localKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(alias)) return (SecretKey) ks.getKey(alias, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
