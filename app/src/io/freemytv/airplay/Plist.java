package io.freemytv.airplay;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Apple's binary property lists (bplist00), the bodies of most AirPlay requests. Values map to Map (dict, keys are
 * strings), List (array), String, Long (every integer; 16-byte ones keep their low 8 bytes, so a uint64 like
 * streamConnectionID can come out negative), Double, Boolean, byte[] (data) and Date. Reading is bounded: a sender
 * can't make us loop, recurse deeply or allocate much.
 */
public final class Plist {
    private Plist() {}

    private static final int MAX_OBJECTS = 4096, MAX_DEPTH = 16, MAX_SIZE = 1 << 20;
    private static final long APPLE_EPOCH_MS = 978_307_200_000L;   // 2001-01-01

    public static boolean is(byte[] b) {
        return b != null && b.length >= 8 && new String(b, 0, 8, StandardCharsets.US_ASCII).equals("bplist00");
    }

    // MARK: - Reading

    public static Object read(byte[] b) throws IOException {
        if (!is(b) || b.length < 40 || b.length > MAX_SIZE) throw new IOException("not a binary plist");
        int t = b.length - 32;
        int offsetSize = b[t + 6] & 0xff, refSize = b[t + 7] & 0xff;
        long count = uint(b, t + 8, 8), top = uint(b, t + 16, 8), table = uint(b, t + 24, 8);
        if (offsetSize < 1 || offsetSize > 8 || refSize < 1 || refSize > 8) throw new IOException("bad plist trailer");
        if (count < 1 || count > MAX_OBJECTS || top >= count) throw new IOException("bad plist object count");
        if (table < 8 || table + count * offsetSize > t) throw new IOException("bad plist offset table");
        return new Reader(b, (int) count, (int) table, offsetSize, refSize, t).object((int) top, 0);
    }

    /** read() that gives an empty map for anything that isn't a dict. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> dict(byte[] b) throws IOException {
        Object o = read(b);
        if (!(o instanceof Map)) throw new IOException("plist is not a dict");
        return (Map<String, Object>) o;
    }

    private static long uint(byte[] b, int off, int size) {
        long v = 0;
        for (int i = 0; i < size; i++) v = v << 8 | (b[off + i] & 0xff);
        return v;
    }

    private static final class Reader {
        final byte[] b;
        final int count, table, offsetSize, refSize, end;
        /** Objects decoded so far and bytes copied: shared references could otherwise expand exponentially. */
        int visits;
        long copied;

        Reader(byte[] b, int count, int table, int offsetSize, int refSize, int end) {
            this.b = b;
            this.count = count;
            this.table = table;
            this.offsetSize = offsetSize;
            this.refSize = refSize;
            this.end = end;
        }

