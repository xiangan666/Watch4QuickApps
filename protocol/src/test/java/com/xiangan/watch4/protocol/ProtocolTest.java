package com.xiangan.watch4.protocol;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

public class ProtocolTest {
    @Test public void ccmMatchesIndependentOpenSslVectors() throws Exception {
        byte[] key = Crypto.unhex("000102030405060708090a0b0c0d0e0f");
        byte[] nonce = Crypto.unhex("101112130000000001000000");
        int[] sizes = {0, 1, 15, 16, 17, 64};
        String[] vectors = {"2429abc5", "0b6fbd2c4c", "0bcd9334b3d8dece4429d46a2b7ebacd90b0b9",
                "0bcd9334b3d8dece4429d46a2b7eba0a1cf82bb9", "0bcd9334b3d8dece4429d46a2b7eba0a532ccab43e",
                "0bcd9334b3d8dece4429d46a2b7eba0a53864d7bd3bc77eecca041448e41112d6fd0da47f1cb9a9fbe522f4a4c1598b0ad79d930ce88cdce6b78899660c28c53e45454bf"};
        for (int n = 0; n < sizes.length; n++) {
            byte[] plain = new byte[sizes[n]]; for (int i = 0; i < plain.length; i++) plain[i] = (byte) i;
            assertEquals(vectors[n], Crypto.hex(Crypto.seal(key, nonce, plain)));
            assertArrayEquals(plain, Crypto.open(key, nonce, Crypto.unhex(vectors[n])));
        }
    }
    @Test public void tamperedCiphertextIsRejected() throws Exception {
        byte[] key = new byte[16], nonce = new byte[12];
        byte[] sealed = Crypto.seal(key, nonce, new byte[19]); sealed[4] ^= 1;
        assertThrows(java.security.GeneralSecurityException.class, () -> Crypto.open(key, nonce, sealed));
    }
    @Test public void hkdfMatchesIndependentOpenSslVector() throws Exception {
        byte[] key = Crypto.unhex("000102030405060708090a0b0c0d0e0f");
        assertEquals("097214e94de2ff784915ebac006a940eecf7a9c50b10bb976e3331d9728ef9cddc98ce32701aba0b8f7655a756dc8c1b129267ba5640e1099f34f7e808451b46",
                Crypto.hex(Crypto.derive(key, key, Crypto.unhex("101112131415161718191a1b1c1d1e1f"))));
    }
    @Test public void frameHandlesFragmentedInputAndNoise() throws Exception {
        SppFrame frame = new SppFrame(1, 128, 2, 255, 2, new byte[]{0, 1, (byte) 239, 4});
        byte[] input = Crypto.concat(new byte[]{0, 1, (byte) 0xba}, frame.encode());
        ByteArrayInputStream stream = new ByteArrayInputStream(input) {
            @Override public synchronized int read(byte[] out, int off, int len) { return super.read(out, off, Math.min(1, len)); }
        };
        assertEquals(frame, SppFrame.read(stream));
    }
    @Test public void frameRejectsBadTailAndTruncation() {
        byte[] data = new SppFrame(1, 128, 2, 0, 2, new byte[3]).encode(); data[data.length - 1] = 0;
        assertThrows(IOException.class, () -> SppFrame.read(new ByteArrayInputStream(data)));
        assertThrows(IOException.class, () -> SppFrame.read(new ByteArrayInputStream(Arrays.copyOf(data, 12))));
    }
    @Test public void protoPreservesZeroAndRepeatedFields() throws Exception {
        Proto.Message parsed = Proto.parse(new Proto.Writer().number(1, 0).text(22, "中国象棋").number(1, 2).build());
        assertTrue(parsed.has(1)); assertEquals(0, parsed.number(1, -1)); assertEquals("中国象棋", parsed.text(22, ""));
        assertEquals(3, parsed.fields().size());
    }
    @Test public void protoRejectsTruncationAndOverflow() {
        assertThrows(IOException.class, () -> Proto.parse(new byte[]{10, 127, 0}));
        assertThrows(IOException.class, () -> Proto.parse(Crypto.unhex("08ffffffffffffffffffff02")));
        assertThrows(IOException.class, () -> Proto.parse(new byte[]{0}));
    }
    @Test public void listRetainsDeviceFingerprintAndName() throws Exception {
        byte[] info = new Proto.Writer().text(1, "com.example.chess").text(5, "象棋").bytes(2, new byte[]{5, 6}).build();
        byte[] list = new Proto.Writer().bytes(1, info).build();
        byte[] response = AppProtocol.command(20, 0, 22, new Proto.Writer().bytes(1, list).build());
        List<QuickApp> apps = AppProtocol.parseList(Proto.parse(response));
        assertEquals(1, apps.size()); assertEquals("象棋", apps.get(0).name);
        assertArrayEquals(new byte[]{5, 6}, apps.get(0).fingerprint);
        assertEquals(-1, apps.get(0).reported3);
    }
    @Test public void malformedListIsNotReportedAsEmpty() {
        assertThrows(IOException.class, () -> AppProtocol.parseList(Proto.parse(AppProtocol.list())));
    }
    @Test public void massTransferCrcCoversHeaderAndEntireRpk() throws Exception {
        byte[] rpk = new byte[9023]; for (int i = 0; i < rpk.length; i++) rpk[i] = (byte) i;
        List<byte[]> chunks = AppProtocol.massChunks(rpk, 1024);
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (int i = 0; i < chunks.size(); i++) {
            ByteBuffer part = ByteBuffer.wrap(chunks.get(i)).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(chunks.size(), part.getShort() & 65535); assertEquals(i + 1, part.getShort() & 65535);
            joined.write(chunks.get(i), 4, chunks.get(i).length - 4);
        }
        byte[] payload = joined.toByteArray(); assertEquals(0, payload[0]); assertEquals(64, payload[1]);
        assertArrayEquals(rpk, Arrays.copyOfRange(payload, 22, payload.length - 4));
        CRC32 crc = new CRC32(); crc.update(payload, 0, payload.length - 4);
        assertEquals(crc.getValue(), ByteBuffer.wrap(payload, payload.length - 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xffffffffL);
    }
    @Test public void destructiveOperationsRequireDeviceFingerprint() {
        QuickApp unverified = new QuickApp("com.example.chess", "chess", new byte[0], -1, -1);
        assertThrows(IllegalArgumentException.class, () -> AppProtocol.delete(unverified));
    }
    @Test public void requestBytesMatchDocumentedWireFields() throws Exception {
        assertEquals("08141000", Crypto.hex(AppProtocol.list()));
        Proto.Message prepare = Proto.parse(AppProtocol.prepareInstall("com.example.chess", 7, 1234));
        Proto.Message info = prepare.message(22).message(2);
        assertEquals(20, prepare.number(1, 0)); assertEquals(1, prepare.number(2, 0));
        assertEquals("com.example.chess", info.text(1, "")); assertEquals(7, info.number(2, 0)); assertEquals(1234, info.number(3, 0));
    }
}
