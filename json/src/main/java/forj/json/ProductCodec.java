package forj.json;

import forj.json.spi.FieldNames;
import forj.json.spi.JsonReader;
import forj.json.spi.JsonWriter;
import java.util.Arrays;
import java.util.function.Function;

/**
 * {@link Codec#product}: a record as a JSON object, its field names matched as bytes. Made by
 * {@link Codec#deriveProduct} it also has a generated {@code encoder}, which writes the fields
 * through {@link Codec#deriveField} instead of the getters; decoding is the same either way.
 */
final class ProductCodec<T> implements Codec<T> {
    private final Function<Object[], T> constructor;
    private final Codec.Encoder<T> encoder;
    private final Codec.Field<T, ?>[] fields;
    final Codec<?>[] codecs;
    final FieldNames names;

    ProductCodec(Function<Object[], T> constructor, Codec.Encoder<T> encoder, Codec.Field<T, ?>[] fields) {
        this.constructor = constructor;
        this.encoder = encoder;
        this.fields = fields.clone();
        this.codecs = Arrays.stream(fields).map(Codec.Field::codec).toArray(Codec<?>[]::new);
        this.names = FieldNames.of(Arrays.stream(fields).map(Codec.Field::name).toList());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void encode(JsonWriter out, T value) {
        out.beginObject();
        if (encoder != null) {
            encoder.encode(new Codec.Out(out, this, value), value);
        } else {
            for (int i = 0; i < fields.length; i++) {
                Codec.Field<T, Object> field = (Codec.Field<T, Object>) fields[i];
                encodeField(out, i, field.getter().apply(value), value);
            }
        }
        out.endObject();
    }

    /** Field {@code i}, holding {@code component}, of {@code value}: the one place nulls and omits are handled. */
    @SuppressWarnings("unchecked")
    void encodeField(JsonWriter out, int i, Object component, Object value) {
        Codec<Object> codec = (Codec<Object>) codecs[i];
        if (component == null && !codec.isOptional()) {
            throw new IllegalArgumentException("field '" + fields[i].name() + "' of " + value.getClass().getSimpleName()
                    + " is null; use Optional or Codec.nullable for absent values");
        }
        if (codec.omits(component)) return;
        out.name(names, i);
        codec.encode(out, component);
    }

    @Override
    public T decode(JsonReader in) {
        Object[] values = new Object[fields.length];
        boolean[] seen = new boolean[fields.length];
        in.beginObject();
        while (in.hasNext()) {
            int i = in.nextField(names);
            if (i < 0) {
                in.skipValue();
                continue;
            }
            values[i] = fields[i].codec().decode(in);
            if (seen[i]) throw in.invalidValue("duplicate field '" + fields[i].name() + "'");
            seen[i] = true;
        }
        in.endObject();
        for (int i = 0; i < fields.length; i++) {
            if (seen[i]) continue;
            Codec<?> codec = fields[i].codec();
            if (!codec.isOptional()) throw in.invalidValue("missing field '" + fields[i].name() + "'");
            values[i] = codec.missing();
        }
        return constructor.apply(values);
    }

    @Override
    public String toString() {
        return (encoder != null ? "Codec.derived" : "Codec.product") + names;
    }
}
