package com.xiangan.watch4.protocol;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 双向流假设备执行真正的握手、解密和指令解析，避免只对实现做镜像断言。 */
public class SessionTest {
    private static final byte[] KEY = Crypto.unhex("0123456789abcdef0123456789abcdef");
    private static final WatchSession.Events QUIET = new WatchSession.Events() {
        public void log(String m) {} public void progress(int p) {}
    };
    @Test public void authenticatedListInstallAndUninstall() throws Exception {
        try (Device device = new Device(false)) {
            WatchSession session = device.session;
            session.authenticate(KEY, 36, "test");
            assertTrue(session.isAuthenticated()); assertEquals(1, session.listApps().size());
            assertEquals(WatchSession.InstallResult.CONFIRMED, session.install(new byte[4001], "com.example.chess", 1));
            QuickApp app = session.listApps().stream().filter(a -> a.packageName.equals("com.example.chess")).findFirst().get();
            assertTrue(session.uninstall(app)); assertEquals(1, session.listApps().size());
            assertNull(device.error.get());
        }
    }
    @Test public void completionEventWithoutInstalledPackageIsUnconfirmed() throws Exception {
        try (Device device = new Device(true)) {
            device.session.authenticate(KEY, 36, "test");
            assertEquals(WatchSession.InstallResult.NEEDS_CONFIRMATION,
                    device.session.install(new byte[100], "com.example.chess", 1));
        }
    }
    @Test public void wrongPairingKeyNeverAuthenticates() throws Exception {
        try (Device device = new Device(false)) {
            assertThrows(java.security.GeneralSecurityException.class, () -> device.session.authenticate(new byte[16], 36, "test"));
            assertFalse(device.session.isAuthenticated());
        }
    }
    @Test public void listRequiresAuthentication() throws Exception {
        try (Device device = new Device(false)) {
            assertThrows(java.io.IOException.class, () -> device.session.listApps());
        }
    }
    @Test public void rejectedInstallPreservesAuthenticatedConnectionAndSendsNoRpk() throws Exception {
        try (Device device = new Device(false, 3)) {
            device.session.authenticate(KEY, 36, "test");
            DeviceRejectedException rejected = assertThrows(DeviceRejectedException.class,
                    () -> device.session.install(new byte[100], "com.example.chess", 1));
            assertEquals(3, rejected.statusCode);
            assertTrue(rejected.getMessage().contains("存储空间不足"));
            assertTrue(device.session.isAuthenticated());
            assertEquals(1, device.session.listApps().size());
            assertEquals(0, device.massRequests);
            assertNull(device.error.get());
        }
    }

