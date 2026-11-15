package com.xiangan.watch4.protocol;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** 所有操作由单一工作线程调用；接收线程只负责完整帧入队。 */
public final class WatchSession implements Closeable {
    public interface Events {
        void log(String message);
        void progress(int percent);
        default void detail(String message) {}
    }
    public enum InstallResult { CONFIRMED, NEEDS_CONFIRMATION }
    private final OutputStream output;
    private final Closeable transport;
    private final Events events;
    private final ArrayBlockingQueue<SppFrame> inbox = new ArrayBlockingQueue<>(512);
    private volatile IOException failure;
    private volatile boolean closed;
    private byte[] txKey, rxKey, txPrefix, rxPrefix;
    private int serial, counter;
    private volatile boolean authenticated;

    public WatchSession(InputStream input, OutputStream output, Closeable transport, Events events) {
        this.output = output; this.transport = transport; this.events = events;
        Thread reader = new Thread(() -> {
            try {
                while (!closed) {
                    SppFrame frame = SppFrame.read(input);
                    if (!inbox.offer(frame)) throw new IOException("设备消息队列已满");
                }
            } catch (IOException e) { if (!closed) failure = e; }
        }, "watch4-spp-reader");
        reader.setDaemon(true); reader.start();
    }

    public boolean isAuthenticated() { return authenticated && !closed && failure == null; }

    public void authenticate(byte[] pairingKey, int api, String phoneName) throws Exception {
        if (pairingKey.length != 16) throw new IOException("配对密钥应为 32 位十六进制");
        sendFrame(0, 0, 0, new byte[0], true);
        long deadline = deadline(5000);
        boolean versionReceived = false;
        while (remaining(deadline) > 0) {
            SppFrame frame = nextFrame(deadline, false);
            if (frame == null) break;
            if (frame.channel != 0) continue;
            versionReceived = true;
            if (frame.payload.length > 0 && (frame.payload[0] & 255) >= 2)
                throw new IOException("设备返回 SPP V2，本版本仅实现 Watch 4 的旧版 V1；已停止发送");
            break;
        }
        events.log(versionReceived ? "已确认 SPP V1 连接" : "未返回版本号，按旧版 V1 尝试鉴权");
        byte[] phoneNonce = new byte[16]; new SecureRandom().nextBytes(phoneNonce);
        byte[] appVerify = new Proto.Writer().bytes(1, phoneNonce).build();
        sendPlain(AppProtocol.command(1, 26, 3, new Proto.Writer().bytes(30, appVerify).build()));
        Proto.Message response = await(1, 26, 10000, true);
        requireSuccess(response);
        Proto.Message verify = response.message(3).message(31);
        byte[] watchNonce = verify.bytes(1), signature = verify.bytes(2);
        if (watchNonce == null || watchNonce.length != 16 || signature == null || signature.length != 32)
            throw new IOException("设备鉴权应答格式不匹配");
        byte[] derived = Crypto.derive(pairingKey, phoneNonce, watchNonce);
        rxKey = Arrays.copyOfRange(derived, 0, 16); txKey = Arrays.copyOfRange(derived, 16, 32);
        rxPrefix = Arrays.copyOfRange(derived, 32, 36); txPrefix = Arrays.copyOfRange(derived, 36, 40);
        Arrays.fill(derived, (byte) 0);
        if (!MessageDigest.isEqual(signature, Crypto.hmac(rxKey, Crypto.concat(watchNonce, phoneNonce))))
            throw new GeneralSecurityException("Watch 4 配对密钥不匹配，请重新导入当前设备的密钥");
        byte[] info = new Proto.Writer().number(1, 0).float32(2, api).text(3, phoneName).number(4, 224).text(5, "CN").build();
        byte[] confirm = new Proto.Writer().bytes(1, Crypto.hmac(txKey, Crypto.concat(phoneNonce, watchNonce)))
                .bytes(2, Crypto.seal(txKey, Crypto.nonce(txPrefix, 0), info)).build();
        sendPlain(AppProtocol.command(1, 27, 3, new Proto.Writer().bytes(32, confirm).build()));
        Proto.Message accepted = await(1, 27, 10000, true);
        requireSuccess(accepted);
        Proto.Message auth = accepted.message(3);
        if (!auth.has(33) || auth.message(33).number(1, 0) != 1) throw new IOException("设备未确认鉴权成功");
        authenticated = true;
        events.log("配对鉴权通过");
    }

