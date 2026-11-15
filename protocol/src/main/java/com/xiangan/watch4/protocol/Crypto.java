package com.xiangan.watch4.protocol;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Watch 4 会话使用 HKDF-SHA256 与 CCM，固定 nonce=12、tag=4、无 AAD。 */
public final class Crypto {
    private Crypto() {}

    public static byte[] concat(byte[]... parts) {
        int size = 0;
        for (byte[] part : parts) size = Math.addExact(size, part.length);
        byte[] result = new byte[size]; int at = 0;
        for (byte[] part : parts) { System.arraycopy(part, 0, result, at, part.length); at += part.length; }
        return result;
    }

    public static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    public static byte[] derive(byte[] key, byte[] phoneNonce, byte[] watchNonce) throws GeneralSecurityException {
        if (key.length != 16 || phoneNonce.length != 16 || watchNonce.length != 16)
            throw new GeneralSecurityException("鉴权参数长度错误");
        byte[] prk = hmac(concat(phoneNonce, watchNonce), key);
        byte[] label = "miwear-auth".getBytes(StandardCharsets.US_ASCII);
        byte[] first = hmac(prk, concat(label, new byte[]{1}));
        byte[] second = hmac(prk, concat(first, label, new byte[]{2}));
        Arrays.fill(prk, (byte) 0);
        return concat(first, second);
    }

    public static byte[] nonce(byte[] prefix, int counter) {
        if (prefix.length != 4) throw new IllegalArgumentException("Invalid nonce prefix");
        byte[] n = new byte[12]; System.arraycopy(prefix, 0, n, 0, 4);
        for (int i = 0; i < 4; i++) n[8 + i] = (byte) (counter >>> (8 * i));
        return n;
    }

    public static byte[] seal(byte[] key, byte[] nonce, byte[] plain) throws GeneralSecurityException {
        Cipher aes = aes(key, nonce, plain.length);
        byte[] tag = tag(aes, nonce, plain);
        byte[] result = new byte[plain.length + 4];
        applyStream(aes, nonce, plain, result);
        byte[] mask = aes.doFinal(counter(nonce, 0));
        for (int i = 0; i < 4; i++) result[plain.length + i] = (byte) (tag[i] ^ mask[i]);
        return result;
    }

    public static byte[] open(byte[] key, byte[] nonce, byte[] sealed) throws GeneralSecurityException {
        if (sealed.length < 4) throw new GeneralSecurityException("加密消息被截断");
        int size = sealed.length - 4;
        Cipher aes = aes(key, nonce, size);
        byte[] plain = new byte[size];
        applyStream(aes, nonce, Arrays.copyOf(sealed, size), plain);
        byte[] tag = tag(aes, nonce, plain), mask = aes.doFinal(counter(nonce, 0));
        byte[] expected = new byte[4];
        for (int i = 0; i < 4; i++) expected[i] = (byte) (tag[i] ^ mask[i]);
        if (!MessageDigest.isEqual(expected, Arrays.copyOfRange(sealed, size, sealed.length))) {
            Arrays.fill(plain, (byte) 0);
            throw new GeneralSecurityException("设备消息认证失败，连接已中止");
        }
        return plain;
    }

    private static Cipher aes(byte[] key, byte[] nonce, int size) throws GeneralSecurityException {
        if (key.length != 16 || nonce.length != 12 || size >= 0x1000000)
            throw new GeneralSecurityException("CCM 参数无效");
        Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return aes;
    }

    private static byte[] tag(Cipher aes, byte[] nonce, byte[] plain) throws GeneralSecurityException {
        byte[] block = new byte[16]; block[0] = 10; // M=4, L=3, no associated data
        System.arraycopy(nonce, 0, block, 1, 12);
        for (int i = 0; i < 3; i++) block[15 - i] = (byte) (plain.length >>> (8 * i));
        byte[] state = aes.doFinal(block);
        for (int at = 0; at < plain.length; at += 16) {
            Arrays.fill(block, (byte) 0);
            System.arraycopy(plain, at, block, 0, Math.min(16, plain.length - at));
            for (int i = 0; i < 16; i++) block[i] ^= state[i];
            state = aes.doFinal(block);
        }
        return state;
    }

    private static byte[] counter(byte[] nonce, int counter) {
        byte[] block = new byte[16]; block[0] = 2;
        System.arraycopy(nonce, 0, block, 1, 12);
        for (int i = 0; i < 3; i++) block[15 - i] = (byte) (counter >>> (8 * i));
        return block;
    }

    private static void applyStream(Cipher aes, byte[] nonce, byte[] source, byte[] target) throws GeneralSecurityException {
        for (int at = 0; at < source.length; at += 16) {
            byte[] mask = aes.doFinal(counter(nonce, at / 16 + 1));
            for (int i = 0; i < Math.min(16, source.length - at); i++) target[at + i] = (byte) (source[at + i] ^ mask[i]);
        }
    }

    public static byte[] unhex(String text) {
        String s = text.trim(); if (s.startsWith("0x")) s = s.substring(2);
        if ((s.length() & 1) != 0 || !s.matches("[0-9a-fA-F]+")) throw new IllegalArgumentException("需要十六进制值");
        byte[] bytes = new byte[s.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return bytes;
    }

    public static String hex(byte[] data) {
        char[] chars = new char[data.length * 2]; String alphabet = "0123456789abcdef";
        for (int i = 0; i < data.length; i++) { chars[2*i] = alphabet.charAt((data[i] & 255) >>> 4); chars[2*i+1] = alphabet.charAt(data[i] & 15); }
        return new String(chars);
    }
}