    private static final class Device implements Closeable {
        final PipedInputStream input = new PipedInputStream(65536), peerInput = new PipedInputStream(65536);
        final PipedOutputStream output, peerOutput;
        final WatchSession session;
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final boolean missing;
        final int installStatus;
        volatile int massRequests;
        byte[] rxKey, txKey, rxPrefix, txPrefix;
        int serial; boolean chess;
        Device(boolean missing) throws Exception {
            this(missing, 0);
        }
        Device(boolean missing, int installStatus) throws Exception {
            this.missing = missing; this.installStatus = installStatus;
            output = new PipedOutputStream(peerInput); peerOutput = new PipedOutputStream(input);
            session = new WatchSession(input, output, () -> { input.close(); output.close(); }, QUIET);
            Thread thread = new Thread(this::run, "mock-watch"); thread.setDaemon(true); thread.start();
        }
        private void run() {
            try {
                ByteArrayOutputStream mass = new ByteArrayOutputStream();
                while (true) {
                    SppFrame frame = SppFrame.read(peerInput);
                    if (frame.channel == 0) { send(0, 0, new byte[]{1}); continue; }
                    byte[] payload = frame.payload;
                    if (frame.dataType == 1) {
                        int counter = frame.channel == 5 ? 0 : (payload[0] & 255) | ((payload[1] & 255) << 8);
                        if (frame.channel != 5) payload = Arrays.copyOfRange(payload, 2, payload.length);
                        payload = Crypto.open(rxKey, Crypto.nonce(rxPrefix, counter), payload);
                    }
                    if (frame.channel == 5) {
                        int count = (payload[0] & 255) | ((payload[1] & 255) << 8), seq = (payload[2] & 255) | ((payload[3] & 255) << 8);
                        mass.write(payload, 4, payload.length - 4);
                        if (seq == count) {
                            byte[] all = mass.toByteArray(); assertEquals(64, all[1]);
                            chess = !missing; response(AppProtocol.command(20, 2, 22, new Proto.Writer().build()));
                        }
                        continue;
                    }
                    Proto.Message cmd = Proto.parse(payload);
                    int mod = (int) cmd.number(1, -1), sub = (int) cmd.number(2, -1);
                    if (mod == 1 && sub == 26) {
                        byte[] phone = cmd.message(3).message(30).bytes(1), watch = new byte[16]; Arrays.fill(watch, (byte) 0x55);
                        byte[] derived = Crypto.derive(KEY, phone, watch);
                        txKey = Arrays.copyOfRange(derived, 0, 16); rxKey = Arrays.copyOfRange(derived, 16, 32);
                        txPrefix = Arrays.copyOfRange(derived, 32, 36); rxPrefix = Arrays.copyOfRange(derived, 36, 40);
                        byte[] verify = new Proto.Writer().bytes(1, watch).bytes(2, Crypto.hmac(txKey, Crypto.concat(watch, phone))).build();
                        send(1, 2, AppProtocol.command(1, 26, 3, new Proto.Writer().bytes(31, verify).build()));
                    } else if (mod == 1 && sub == 27) {
                        Proto.Message confirm = cmd.message(3).message(32);
                        Proto.Message info = Proto.parse(Crypto.open(rxKey, Crypto.nonce(rxPrefix, 0), confirm.bytes(2)));
                        assertEquals("test", info.text(3, ""));
                        send(1, 2, AppProtocol.command(1, 27, 3, new Proto.Writer().bytes(33, new Proto.Writer().number(1, 1).build()).build()));
                    } else if (mod == 20 && sub == 0) {
                        Proto.Writer list = new Proto.Writer().bytes(1, app("com.example.base", "Base"));
                        if (chess) list.bytes(1, app("com.example.chess", "象棋"));
                        response(AppProtocol.command(20, 0, 22, new Proto.Writer().bytes(1, list.build()).build()));
                    } else if (mod == 20 && sub == 1) {
                        response(AppProtocol.command(20, 1, 22, new Proto.Writer().bytes(3, new Proto.Writer().number(1, installStatus).build()).build()));
                    } else if (mod == 22 && sub == 0) {
                        massRequests++;
                        mass.reset();
                        response(AppProtocol.command(22, 0, 24, new Proto.Writer().bytes(2,
                                new Proto.Writer().number(2, 0).number(5, 1024).build()).build()));
                    } else if (mod == 20 && sub == 3) chess = false;
                    else throw new AssertionError("Unexpected request " + mod + "/" + sub);
                }
            } catch (java.io.IOException expectedOnClose) { /* pipe closes with the session */ }
            catch (Throwable e) { error.set(e); try { peerOutput.close(); } catch (Exception ignored) {} }
        }
        private byte[] app(String pkg, String name) { return new Proto.Writer().text(1, pkg).text(5, name).bytes(2, new byte[]{1, 2, 3}).build(); }
        private void response(byte[] data) throws Exception { send(1, 1, Crypto.seal(txKey, Crypto.nonce(txPrefix, 0), data)); }
        private synchronized void send(int channel, int type, byte[] data) throws Exception {
            peerOutput.write(new SppFrame(channel, 128, 2, serial++, type, data).encode()); peerOutput.flush();
        }
        @Override public void close() throws java.io.IOException { session.close(); peerInput.close(); peerOutput.close(); }
    }
}
