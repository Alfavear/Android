package com.alvear.tvremote;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Codificador/decodificador mínimo de protobuf y framing delimitado por varint. */
final class Proto {

    static final class Field {
        int num;
        int wire;
        long val;
        byte[] data;
    }

    private Proto() {
    }

    static byte[] varint(long v) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        while ((v & ~0x7FL) != 0) {
            o.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        o.write((int) v);
        return o.toByteArray();
    }

    static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    static byte[] num(int field, long v) {
        return cat(varint((long) field << 3), varint(v));
    }

    static byte[] bytes(int field, byte[] b) {
        return cat(varint(((long) field << 3) | 2), varint(b.length), b);
    }

    static byte[] str(int field, String s) {
        return bytes(field, s.getBytes(StandardCharsets.UTF_8));
    }

    static List<Field> parse(byte[] b) {
        List<Field> out = new ArrayList<>();
        int i = 0;
        while (i < b.length) {
            long tag = 0;
            int shift = 0;
            int c;
            do {
                c = b[i++] & 0xFF;
                tag |= (long) (c & 0x7F) << shift;
                shift += 7;
            } while ((c & 0x80) != 0);
            Field f = new Field();
            f.num = (int) (tag >>> 3);
            f.wire = (int) (tag & 7);
            switch (f.wire) {
                case 0: {
                    long v = 0;
                    shift = 0;
                    do {
                        c = b[i++] & 0xFF;
                        v |= (long) (c & 0x7F) << shift;
                        shift += 7;
                    } while ((c & 0x80) != 0);
                    f.val = v;
                    break;
                }
                case 2: {
                    long len = 0;
                    shift = 0;
                    do {
                        c = b[i++] & 0xFF;
                        len |= (long) (c & 0x7F) << shift;
                        shift += 7;
                    } while ((c & 0x80) != 0);
                    f.data = new byte[(int) len];
                    System.arraycopy(b, i, f.data, 0, (int) len);
                    i += (int) len;
                    break;
                }
                case 1:
                    i += 8;
                    break;
                case 5:
                    i += 4;
                    break;
                default:
                    return out;
            }
            out.add(f);
        }
        return out;
    }

    static Field get(List<Field> fields, int num) {
        for (Field f : fields) if (f.num == num) return f;
        return null;
    }

    static void writeDelimited(OutputStream o, byte[] msg) throws IOException {
        o.write(cat(varint(msg.length), msg));
        o.flush();
    }

    static byte[] readDelimited(InputStream in) throws IOException {
        long len = 0;
        int shift = 0;
        while (true) {
            int c = in.read();
            if (c < 0) throw new EOFException();
            len |= (long) (c & 0x7F) << shift;
            if ((c & 0x80) == 0) break;
            shift += 7;
        }
        byte[] b = new byte[(int) len];
        int off = 0;
        while (off < len) {
            int r = in.read(b, off, (int) len - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return b;
    }
}
