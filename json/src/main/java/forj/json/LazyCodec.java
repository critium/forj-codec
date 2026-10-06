package forj.json;

import forj.json.spi.JsonReader;
import forj.json.spi.JsonWriter;
import java.util.function.Supplier;

/**
 * {@link Codec#lazy}: resolves its codec on first use. A racing first use may resolve it twice,
 * which is harmless: the supplier returns the same given either way.
 */
final class LazyCodec<A> implements Codec<A> {
    private final Supplier<Codec<A>> supplier;
    private volatile Codec<A> codec;

    LazyCodec(Supplier<Codec<A>> supplier) {
        this.supplier = supplier;
    }

    private Codec<A> codec() {
        Codec<A> c = codec;
        if (c == null) {
            c = supplier.get();
            if (c == null) throw new IllegalStateException("lazy codec resolved to null");
            codec = c;
        }
        return c;
    }

    @Override
    public void encode(JsonWriter out, A value) {
        codec().encode(out, value);
    }

    @Override
    public A decode(JsonReader in) {
        return codec().decode(in);
    }

    @Override
    public boolean isOptional() {
        return codec().isOptional();
    }

    @Override
    public A missing() {
        return codec().missing();
    }

    @Override
    public boolean omits(A value) {
        return codec().omits(value);
    }
}
