package forj.json.tck;

import forj.json.spi.FieldNames;
import forj.json.spi.JsonReader;
import forj.json.spi.JsonToken;
import forj.json.spi.JsonWriter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Plain Java values standing in for JSON in the TCK: {@code Map<String, Object>} (insertion
 * ordered), {@code List<Object>}, {@code String}, {@code Long}, {@code Double},
 * {@code BigDecimal}, {@code Boolean} and {@code null}.
 */
final class Values {
    private Values() {}

    static void write(JsonWriter w, Object value) {
        switch (value) {
            case null -> w.nullValue();
            case Map<?, ?> map -> {
                w.beginObject();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    w.name((String) e.getKey());
                    write(w, e.getValue());
                }
                w.endObject();
            }
            case List<?> list -> {
                w.beginArray();
                for (Object element : list) write(w, element);
                w.endArray();
            }
            case String s -> w.value(s);
            case Long l -> w.value((long) l);
            case Double d -> w.value((double) d);
            case BigDecimal d -> w.value(d);
            case Boolean b -> w.value((boolean) b);
            default -> throw new IllegalArgumentException(value.getClass().getName());
        }
    }

    /** Reads a value of the same shape and kinds as {@code expected}, using the typed reads. */
    static Object readLike(JsonReader r, Object expected, boolean byFieldNames) {
        switch (expected) {
            case null -> {
                r.readNull();
                return null;
            }
            case Map<?, ?> map -> {
                List<String> keys = map.keySet().stream().map(String.class::cast).toList();
                FieldNames names = FieldNames.of(keys);
                Map<String, Object> out = new LinkedHashMap<>();
                r.beginObject();
                while (r.hasNext()) {
                    String key;
                    if (byFieldNames) {
                        int index = r.nextField(names);
                        if (index < 0) throw new AssertionError("unknown field at " + r.path());
                        key = names.name(index);
                    } else {
                        key = r.nextName();
                    }
                    out.put(key, readLike(r, map.get(key), byFieldNames));
                }
                r.endObject();
                return out;
            }
            case List<?> list -> {
                List<Object> out = new ArrayList<>();
                r.beginArray();
                for (Object element : list) {
                    if (!r.hasNext()) throw new AssertionError("array ended early at " + r.path());
                    out.add(readLike(r, element, byFieldNames));
                }
                r.endArray();
                return out;
            }
            case String s -> {
                return r.readString();
            }
            case Long l -> {
                return r.readLong();
            }
            case Double d -> {
                return r.readDouble();
            }
            case BigDecimal d -> {
                return r.readBigDecimal();
            }
            case Boolean b -> {
                return r.readBoolean();
            }
            default -> throw new IllegalArgumentException(expected.getClass().getName());
        }
    }

    /**
     * Walks any document to its end, checking every token: strings and names are decoded,
     * numbers skipped (their grammar is still checked). Used for the parsing cases, where the
     * shape isn't known in advance.
     */
    static void walk(JsonReader r) {
        walkValue(r);
        r.endDocument();
    }

    private static void walkValue(JsonReader r) {
        JsonToken token = r.peek();
        switch (token) {
            case BEGIN_OBJECT -> {
                r.beginObject();
                while (r.hasNext()) {
                    r.nextName();
                    walkValue(r);
                }
                r.endObject();
            }
            case BEGIN_ARRAY -> {
                r.beginArray();
                while (r.hasNext()) walkValue(r);
                r.endArray();
            }
            case STRING -> r.readString();
            case NUMBER -> r.skipValue();
            case BOOLEAN -> r.readBoolean();
            case NULL -> r.readNull();
            default -> throw new AssertionError("unexpected " + token + " at " + r.path());
        }
    }

    // ---- random values

    static Object random(Random random, int depth) {
        int kind = random.nextInt(depth > 4 ? 6 : 8);
        return switch (kind) {
            case 0 -> null;
            case 1 -> random.nextBoolean();
            case 2 -> randomString(random);
            case 3 -> randomLong(random);
            case 4 -> randomDouble(random);
            case 5 -> new BigDecimal(random.nextLong()).scaleByPowerOfTen(random.nextInt(-40, 40));
            case 6 -> {
                List<Object> list = new ArrayList<>();
                for (int i = random.nextInt(6); i > 0; i--) list.add(random(random, depth + 1));
                yield list;
            }
            default -> {
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = random.nextInt(6); i > 0; i--) map.put(randomString(random), random(random, depth + 1));
                yield map;
            }
        };
    }

    private static long randomLong(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> Long.MIN_VALUE + random.nextInt(2);
            case 1 -> Long.MAX_VALUE - random.nextInt(2);
            case 2 -> random.nextInt(-1000, 1000);
            default -> random.nextLong();
        };
    }

    private static double randomDouble(Random random) {
        for (;;) {
            double d = switch (random.nextInt(4)) {
                case 0 -> Double.longBitsToDouble(random.nextLong());
                case 1 -> random.nextDouble() * 1000;
                case 2 -> random.nextInt(-1000, 1000) / 100.0;
                default -> random.nextBoolean() ? 0.0 : -0.0;
            };
            if (Double.isFinite(d)) return d;
        }
    }

    /** Strings mixing ASCII, characters that need escaping, 2- and 3-byte UTF-8 and surrogate pairs. */
    static String randomString(Random random) {
        int length = random.nextInt(random.nextInt(10) == 0 ? 200 : 12);
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            switch (random.nextInt(10)) {
                case 0 -> out.append("\"\\/\b\f\n\r\t".charAt(random.nextInt(8)));
                case 1 -> out.append((char) random.nextInt(0x20));
                case 2 -> out.append((char) random.nextInt(0x80, 0x800));
                case 3 -> {
                    char c;
                    do c = (char) random.nextInt(0x800, 0x10000); while (Character.isSurrogate(c));
                    out.append(c);
                }
                case 4 -> out.appendCodePoint(random.nextInt(0x10000, 0x110000));
                default -> out.append((char) random.nextInt(0x20, 0x7F));
            }
        }
        return out.toString();
    }

    // ---- inputs

    /** Hands out at most a few bytes per read, so every token gets split across reads. */
    static InputStream trickle(byte[] bytes, Random random) {
        return new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1 + random.nextInt(7)));
            }
        };
    }

    static boolean equal(Object a, Object b) {
        if (a instanceof Double x && b instanceof Double y) {
            return Double.doubleToLongBits(x) == Double.doubleToLongBits(y);
        }
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) return false;
            for (Map.Entry<?, ?> e : x.entrySet()) {
                if (!y.containsKey(e.getKey()) || !equal(e.getValue(), y.get(e.getKey()))) return false;
            }
            return true;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!equal(x.get(i), y.get(i))) return false;
            return true;
        }
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        return a == null ? b == null : a.equals(b);
    }

    static String show(byte[] bytes) {
        return bytes.length <= 200 ? new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                : new String(Arrays.copyOf(bytes, 200), java.nio.charset.StandardCharsets.UTF_8) + "...";
    }
}
