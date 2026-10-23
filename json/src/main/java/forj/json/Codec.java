package forj.json;

import forj.data.Either;
import forj.json.spi.FieldNames;
import forj.json.spi.JsonReader;
import forj.json.spi.JsonWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * How to write an {@code A} as JSON and read it back: a type class, resolved as a given.
 *
 * <p>Codecs see only {@link JsonWriter} and {@link JsonReader}, so they run on any backend.
 * Malformed or unacceptable input is a {@link forj.json.spi.JsonException} with the value's
 * path; a value that can't be written (a null field, NaN) is an {@link IllegalArgumentException}.
 * {@code encode} is never passed {@code null}; a codec that has a JSON form for "nothing"
 * ({@link #optional}, {@link #nullable}) says so through {@link #isOptional()}.
 *
 * <p>Built in: {@code String}, the primitives and their boxes, {@code BigDecimal},
 * {@code BigInteger}, {@code UUID}, {@code Instant}, {@code List}, {@code Set},
 * {@code Map<String, A>}, {@code Optional} and forj's {@code Either}.
 *
 * <p>Records get a codec from {@code record User(...) derives Codec}, which forj's plugin turns
 * into a call to {@link #deriveProduct}, or by hand from {@link #product}.
 */
public interface Codec<A> {
    void encode(JsonWriter out, A value);

    A decode(JsonReader in);

    /** Whether a field of this type may be missing; {@link #missing()} is then its value. */
    default boolean isOptional() {
        return false;
    }

    /** The value of a missing field, for codecs that are {@link #isOptional() optional}. */
    default A missing() {
        throw new UnsupportedOperationException("required");
    }

    /** Whether to leave a field holding {@code value} out of the object entirely. */
    default boolean omits(A value) {
        return false;
    }

    /** This codec seen through a two-way conversion: a {@code UserId} codec from the long codec. */
    default <B> Codec<B> imap(Function<? super A, ? extends B> to, Function<? super B, ? extends A> from) {
        Codec<A> self = this;
        return new Codec<>() {
            public void encode(JsonWriter out, B value) {
                self.encode(out, from.apply(value));
            }

            public B decode(JsonReader in) {
                return to.apply(self.decode(in));
            }
        };
    }

    // ---- products

    /** One component of a product: its JSON name, how to get it, and its codec. */
    record Field<T, F>(String name, Function<T, F> getter, Codec<F> codec) {}

    /**
     * A field whose codec is the given for its type. Pass the type arguments
     * ({@code Codec.<User, Long>field("id", User::id)}): forj doesn't infer them from a method
     * reference.
     */
    static <T, F> Field<T, F> field(String name, Function<T, F> getter)(using Codec<F> codec) {
        return new Field<>(name, getter, codec);
    }

    /**
     * A codec for a record (or any product) as a JSON object: {@code constructor} receives the
     * decoded fields in the order given, boxed. Unknown fields are skipped; a missing field is
     * an error unless its codec is optional; a repeated field is an error.
     *
     * <pre>{@code
     * given Codec<User> codec = Codec.product(
     *         a -> new User((long) a[0], (String) a[1]),
     *         Codec.<User, Long>field("id", User::id),
     *         Codec.<User, String>field("name", User::name));
     * }</pre>
     */
    @SafeVarargs
    static <T> Codec<T> product(Function<Object[], T> constructor, Field<T, ?>... fields) {
        return new ProductCodec<>(constructor, null, fields);
    }

    // ---- derives: what forj's plugin generates for `record User(...) derives Codec`

    /**
     * {@link #product}, with an encoder forj generates as straight-line code:
     * {@code deriveField(out, 0, user.id()); deriveField(out, 1, user.name()); ...}. Each call
     * picks the {@code deriveField} for its component's type, so a {@code long} is written
     * without boxing or a getter. Decoding is {@code product}'s.
     */
    @SafeVarargs
    static <T> Codec<T> deriveProduct(Function<Object[], T> constructor, Encoder<T> encoder, Field<T, ?>... fields) {
        return new ProductCodec<>(constructor, encoder, fields);
    }

    /** A derived record's generated encoder: writes each field of {@code value} to {@code out}. */
    interface Encoder<T> {
        void encode(Out out, T value);
    }

    /** What a derived encoder writes to: the writer, plus the record's field names and codecs. */
    final class Out {
        final JsonWriter writer;
        final ProductCodec<?> product;
        final Object value;

        Out(JsonWriter writer, ProductCodec<?> product, Object value) {
            this.writer = writer;
            this.product = product;
            this.value = value;
        }
    }

    // Each typed deriveField writes directly only when field i's codec is the built-in one for
    // its type: a given in scope that replaces it (a long written as a string) was used for the
    // field, so decoding expects it, and encoding goes through it too. byte, short, int, char
    // and float have their own overloads, because widening to long or double would lose the
    // component's type for that fallback (and write a char as a number).

    static void deriveField(Out out, int i, long v) {
        if (out.product.codecs[i] == longs) {
            out.writer.name(out.product.names, i).value(v);
        } else {
            deriveField(out, i, (Object) v);
        }
    }

    static void deriveField(Out out, int i, int v) {
        if (out.product.codecs[i] == ints) {
            out.writer.name(out.product.names, i).value((long) v);
        } else {
            deriveField(out, i, (Object) v);
        }
    }

    static void deriveField(Out out, int i, short v) {
        if (out.product.codecs[i] == shorts) {
            out.writer.name(out.product.names, i).value((long) v);
        } else {
            deriveField(out, i, (Object) v);
        }
    }

    static void deriveField(Out out, int i, byte v) {
        if (out.product.codecs[i] == bytes) {
            out.writer.name(out.product.names, i).value((long) v);
        } else {
            deriveField(out, i, (Object) v);
        }
    }

    static void deriveField(Out out, int i, double v) {
        if (out.product.codecs[i] == doubles) {
            out.writer.name(out.product.names, i).value(v);
        } else {
            deriveField(out, i, (Object) v);
        }
    }

    static void deriveField(Out out, int i, boolean v) {
        if (out.product.codecs[i] == booleans) {
            out.writer.name(out.product.names, i).value(v);
        } else {
            deriveField(out, i, (Object) v);
        }
    }

    static void deriveField(Out out, int i, float v) {
        deriveField(out, i, (Object) v);
    }

    static void deriveField(Out out, int i, char v) {
        deriveField(out, i, (Object) v);
    }

    static void deriveField(Out out, int i, String v) {
        if (v != null && out.product.codecs[i] == strings) {
            out.writer.name(out.product.names, i).value(v);
        } else {
            deriveField(out, i, (Object) v);
        }
    }

    /** Every other type: through the field's codec, as {@link #product} writes it. */
    static void deriveField(Out out, int i, Object v) {
        out.product.encodeField(out.writer, i, v, out.value);
    }

    /**
     * A codec for a sealed family of cases, each a JSON object naming its case in the
     * {@code discriminator} field, which comes first:
     *
     * <pre>{@code
     * given Codec<Payment> codec = Codec.<Payment>sealed("type")
     *         .when("card", Card.codec, p -> p instanceof Card c ? c : null)
     *         .when("cash", Cash.codec, p -> p instanceof Cash c ? c : null)
     *         .build();                 // {"type":"card","number":"4111…"}, {"type":"cash"}
     * }</pre>
     *
     * Each case keeps its own codec (usually a {@link #product}). An unknown tag, a missing
     * discriminator or one that isn't the first field is a decoding error; a value no case
     * selects can't be encoded. Nothing checks that every case is listed ({@code derives}
     * doesn't cover sealed interfaces yet), so a forgotten case fails when such a value is
     * encoded.
     */
    static <T> SealedCodec.Builder<T> sealed(String discriminator) {
        return new SealedCodec.Builder<>(discriminator);
    }

    /** Defers to {@code codec} on first use, so a recursive type can refer to its own codec. */
    static <A> Codec<A> lazy(Supplier<Codec<A>> codec) {
        return new LazyCodec<>(codec);
    }

    // ---- JDK instances

    given Codec<String> strings = new Codec<>() {
        public void encode(JsonWriter out, String value) {
            out.value(value);
        }

        public String decode(JsonReader in) {
            return in.readString();
        }
    };

    given Codec<Boolean> booleans = new Codec<>() {
        public void encode(JsonWriter out, Boolean value) {
            out.value((boolean) value);
        }

        public Boolean decode(JsonReader in) {
            return in.readBoolean();
        }
    };

    given Codec<Integer> ints = new Codec<>() {
        public void encode(JsonWriter out, Integer value) {
            out.value((long) value);
        }

        public Integer decode(JsonReader in) {
            return in.readInt();
        }
    };

    given Codec<Long> longs = new Codec<>() {
        public void encode(JsonWriter out, Long value) {
            out.value((long) value);
        }

        public Long decode(JsonReader in) {
            return in.readLong();
        }
    };

    given Codec<Short> shorts = new Codec<>() {
        public void encode(JsonWriter out, Short value) {
            out.value((long) value);
        }

        public Short decode(JsonReader in) {
            int value = in.readInt();
            if (value != (short) value) throw in.invalidValue("number out of short range");
            return (short) value;
        }
    };

    given Codec<Byte> bytes = new Codec<>() {
        public void encode(JsonWriter out, Byte value) {
            out.value((long) value);
        }

        public Byte decode(JsonReader in) {
            int value = in.readInt();
            if (value != (byte) value) throw in.invalidValue("number out of byte range");
            return (byte) value;
        }
    };

    given Codec<Double> doubles = new Codec<>() {
        public void encode(JsonWriter out, Double value) {
            out.value((double) value);
        }

        public Double decode(JsonReader in) {
            return in.readDouble();
        }
    };

    given Codec<Float> floats = new Codec<>() {
        public void encode(JsonWriter out, Float value) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("JSON has no " + value);
            // Float.toString gives the shortest digits for the float, not for its double widening
            out.value(new BigDecimal(Float.toString(value)));
        }

        public Float decode(JsonReader in) {
            float value = (float) in.readDouble();
            if (Float.isInfinite(value)) throw in.invalidValue("number out of float range");
            return value;
        }
    };

    given Codec<Character> chars = new Codec<>() {
        public void encode(JsonWriter out, Character value) {
            out.value(String.valueOf((char) value));
        }

        public Character decode(JsonReader in) {
            String s = in.readString();
            if (s.length() != 1) throw in.invalidValue("expected a single character");
            return s.charAt(0);
        }
    };

    given Codec<BigDecimal> bigDecimals = new Codec<>() {
        public void encode(JsonWriter out, BigDecimal value) {
            out.value(value);
        }

        public BigDecimal decode(JsonReader in) {
            return in.readBigDecimal();
        }
    };

    given Codec<BigInteger> bigIntegers = new Codec<>() {
        public void encode(JsonWriter out, BigInteger value) {
            out.value(new BigDecimal(value));
        }

        public BigInteger decode(JsonReader in) {
            BigDecimal value = in.readBigDecimal();
            try {
                return value.toBigIntegerExact();
            } catch (ArithmeticException e) {
                throw in.invalidValue("expected an integer");
            }
        }
    };

    /** The canonical 36-character form, {@code 123e4567-e89b-12d3-a456-426614174000}. */
    given Codec<UUID> uuids = new Codec<>() {
        public void encode(JsonWriter out, UUID value) {
            out.value(value.toString());
        }

        public UUID decode(JsonReader in) {
            String s = in.readString();
            // UUID.fromString also takes short forms such as "1-2-3-4-5"; JSON gets the canonical one
            if (s.length() != 36) throw in.invalidValue("invalid UUID");
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException e) {
                throw in.invalidValue("invalid UUID");
            }
        }
    };

    /** ISO-8601 in UTC, {@code 2026-10-06T12:00:00Z}, as {@link Instant#toString()} writes it. */
    given Codec<Instant> instants = new Codec<>() {
        public void encode(JsonWriter out, Instant value) {
            out.value(value.toString());
        }

        public Instant decode(JsonReader in) {
            String s = in.readString();
            try {
                return Instant.parse(s);
            } catch (DateTimeParseException e) {
                throw in.invalidValue("invalid ISO-8601 instant");
            }
        }
    };

    // ---- containers

    /** A JSON array; decodes to an unmodifiable list. */
    given <A> Codec<List<A>> list(using Codec<A> element) {
        return new Codec<>() {
            public void encode(JsonWriter out, List<A> value) {
                out.beginArray();
                for (A a : value) {
                    if (a == null) throw new IllegalArgumentException("null list element");
                    element.encode(out, a);
                }
                out.endArray();
            }

            public List<A> decode(JsonReader in) {
                List<A> list = new ArrayList<>();
                in.beginArray();
                while (in.hasNext()) list.add(element.decode(in));
                in.endArray();
                return Collections.unmodifiableList(list);
            }
        };
    }

    /** A JSON array without repeats; decodes to an unmodifiable set in document order. */
    given <A> Codec<Set<A>> set(using Codec<A> element) {
        return new Codec<>() {
            public void encode(JsonWriter out, Set<A> value) {
                out.beginArray();
                for (A a : value) {
                    if (a == null) throw new IllegalArgumentException("null set element");
                    element.encode(out, a);
                }
                out.endArray();
            }

            public Set<A> decode(JsonReader in) {
                Set<A> set = new LinkedHashSet<>();
                in.beginArray();
                while (in.hasNext()) {
                    if (!set.add(element.decode(in))) throw in.invalidValue("repeated set element");
                }
                in.endArray();
                return Collections.unmodifiableSet(set);
            }
        };
    }

    /** {@code {"left": l}} or {@code {"right": r}}: an object with exactly one of the two keys. */
    given <L, R> Codec<Either<L, R>> either(using Codec<L> left, Codec<R> right) {
        FieldNames names = FieldNames.of("left", "right");
        return new Codec<>() {
            public void encode(JsonWriter out, Either<L, R> value) {
                out.beginObject();
                switch (value) {
                    case Either.Left<L, R> l -> {
                        out.name(names, 0);
                        left.encode(out, l.value());
                    }
                    case Either.Right<L, R> r -> {
                        out.name(names, 1);
                        right.encode(out, r.value());
                    }
                }
                out.endObject();
            }

            public Either<L, R> decode(JsonReader in) {
                in.beginObject();
                if (!in.hasNext()) {
                    in.endObject();
                    throw in.invalidValue("expected \"left\" or \"right\"");
                }
                Either<L, R> value = switch (in.nextField(names)) {
                    case 0 -> Either.left(left.decode(in));
                    case 1 -> Either.right(right.decode(in));
                    default -> {
                        in.skipValue();
                        throw in.invalidValue("expected \"left\" or \"right\"");
                    }
                };
                if (in.hasNext()) {
                    in.nextName();
                    in.skipValue();
                    throw in.invalidValue("expected only one of \"left\" and \"right\"");
                }
                in.endObject();
                return value;
            }
        };
    }

    /** A JSON object with any keys; decodes to an unmodifiable map in document order. */
    given <A> Codec<Map<String, A>> map(using Codec<A> value) {
        return new Codec<>() {
            public void encode(JsonWriter out, Map<String, A> map) {
                out.beginObject();
                for (Map.Entry<String, A> e : map.entrySet()) {
                    if (e.getValue() == null) throw new IllegalArgumentException("null map value for " + e.getKey());
                    out.name(e.getKey());
                    value.encode(out, e.getValue());
                }
                out.endObject();
            }

            public Map<String, A> decode(JsonReader in) {
                Map<String, A> map = new LinkedHashMap<>();
                in.beginObject();
                while (in.hasNext()) {
                    String key = in.nextName();
                    if (map.put(key, value.decode(in)) != null) throw in.invalidValue("duplicate key '" + key + "'");
                }
                in.endObject();
                return Collections.unmodifiableMap(map);
            }
        };
    }

    /**
     * May be missing: as a field, {@code empty} is left out when writing, and a missing field
     * or {@code null} reads as {@code empty}. Anywhere else, {@code empty} is {@code null}.
     */
    given <A> Codec<Optional<A>> optional(using Codec<A> value) {
        return new Codec<>() {
            public void encode(JsonWriter out, Optional<A> o) {
                if (o.isPresent()) value.encode(out, o.get());
                else out.nullValue();
            }

            public Optional<A> decode(JsonReader in) {
                return in.readNullIfPresent() ? Optional.empty() : Optional.of(value.decode(in));
            }

            public boolean isOptional() {
                return true;
            }

            public Optional<A> missing() {
                return Optional.empty();
            }

            public boolean omits(Optional<A> o) {
                return o == null || o.isEmpty();
            }
        };
    }

    /**
     * {@code null} as a value, for types that use it to mean "absent". Not a given: plain Java
     * {@code null} is opted into per field, with {@code Codec.field(name, getter)(using
     * Codec.nullable(codec))}. A missing field reads as {@code null}, and a {@code null} field
     * is written as {@code null}.
     */
    static <A> Codec<A> nullable(Codec<A> codec) {
        return new Codec<>() {
            public void encode(JsonWriter out, A value) {
                if (value == null) out.nullValue();
                else codec.encode(out, value);
            }

            public A decode(JsonReader in) {
                return in.readNullIfPresent() ? null : codec.decode(in);
            }

            public boolean isOptional() {
                return true;
            }

            public A missing() {
                return null;
            }
        };
    }
}
