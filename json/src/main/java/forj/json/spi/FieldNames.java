package forj.json.spi;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The field names of one shape, encoded once so readers and writers never build a
 * {@code String} per field.
 *
 * <p>A reader matches the raw bytes of a field name with {@link #indexOf(byte[], int, int)},
 * which doesn't allocate; a writer copies {@link #encodedField(int)}, the name already escaped,
 * quoted and followed by a colon. Indices follow the order the names were given in.
 */
public final class FieldNames {
    private final String[] names;
    private final byte[][] utf8;
    private final byte[][] encoded;
    private final Map<String, Integer> byString;
    /** Open addressing over the UTF-8 hash: index + 1, 0 for an empty slot. */
    private final int[] table;
    private final int mask;

    private FieldNames(String[] names) {
        this.names = names;
        this.utf8 = new byte[names.length][];
        this.encoded = new byte[names.length][];
        this.byString = HashMap.newHashMap(names.length);
        int size = Integer.highestOneBit(Math.max(4, names.length * 2) - 1) << 1;
        this.table = new int[size];
        this.mask = size - 1;
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            if (byString.putIfAbsent(name, i) != null) {
                throw new IllegalArgumentException("duplicate field name: " + name);
            }
            utf8[i] = name.getBytes(StandardCharsets.UTF_8);
            encoded[i] = encodeField(name);
            int slot = hash(utf8[i], 0, utf8[i].length) & mask;
            while (table[slot] != 0) slot = (slot + 1) & mask;
            table[slot] = i + 1;
        }
    }

    public static FieldNames of(String... names) {
        return new FieldNames(names.clone());
    }

    public static FieldNames of(List<String> names) {
        return new FieldNames(names.toArray(String[]::new));
    }

    public int size() {
        return names.length;
    }

    public String name(int index) {
        return names[index];
    }

    /** The name as UTF-8, unescaped. Don't modify it. */
    public byte[] utf8(int index) {
        return utf8[index];
    }

    /** The name ready to write: escaped, quoted and followed by a colon. Don't modify it. */
    public byte[] encodedField(int index) {
        return encoded[index];
    }

    /** The index of the name whose UTF-8 is {@code bytes[from, from + length)}, or -1. */
    public int indexOf(byte[] bytes, int from, int length) {
        int slot = hash(bytes, from, length) & mask;
        for (int entry; (entry = table[slot]) != 0; slot = (slot + 1) & mask) {
            byte[] candidate = utf8[entry - 1];
            if (candidate.length == length
                    && Arrays.equals(candidate, 0, length, bytes, from, from + length)) {
                return entry - 1;
            }
        }
        return -1;
    }

    /** The index of {@code name}, or -1. For backends that only see names as strings. */
    public int indexOf(String name) {
        Integer index = byString.get(name);
        return index == null ? -1 : index;
    }

    private static int hash(byte[] bytes, int from, int length) {
        int h = length;
        for (int i = from, end = from + length; i < end; i++) h = 31 * h + bytes[i];
        return h ^ (h >>> 16);
    }

    private static byte[] encodeField(String name) {
        StringBuilder out = new StringBuilder(name.length() + 3).append('"');
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append("\":").toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "FieldNames" + Arrays.toString(names);
    }
}