        Object object(int ref, int depth) throws IOException {
            if (ref < 0 || ref >= count) throw new IOException("bad plist reference");
            if (depth > MAX_DEPTH) throw new IOException("plist too deep");
            if (++visits > MAX_OBJECTS * 4) throw new IOException("plist too big");
            long at = uint(b, table + ref * offsetSize, offsetSize);
            if (at < 8 || at >= end) throw new IOException("bad plist offset");
            int p = (int) at;
            int marker = b[p] & 0xff, type = marker >> 4, info = marker & 15;
            switch (type) {
                case 0x0:
                    if (info == 8) return Boolean.FALSE;
                    if (info == 9) return Boolean.TRUE;
                    return null;
                case 0x1: {
                    int size = 1 << info;
                    if (info > 4) throw new IOException("bad plist int");
                    need(p + 1, size);
                    // 16-byte integers: the low 8 bytes (an unsigned 64-bit value lands as a negative long).
                    return size == 16 ? uint(b, p + 9, 8) : size == 8 ? uint(b, p + 1, 8) : uint(b, p + 1, size);
                }
                case 0x2: {
                    if (info == 2) { need(p + 1, 4); return (double) Float.intBitsToFloat((int) uint(b, p + 1, 4)); }
                    if (info == 3) { need(p + 1, 8); return Double.longBitsToDouble(uint(b, p + 1, 8)); }
                    throw new IOException("bad plist real");
                }
                case 0x3: {
                    need(p + 1, 8);
                    double seconds = Double.longBitsToDouble(uint(b, p + 1, 8));
                    return new Date(APPLE_EPOCH_MS + (long) (seconds * 1000));
                }
                case 0x4: {
                    int[] len = length(p, info);
                    need(len[1], len[0]);
                    spend(len[0]);
                    return Bytes.slice(b, len[1], len[1] + len[0]);
                }
                case 0x5: {
                    int[] len = length(p, info);
                    need(len[1], len[0]);
                    spend(len[0]);
                    return new String(b, len[1], len[0], StandardCharsets.US_ASCII);
                }
                case 0x6: {
                    int[] len = length(p, info);
                    if (len[0] > MAX_SIZE / 2) throw new IOException("plist string too long");
                    need(len[1], len[0] * 2);
                    spend(len[0] * 2L);
                    return new String(b, len[1], len[0] * 2, StandardCharsets.UTF_16BE);
                }
                case 0x7: {   // UTF-8 (bplist v1.5), rare
                    int[] len = length(p, info);
                    need(len[1], len[0]);
                    spend(len[0]);
                    return new String(b, len[1], len[0], StandardCharsets.UTF_8);
                }
                case 0x8:
                    need(p + 1, info + 1);
                    return uint(b, p + 1, info + 1);
                case 0xA: {
                    int[] len = length(p, info);
                    need(len[1], len[0] * refSize);
                    List<Object> list = new ArrayList<>(Math.min(len[0], 64));
                    for (int i = 0; i < len[0]; i++) list.add(object(ref(len[1] + i * refSize), depth + 1));
                    return list;
                }
                case 0xD: {
                    int[] len = length(p, info);
                    need(len[1], len[0] * refSize * 2);
                    Map<String, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < len[0]; i++) {
                        Object key = object(ref(len[1] + i * refSize), depth + 1);
                        if (!(key instanceof String)) throw new IOException("plist key is not a string");
                        map.put((String) key, object(ref(len[1] + (len[0] + i) * refSize), depth + 1));
                    }
                    return map;
                }
                default:
                    throw new IOException("unknown plist object 0x" + Integer.toHexString(marker));
            }
        }

        /** Data and strings may be referenced many times: at most twice the plist's size gets copied out. */
        void spend(long len) throws IOException {
            copied += len;
            if (copied > 2L * b.length) throw new IOException("plist expands too much");
        }

        int ref(int at) {
            return (int) uint(b, at, refSize);
        }

        /** {length, where the content starts}; lengths of 15 and more follow as an int object. */
        int[] length(int p, int info) throws IOException {
            if (info != 15) return new int[] {info, p + 1};
            need(p + 1, 1);
            int marker = b[p + 1] & 0xff;
            if (marker >> 4 != 1 || (marker & 15) > 3) throw new IOException("bad plist length");
            int size = 1 << (marker & 15);
            need(p + 2, size);
            long len = uint(b, p + 2, size);
            if (len < 0 || len > MAX_SIZE) throw new IOException("plist length too big");
            return new int[] {(int) len, p + 2 + size};
        }

        void need(int from, long len) throws IOException {
            if (len < 0 || from + len > end) throw new IOException("plist object past the end");
        }
    }

    // MARK: - Writing

    public static byte[] write(Object root) {
        List<Object> objects = new ArrayList<>();
        flatten(root, objects);
        int n = objects.size();
        int refSize = n < 256 ? 1 : n < 65536 ? 2 : 4;
        Map<Object, Integer> index = new java.util.IdentityHashMap<>();
        for (int i = 0; i < n; i++) index.put(objects.get(i), i);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('b'); out.write('p'); out.write('l'); out.write('i'); out.write('s'); out.write('t'); out.write('0'); out.write('0');
        long[] offsets = new long[n];
        for (int i = 0; i < n; i++) {
            offsets[i] = out.size();
            writeObject(out, objects.get(i), index, refSize);
        }
        long table = out.size();
        int offsetSize = table < 256 ? 1 : table < 65536 ? 2 : table < (1L << 32) ? 4 : 8;
        for (long o : offsets) writeUint(out, o, offsetSize);
        byte[] trailer = new byte[32];
        trailer[6] = (byte) offsetSize;
        trailer[7] = (byte) refSize;
        putUint(trailer, 8, n);
        putUint(trailer, 16, 0);
        putUint(trailer, 24, table);
        out.write(trailer, 0, 32);
        return out.toByteArray();
    }

    /** Depth-first list of every object (the root first). Strings and numbers are boxed per use, so no sharing issues. */
    private static void flatten(Object o, List<Object> out) {
        out.add(o);
        if (o instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) out.add(new Key(String.valueOf(e.getKey())));
            for (Object v : ((Map<?, ?>) o).values()) flatten(wrap(v), out);
        } else if (o instanceof List) {
            for (Object v : (List<?>) o) flatten(wrap(v), out);
        }
    }

    /** Leaf values get their own identity, so the same String twice is two objects. */
    private static Object wrap(Object v) {
        return v instanceof Map || v instanceof List ? v : new Leaf(v);
    }

    private static final class Key {
        final String s;
        Key(String s) { this.s = s; }
    }

    private static final class Leaf {
        final Object v;
        Leaf(Object v) { this.v = v; }
    }

    private static void writeObject(ByteArrayOutputStream out, Object o, Map<Object, Integer> index, int refSize) {
        if (o instanceof Key) { writeString(out, ((Key) o).s); return; }
        if (o instanceof Leaf) o = ((Leaf) o).v;
        if (o == null) {
            out.write(0x00);
        } else if (o instanceof Boolean) {
            out.write((Boolean) o ? 0x09 : 0x08);
        } else if (o instanceof Long || o instanceof Integer || o instanceof Short || o instanceof Byte) {
            long v = ((Number) o).longValue();
            if (v >= 0 && v < 256) { out.write(0x10); writeUint(out, v, 1); }
            else if (v >= 0 && v < 65536) { out.write(0x11); writeUint(out, v, 2); }
            else if (v >= 0 && v < (1L << 32)) { out.write(0x12); writeUint(out, v, 4); }
            else { out.write(0x13); writeUint(out, v, 8); }
        } else if (o instanceof Double || o instanceof Float) {
            out.write(0x23);
            writeUint(out, Double.doubleToLongBits(((Number) o).doubleValue()), 8);
        } else if (o instanceof Date) {
            out.write(0x33);
            writeUint(out, Double.doubleToLongBits((((Date) o).getTime() - APPLE_EPOCH_MS) / 1000.0), 8);
        } else if (o instanceof byte[]) {
            byte[] d = (byte[]) o;
            header(out, 0x4, d.length);
            out.write(d, 0, d.length);
        } else if (o instanceof String) {
            writeString(out, (String) o);
        } else if (o instanceof List) {
            List<?> list = (List<?>) o;
            header(out, 0xA, list.size());
            int base = index.get(o) + 1;
            // Children were flattened right after their parent, in order: walk them by their sizes.
            for (int i = 0, at = base; i < list.size(); i++) {
                writeUint(out, at, refSize);
                at += size(list.get(i));
            }
        } else if (o instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) o;
            header(out, 0xD, map.size());
            int base = index.get(o) + 1;
            for (int i = 0; i < map.size(); i++) writeUint(out, base + i, refSize);
            int at = base + map.size();
            for (Object v : map.values()) {
                writeUint(out, at, refSize);
                at += size(v);
            }
        } else {
            writeString(out, String.valueOf(o));
        }
    }

    /** How many objects flatten() makes for a value. */
    private static int size(Object v) {
        if (v instanceof Map) {
            int n = 1 + ((Map<?, ?>) v).size();
            for (Object c : ((Map<?, ?>) v).values()) n += size(c);
            return n;
        }
        if (v instanceof List) {
            int n = 1;
            for (Object c : (List<?>) v) n += size(c);
            return n;
        }
        return 1;
    }

    private static void writeString(ByteArrayOutputStream out, String s) {
        boolean ascii = true;
        for (int i = 0; i < s.length() && ascii; i++) ascii = s.charAt(i) < 0x80;
        if (ascii) {
            header(out, 0x5, s.length());
            byte[] b = s.getBytes(StandardCharsets.US_ASCII);
            out.write(b, 0, b.length);
        } else {
            header(out, 0x6, s.length());
            byte[] b = s.getBytes(StandardCharsets.UTF_16BE);
            out.write(b, 0, b.length);
        }
    }

    private static void header(ByteArrayOutputStream out, int type, int len) {
        if (len < 15) {
            out.write(type << 4 | len);
        } else {
            out.write(type << 4 | 15);
            if (len < 256) { out.write(0x10); writeUint(out, len, 1); }
            else if (len < 65536) { out.write(0x11); writeUint(out, len, 2); }
            else { out.write(0x12); writeUint(out, len, 4); }
        }
    }

    private static void writeUint(ByteArrayOutputStream out, long v, int size) {
        for (int i = size - 1; i >= 0; i--) out.write((int) (v >>> (i * 8)) & 0xff);
    }

    private static void putUint(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >>> ((7 - i) * 8));
    }

    // MARK: - Small helpers for handlers

    public static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return m;
    }

    public static long number(Map<String, Object> m, String key, long fallback) {
        Object o = m.get(key);
        return o instanceof Long ? (Long) o : o instanceof Double ? (long) (double) (Double) o : fallback;
    }

    public static byte[] data(Map<String, Object> m, String key) {
        Object o = m.get(key);
        return o instanceof byte[] ? (byte[]) o : null;
    }
}