    public List<QuickApp> listApps() throws Exception {
        ensureAuthenticated();
        sendCommand(AppProtocol.list());
        Proto.Message response = await(20, 0, 12000, true); requireSuccess(response);
        return AppProtocol.parseList(response);
    }

    public void launch(QuickApp app) throws Exception {
        ensureAuthenticated(); sendCommand(AppProtocol.launch(app));
        // 协议没有强制启动回执，只能显示「已发送」。
        events.log("已发送启动指令，请查看手表");
    }

    public boolean uninstall(QuickApp app) throws Exception {
        ensureAuthenticated(); sendCommand(AppProtocol.delete(app));
        events.log("已发送卸载指令，正在核对设备列表");
        for (int i = 0; i < 4; i++) {
            if (i > 0) Thread.sleep(650);
            if (listApps().stream().noneMatch(a -> a.packageName.equals(app.packageName))) return true;
        }
        return false;
    }

    public InstallResult install(byte[] rpk, String pkg, int version) throws Exception {
        ensureAuthenticated();
        events.log("安装预检：" + pkg + "；versionCode=" + version + "；RPK=" + rpk.length + " 字节");
        sendCommand(AppProtocol.prepareInstall(pkg, version, rpk.length));
        Proto.Message ready = await(20, 1, 10000, true); requireSuccess(ready);
        long status = ready.message(22).message(3).number(1, -1);
        events.log("安装预检应答：状态码=" + status + "；设备建议分片长度=" + ready.message(22).message(3).number(2, -1));
        if (status < 0) throw new IOException("设备安装预检应答缺少状态");
        if (status != 0) throw new DeviceRejectedException(status);
        sendCommand(AppProtocol.prepareMass(rpk));
        Proto.Message upload = await(22, 0, 10000, true); requireSuccess(upload);
        Proto.Message ack = upload.message(24).message(2);
        long error = ack.number(2, -1), resume = ack.number(4, 0), chunkSize = ack.number(5, 2048);
        events.log("传输预检应答：状态码=" + error + "；续传位置=" + resume + "；分片长度=" + chunkSize);
        if (error != 0) throw new IOException("设备拒绝传输，状态码 " + error);
        byte[] returnedHash = ack.bytes(1);
        if (returnedHash != null && returnedHash.length > 0
                && !MessageDigest.isEqual(returnedHash, MessageDigest.getInstance("MD5").digest(rpk)))
            throw new IOException("设备传输应答摘要不匹配");
        if (resume != 0) throw new IOException("设备要求从偏移 " + resume + " 续传；本版本只接受完整传输，请重连后重试");
        if (chunkSize < 64 || chunkSize > 32768) throw new IOException("设备分片长度无效：" + chunkSize);
        List<byte[]> chunks = AppProtocol.massChunks(rpk, (int) chunkSize);
        events.log("设备接受 RPK，开始传输，共 " + chunks.size() + " 片");
        for (int i = 0; i < chunks.size(); i++) {
            ensureAuthenticated();
            byte[] encrypted = Crypto.seal(txKey, Crypto.nonce(txPrefix, 0), chunks.get(i));
            sendFrame(5, 2, 1, encrypted, false);
            events.detail("RPK 分片已发送：" + (i + 1) + "/" + chunks.size() + "；明文长度=" + chunks.get(i).length);
            events.progress((i + 1) * 90 / chunks.size());
            Thread.sleep(30);
        }
        events.log("RPK 传输完成，等待设备安装结果");
        Proto.Message installed = await(20, 2, 20000, false);
        if (installed != null) requireSuccess(installed);
        List<QuickApp> apps = listApps();
        boolean present = apps.stream().anyMatch(a -> a.packageName.equals(pkg));
        if (installed != null && present) { events.progress(100); return InstallResult.CONFIRMED; }
        events.log(present ? "设备列表中存在目标包，但缺少本次安装完成回执" : "未从设备列表确认目标包安装完成");
        return InstallResult.NEEDS_CONFIRMATION;
    }

