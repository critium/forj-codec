# TODO

In suggested order. Each item is open, added 2026-10-22.

## 1. `derives Codec`: implement the specialized encoder

forj's plugin has `derives` (forj `986f3fa`; Java guide, "Deriving instances"). Without the
optional convention, `record User(...) derives Codec` generates the same `Codec.product(...)`
call the bench writes by hand, so it is no faster. With it, forj generates the encoder as
straight-line code, which E4/E6 measured at +10% (+30% with direct `Instant` writing) on the
1,000-users encode (`docs/benchmarks-for-agents.md`).

- Add `Codec.deriveProduct(constructor, encoder, fields...)` and `deriveField(out, index,
  value)` overloads: `long`, `double`, `boolean`, `String`, and a generic
  `<A> deriveField(out, index, A)(using Codec<A>)`. `out` carries the `JsonWriter`, the
  `FieldNames` and the fields' codecs.
- **Keep encoding consistent with decoding:** decoding still goes through `product`'s field
  codecs, so a given in scope that replaces a built-in (`Codec<Long>` written as a string)
  must win in the typed overloads too. Check `out.codecs[i] == Codec.longs` (one compare) and
  fall back to the field's codec otherwise.
- **Widening traps:** `int`/`short`/`byte` components pick the `long` overload (fine for the
  output, but the fallback must box to the component's own type); `char` and `float` would
  widen to `long`/`double` and write the wrong JSON, so they need their own overloads.
- Null and `omits` behaviour must match `ProductCodec` exactly (same error message).
- Watch the allocation of `out` per object: the encoder call in `ProductCodec.encode` is
  megamorphic across record types in a real app, so escape analysis may not remove it.
- Tests: a `derives` version of `CodecTest`'s records gives identical bytes to the
  hand-written `product`; the guide gets a `derives` section, and the hand-written form
  becomes the fallback in the docs.
- Not possible yet (forj follow-ups): specialized decoding (`deriveRead`), generic records,
  sealed interfaces, self-recursive records.

## 2. Benchmarks: `derives` rows and Jackson 3

- Switch `bench/Model` to `derives Codec`; keep the hand-written `product` codecs as a
  `forjProduct` row so the generic path stays measured.
- Add Jackson 3 rows (`tools.jackson.core:jackson-databind:3.2.4`, the latest). Jackson 3
  sorts properties alphabetically and has `java.time`/`Optional` built in: configure it to
  write the same bytes and extend `BenchEquivalenceTest` to cover it.
- Run on mains power, 2 forks × 8 iterations; update both benchmark docs.
- forj's annotation-processor bug is fixed (forj `986f3fa`), so the bench may be able to drop
  `-proc:none` and the bytecode-generator harness in favour of JMH's own processor. Try it,
  keep the harness if it doesn't work.

## 3. Writer speed without generated code

- Write `Instant` (E3: +5% alone, +30% with derived encoders) and `UUID` straight into the
  buffer instead of through `toString()`.
- A `JsonWriter` method for field values known to be ASCII.
- Fixed-size in-memory segments (E1: 1,168 → 932 KB per call on 1,000 users; memory only).

## 4. Decode side (untested)

- Profile the decode rows the way the 1,000-users encode was profiled.
- Suspects: boxing every field into an `Object[]` for the constructor (goes away with forj's
  `deriveRead`), and `Instant.parse`/`UUID.fromString`.

## 5. More forj-like codecs

- Sealed codecs: the discriminator anywhere in the object (not only first), and the wrapped
  form `{"card": {...}}`.
- A `flatMap` for codecs whose shape depends on a value already read.
- Sealed `derives` (with an exhaustiveness check) once forj supports sealed sums.

## 6. More benchmark coverage

- jsoniter-scala (on JDK 25), the fastest JVM reference.
- Streams: decoding from an `InputStream` and encoding to an `OutputStream`, as the HTTP
  server will.

## 7. json-jackson adapter: close the gap to Jackson databind

**Status:** open, added 2026-10-22. Not urgent: the adapter exists mainly to prove the parser
is pluggable.

The adapter runs forj's codecs at 0.74–0.93× of Jackson databind, although both use
jackson-core (`docs/benchmarks.md`, run 3 in `docs/benchmarks-for-agents.md`). The gap is
largest when encoding.

1. **Profile first:** `bin/bench forjOnJackson -prof "jfr:dir=$PWD/out/jfr;configName=profile"`
   next to `jackson`, and compare CPU and allocation by site, as in the 1,000-users
   investigation.
2. **Likely first fix:** encode into Jackson's own recycled buffers (`ByteArrayBuilder`, from
   the factory's `BufferRecycler`) instead of a `ByteArrayOutputStream`. On 1,000 users the
   adapter allocates 1,716 KB per call against databind's 938 KB.
3. **Other suspects** (untested):
   - `writeNumber(double)` builds a `String` per number (databind pays this too);
   - the surrogate scan on every `value(String)`, and structure checks duplicated with
     Jackson's own;
   - decoding: `nextField` is a `HashMap` lookup on the interned name, plus one
     `JsonLocation` per object for start offsets.
4. **Check after each change:** the TCK, `SameBehaviourTest` (byte-identical output) and a
   2-fork × 8-iteration run of the `forjOnJackson` rows.
