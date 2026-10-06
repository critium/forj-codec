package forj.json;

import forj.json.spi.FieldNames;
import forj.json.spi.JsonReader;
import forj.json.spi.JsonWriter;
import java.util.Arrays;
import java.util.function.Function;

/** {@link Codec#product}: a record as a JSON object, its field names matched as bytes. */
final class ProductCodec<T> implements Codec<T> {
    private final Function<Object[], T> constructor;
    private final Codec.Field<T, ?>[] fields;
    private final FieldNames names;

    ProductCodec(Function<Object[], T> constructor, Codec.Field<T, ?>[] fields) {
        this.constructor = constructor;
        this.fields = fields.clone();
        this.names = FieldNames.of(Arrays.stream(fields).map(Codec.Field::name).toList());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void encode(JsonWriter out, T value) {
        out.beginObject();
        for (int i = 0; i < fields.length; i++) {
            Codec.Field<T, Object> field = (Codec.Field<T, Object>) fields[i];
            Object component = field.getter().apply(value);
            Codec<Object> codec = field.codec();
            if (component == null && !codec.isOptional()) {
                throw new IllegalArgumentException("field '" + field.name() + "' of " + value.getClass().getSimpleName()
                        + " is null; use Optional or Codec.nullable for absent values");
            }
            if (codec.omits(component)) continue;
            out.name(names, i);
            codec.encode(out, component);
        }
        out.endObject();
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
        return "Codec.product" + names;
    }
}
