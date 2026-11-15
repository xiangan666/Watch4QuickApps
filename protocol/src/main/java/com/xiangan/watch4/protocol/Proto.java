package com.xiangan.watch4.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 只实现协议用到的 protobuf wire 类型，解析不依赖生成代码。 */
public final class Proto {
    private Proto() {}

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        public Writer number(int field, long value) {
            tag(field, 0); varint(value); return this;
        }
        public Writer bytes(int field, byte[] value) {
            tag(field, 2); varint(value.length); out.write(value, 0, value.length); return this;
        }
        public Writer text(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }
        public Writer float32(int field, float value) {
            tag(field, 5);
            int bits = Float.floatToIntBits(value);
            for (int i = 0; i < 4; i++) out.write(bits >>> (i * 8));
            return this;
        }
        private void tag(int field, int wire) {
            if (field < 1 || field > 536870911) throw new IllegalArgumentException("Invalid field");
            varint(((long) field << 3) | wire);
        }
        private void varint(long n) {
            do {
                int b = (int) (n & 127); n >>>= 7; out.write(n == 0 ? b : b | 128);
            } while (n != 0);
        }
        public byte[] build() { return out.toByteArray(); }
    }

    public static final class Field {
        public final int id, wire;
        public final long number;
        public final byte[] data;
        Field(int id, int wire, long number, byte[] data) {
            this.id = id; this.wire = wire; this.number = number; this.data = data;
        }
    }

    public static final class Message {
        private final List<Field> fields;
        Message(List<Field> fields) { this.fields = Collections.unmodifiableList(fields); }
        public List<Field> fields() { return fields; }
        public boolean has(int id) { return fields.stream().anyMatch(f -> f.id == id); }
        public long number(int id, long fallback) {
            for (Field f : fields) if (f.id == id && f.wire == 0) return f.number;
            return fallback;
        }
        public byte[] bytes(int id) {
            for (Field f : fields) if (f.id == id && f.wire == 2) return f.data.clone();
            return null;
        }
        public String text(int id, String fallback) {
            byte[] b = bytes(id); return b == null ? fallback : new String(b, StandardCharsets.UTF_8);
        }
        public Message message(int id) throws IOException {
            byte[] b = bytes(id);
            if (b == null) throw new IOException("设备应答缺少字段 " + id);
            return parse(b);
        }
    }

    public static Message parse(byte[] data) throws IOException {
        if (data == null || data.length > 4 * 1024 * 1024) throw new IOException("协议消息长度无效");
        List<Field> result = new ArrayList<>();
        int[] cursor = {0};
        while (cursor[0] < data.length) {
            if (result.size() >= 4096) throw new IOException("协议字段过多");
            long tag = varint(data, cursor);
            int wire = (int) tag & 7;
            long id = tag >>> 3;
            if (id < 1 || id > 536870911) throw new IOException("协议字段编号无效");
            long number = 0; byte[] bytes = null;
            if (wire == 0) number = varint(data, cursor);
            else if (wire == 2) {
                long length = varint(data, cursor);
                if (length < 0 || length > data.length - cursor[0]) throw new IOException("协议字段被截断");
                bytes = java.util.Arrays.copyOfRange(data, cursor[0], cursor[0] + (int) length);
                cursor[0] += (int) length;
            } else if (wire == 1 || wire == 5) {
                int size = wire == 1 ? 8 : 4;
                if (data.length - cursor[0] < size) throw new IOException("固定字段被截断");
                for (int i = 0; i < size; i++) number |= (long) (data[cursor[0]++] & 255) << (i * 8);
            } else throw new IOException("不支持的协议 wire 类型 " + wire);
            result.add(new Field((int) id, wire, number, bytes));
        }
        return new Message(result);
    }

    private static long varint(byte[] data, int[] cursor) throws IOException {
        long value = 0;
        for (int i = 0; i < 10; i++) {
            if (cursor[0] >= data.length) throw new IOException("varint 被截断");
            int b = data[cursor[0]++] & 255;
            if (i == 9 && (b & 254) != 0) throw new IOException("varint 溢出");
            value |= (long) (b & 127) << (7 * i);
            if ((b & 128) == 0) return value;
        }
        throw new IOException("varint 过长");
    }
}
