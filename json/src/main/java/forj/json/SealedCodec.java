package forj.json;

import forj.json.spi.FieldNames;
import forj.json.spi.JsonException;
import forj.json.spi.JsonReader;
import forj.json.spi.JsonToken;
import forj.json.spi.JsonWriter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@link Codec#sealed}: a closed family of cases (a sealed interface and its records) as JSON
 * objects that name their case in a discriminator field, written first:
 * {@code {"type":"card","number":"4111…"}}.
 *
 * <p>Each case keeps its own codec, usually a {@link Codec#product}: encoding wraps the writer
 * so the discriminator goes in as the object's first field, and decoding reads the
 * discriminator, then hands the open object to the case's codec. Missing fields, optional
 * fields and error paths behave exactly as they do for that codec on its own.
 */
public final class SealedCodec<T> implements Codec<T> {
    private record Case<T, C extends T>(String tag, Codec<C> codec, Function<? super T, ? extends C> select) {}

    private final FieldNames discriminator;
    private final List<Case<T, ? extends T>> cases;
    private final Map<String, Case<T, ? extends T>> byTag;

    private SealedCodec(String discriminator, List<Case<T, ? extends T>> cases) {
        this.discriminator = FieldNames.of(discriminator);
        this.cases = List.copyOf(cases);
        this.byTag = new HashMap<>();
        for (Case<T, ? extends T> c : cases) byTag.put(c.tag(), c);
    }

    /** Collects the cases; {@link #build()} makes the codec. */
    public static final class Builder<T> {
        private final String discriminator;
        private final List<Case<T, ? extends T>> cases = new ArrayList<>();

        Builder(String discriminator) {
            this.discriminator = discriminator;
        }

        /**
         * The case written as {@code tag}: {@code select} returns the value as that case, or
         * {@code null} if it's another case ({@code p -> p instanceof Card c ? c : null}).
         * Cases are tried in the order given.
         */
        public <C extends T> Builder<T> when(String tag, Codec<C> codec, Function<? super T, ? extends C> select) {
            if (codec == null) {
                throw new IllegalArgumentException("the codec for case '" + tag + "' is null: probably a class-initialization"
                        + " cycle (the sealed interface's codec read a case's codec before that case finished initializing);"
                        + " wrap it with Codec.lazy(() -> ...)");
            }
            for (Case<T, ? extends T> c : cases) {
                if (c.tag().equals(tag)) throw new IllegalArgumentException("duplicate case '" + tag + "'");
            }
            cases.add(new Case<>(tag, codec, select));
            return this;
        }

        public Codec<T> build() {
            if (cases.isEmpty()) throw new IllegalStateException("a sealed codec needs at least one case");
            return new SealedCodec<>(discriminator, cases);
        }
    }

    // ---- encoding

    @Override
    public void encode(JsonWriter out, T value) {
        for (Case<T, ? extends T> c : cases) {
            if (encodeAs(c, out, value)) return;
        }
        throw new IllegalArgumentException("no case of the sealed codec for " + value.getClass().getSimpleName());
    }

    private <C extends T> boolean encodeAs(Case<T, C> c, JsonWriter out, T value) {
        C selected = c.select().apply(value);
        if (selected == null) return false;
        Tagging tagging = new Tagging(out, discriminator, c.tag());
        c.codec().encode(tagging, selected);
        if (!tagging.tagged) {
            throw new IllegalStateException("the codec for case '" + c.tag() + "' must write a JSON object");
        }
        return true;
    }

    /** Writes the discriminator as the first field of the case codec's object. */
    private static final class Tagging implements JsonWriter {
        private final JsonWriter out;
        private final FieldNames discriminator;
        private final String tag;
        boolean tagged;

        Tagging(JsonWriter out, FieldNames discriminator, String tag) {
            this.out = out;
            this.discriminator = discriminator;
            this.tag = tag;
        }

        private JsonWriter untaggedValue() {
            if (!tagged) throw new IllegalStateException("the codec for case '" + tag + "' must write a JSON object");
            return this;
        }

        public JsonWriter beginObject() {
            out.beginObject();
            if (!tagged) {
                tagged = true;
                out.name(discriminator, 0).value(tag);
            }
            return this;
        }

        public JsonWriter endObject() {
            out.endObject();
            return this;
        }

        public JsonWriter beginArray() {
            untaggedValue();
            out.beginArray();
            return this;
        }

        public JsonWriter endArray() {
            out.endArray();
            return this;
        }

        public JsonWriter name(FieldNames names, int index) {
            out.name(names, index);
            return this;
        }

        public JsonWriter name(String name) {
            out.name(name);
            return this;
        }

        public JsonWriter value(String value) {
            untaggedValue();
            out.value(value);
            return this;
        }

        public JsonWriter value(boolean value) {
            untaggedValue();
            out.value(value);
            return this;
        }

        public JsonWriter value(long value) {
            untaggedValue();
            out.value(value);
            return this;
        }

        public JsonWriter value(double value) {
            untaggedValue();
            out.value(value);
            return this;
        }

        public JsonWriter value(BigDecimal value) {
            untaggedValue();
            out.value(value);
            return this;
        }

        public JsonWriter nullValue() {
            untaggedValue();
            out.nullValue();
            return this;
        }

        public void flush() {
            out.flush();
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }

        public void close() {
            // the outer writer belongs to the caller
        }
    }

    // ---- decoding

    @Override
    public T decode(JsonReader in) {
        in.beginObject();
        if (!in.hasNext() || in.nextField(discriminator) != 0) {
            throw new JsonException("expected the \"" + discriminator.name(0) + "\" field first", in.offset(), in.path());
        }
        String tag = in.readString();
        Case<T, ? extends T> c = byTag.get(tag);
        if (c == null) throw in.invalidValue("unknown " + discriminator.name(0) + " '" + tag + "'");
        return c.codec().decode(new Opened(in));
    }

    /**
     * The reader with the object already opened: the case codec's first {@code beginObject}
     * is the one the sealed codec already read.
     */
    private static final class Opened implements JsonReader {
        private final JsonReader in;
        private boolean opened = true;

        Opened(JsonReader in) {
            this.in = in;
        }

        private void inside(String what) {
            if (opened) throw new IllegalStateException("a sealed case's codec must read a JSON object, not " + what);
        }

        public JsonToken peek() {
            return opened ? JsonToken.BEGIN_OBJECT : in.peek();
        }

        public void beginObject() {
            if (opened) {
                opened = false;
                return;
            }
            in.beginObject();
        }

        public void endObject() {
            inside("the end of an object");
            in.endObject();
        }

        public void beginArray() {
            inside("an array");
            in.beginArray();
        }

        public void endArray() {
            inside("the end of an array");
            in.endArray();
        }

        public boolean hasNext() {
            inside("its fields");
            return in.hasNext();
        }

        public String nextName() {
            inside("a field name");
            return in.nextName();
        }

        public int nextField(FieldNames names) {
            inside("a field name");
            return in.nextField(names);
        }

        public String readString() {
            inside("a string");
            return in.readString();
        }

        public boolean readBoolean() {
            inside("a boolean");
            return in.readBoolean();
        }

        public void readNull() {
            inside("null");
            in.readNull();
        }

        public boolean readNullIfPresent() {
            return !opened && in.readNullIfPresent();
        }

        public int readInt() {
            inside("a number");
            return in.readInt();
        }

        public long readLong() {
            inside("a number");
            return in.readLong();
        }

        public double readDouble() {
            inside("a number");
            return in.readDouble();
        }

        public BigDecimal readBigDecimal() {
            inside("a number");
            return in.readBigDecimal();
        }

        public void skipValue() {
            if (opened) { // the whole case object: skip the rest of the already-open object
                opened = false;
                while (in.hasNext()) {
                    in.nextName();
                    in.skipValue();
                }
                in.endObject();
                return;
            }
            in.skipValue();
        }

        public void endDocument() {
            in.endDocument();
        }

        public String path() {
            return in.path();
        }

        public JsonException invalidValue(String reason) {
            return in.invalidValue(reason);
        }

        public long offset() {
            return in.offset();
        }

        public void close() {
            // the outer reader belongs to the caller
        }
    }

    @Override
    public String toString() {
        return "Codec.sealed(" + discriminator.name(0) + ")" + byTag.keySet();
    }
}
