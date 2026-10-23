# TODO

In suggested order. Each item is open, added 2026-10-22.

## 1. Route encode slowed on the hand-written path (run 4)

Run 4 (2026-10-23, `docs/benchmarks-for-agents.md`): hand-written route encode fell from 449
to 410 MB/s (Jackson steady), and forjOnJackson route encode from 297 to 272, while users and
user held. Find out why before quoting "derived vs hand-written" gains on route.
- Suspects: the `ProductCodec.encode` refactor (`encodeField`, `codecs[]`), and profile
  pollution from `CodecBench.setup()` encoding with the derived codecs in every fork.
- Check: an A/B with the old `ProductCodec.encode` loop copied back as an EXPERIMENT, and a
  setup that encodes only with the row's own codec. 2 forks × 8, mains.

## 2. Streaming encode is 41% slower on small HTTP bodies (forj-plugins load bench)

forj-plugins measured `Json.encode(value, out)` in a forj-http `Body.writer` at 63k req/s vs
107k for the `byte[]` path (2026-10-23, `../plugins/docs/benchmarks.md`). Untested
explanation: `ForjWriter.close()` calls `flush()`, which calls `out.flush()` on the caller's
stream (the documented contract: "writes to `out` and flushes it"). On Helidon that pushes
the body out as a chunk before the response closes, likely an extra write per response, and
it may stop Helidon from sending a small body with a `Content-Length`.
- Check first: count socket writes, or try the bench with close writing the buffer without
  `out.flush()`.
- A fix changes a documented API behaviour that forj-http relies on (flush vs not): agree it
  with the user and the forj-http session before changing it. Options: `close()` writes but
  doesn't flush (the caller flushes), or a separate non-flushing `encode`.

## 3. Benchmarks: Jackson 3, and `Codec.Out`

`derives Codec`'s straight-line encoder is measured (run 4: +8–15% over hand-written in the
same run). Still to do:
- Add Jackson 3 rows (`tools.jackson.core:jackson-databind:3.2.4`, the latest). Jackson 3
  sorts properties alphabetically and has `java.time`/`Optional` built in: configure it to
  write the same bytes and extend `BenchEquivalenceTest` to cover it.
- `Codec.Out` (24 B per encoded record) isn't scalar-replaced (run 4 allocation matches
  exactly). Run 4 suggests allocation isn't where the time goes; try an encoder shape without
  it only if a profile says otherwise.
- forj's annotation-processor bug is fixed (forj `986f3fa`), so the bench may be able to drop
  `-proc:none` and the bytecode-generator harness in favour of JMH's own processor. Try it,
  keep the harness if it doesn't work.

Not possible yet (forj follow-ups): specialized decoding (`deriveRead`), generic records,
self-recursive records.

## 4. Writer speed without generated code

- Write `Instant` (E3: +5% alone, +30% with derived encoders) and `UUID` straight into the
  buffer instead of through `toString()`.
- A `JsonWriter` method for field values known to be ASCII.
- Fixed-size in-memory segments (E1: 1,168 → 932 KB per call on 1,000 users; memory only).

## 5. Decode side (untested)

- Profile the decode rows the way the 1,000-users encode was profiled.
- Suspects: boxing every field into an `Object[]` for the constructor (goes away with forj's
  `deriveRead`), and `Instant.parse`/`UUID.fromString`.

## 6. More forj-like codecs

- Sealed codecs: the discriminator anywhere in the object (not only first), and the wrapped
  form `{"card": {...}}`.
- A `flatMap` for codecs whose shape depends on a value already read.
- Sealed `derives` (with an exhaustiveness check) once forj supports sealed sums.

## 7. More benchmark coverage

- jsoniter-scala (on JDK 25), the fastest JVM reference.
- Streams: decoding from an `InputStream` and encoding to an `OutputStream`, as the HTTP
  server will.

## 8. json-jackson adapter: close the gap to Jackson databind

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