    private void ensureAuthenticated() throws IOException {
        if (!isAuthenticated()) throw new IOException(failure == null ? "请先连接并完成鉴权" : "蓝牙连接已中断");
    }
    private void sendPlain(byte[] payload) throws IOException { traceCommand(payload); sendFrame(2, 2, 2, payload, false); }
    private void sendCommand(byte[] payload) throws Exception {
        traceCommand(payload);
        if (++counter > 65535) throw new IOException("会话计数已用完，请重新连接");
        byte[] encrypted = Crypto.seal(txKey, Crypto.nonce(txPrefix, counter), payload);
        byte[] bytes = Crypto.concat(new byte[]{(byte) counter, (byte) (counter >>> 8)}, encrypted);
        sendFrame(2, 2, 1, bytes, false);
    }
    private void traceCommand(byte[] payload) throws IOException {
        Proto.Message command = Proto.parse(payload);
        events.detail("TX 指令：" + command.number(1, -1) + "/" + command.number(2, -1) + "；length=" + payload.length);
    }
    private synchronized void sendFrame(int channel, int op, int dataType, byte[] bytes, boolean needsResponse) throws IOException {
        if (closed) throw new IOException("连接已关闭");
        int sequence = serial++ & 255;
        events.detail("TX 帧：channel=" + channel + "；opcode=" + op + "；serial=" + sequence + "；type=" + dataType + "；length=" + bytes.length);
        output.write(new SppFrame(channel, needsResponse ? 0xc0 : 0x80, op, sequence, dataType, bytes).encode());
        output.flush();
    }
    private Proto.Message await(int module, int sub, long timeoutMs, boolean required) throws Exception {
        long deadline = deadline(timeoutMs);
        while (remaining(deadline) > 0) {
            SppFrame frame = nextFrame(deadline, false); if (frame == null) break;
            if (frame.channel != 1 && frame.channel != 2) continue;
            byte[] payload = frame.payload;
            if (frame.dataType == 1) {
                if (rxKey == null) throw new IOException("鉴权前收到加密消息");
                payload = Crypto.open(rxKey, Crypto.nonce(rxPrefix, 0), payload);
            }
            Proto.Message command = Proto.parse(payload);
            long receivedModule = command.number(1, -1), receivedSub = command.number(2, -1);
            events.detail("RX 指令：" + receivedModule + "/" + receivedSub + "；状态=" + command.number(100, 0) + "；length=" + payload.length);
            if (command.number(1, -1) == module && command.number(2, -1) == sub) return command;
            // 运动/通知等其它模块不读取、不自动确认，以免改变其同步状态。
        }
        if (required) throw new IOException("等待设备应答超时（" + module + "/" + sub + "）");
        return null;
    }
    private SppFrame nextFrame(long deadline, boolean required) throws IOException, InterruptedException {
        while (remaining(deadline) > 0) {
            if (failure != null) throw new IOException("设备已断开蓝牙连接", failure);
            if (closed) throw new IOException("操作已取消");
            SppFrame frame = inbox.poll(Math.min(150, remaining(deadline)), TimeUnit.MILLISECONDS);
            if (frame != null) {
                events.detail("RX 帧：channel=" + frame.channel + "；opcode=" + frame.opcode + "；serial=" + frame.serial
                        + "；type=" + frame.dataType + "；length=" + frame.payload.length);
                return frame;
            }
        }
        if (required) throw new IOException("等待蓝牙帧超时");
        return null;
    }
    private static long deadline(long millis) { return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis); }
    private static long remaining(long deadline) { return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())); }
    private static void requireSuccess(Proto.Message command) throws IOException {
        long status = command.number(100, 0);
        if (status != 0) throw new IOException("设备返回错误码 " + status);
    }
    @Override public void close() throws IOException {
        closed = true; authenticated = false; transport.close();
        for (byte[] b : new byte[][]{txKey, rxKey, txPrefix, rxPrefix}) if (b != null) Arrays.fill(b, (byte) 0);
        inbox.clear();
    }
}
