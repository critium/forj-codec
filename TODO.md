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

## 2. Streaming JSON on forj-http: decided, fixed in forj-http (K)

forj-plugins' load bench (2026-10-23) found `Json.encode(value, out)` in a forj-http
`Body.writer` 41% slower on small bodies (63k vs 107k req/s). Cause confirmed: the flush in
`ForjWriter.close()` makes Helidon commit the headers early and send chunked; without it,
Helidon sends a small body with `Content-Length` in one write (108k req/s, measured with a
no-flush variant, since deleted).

The user chose K (2026-10-23): forj-http's `Body.writer` ignores flushes until the writer
returns, and a separate streaming body passes them through (SSE). Codec doesn't change and
keeps its documented flush. Flushing affects latency only: memory stays bounded by the
buffers (8 KB in ForjWriter, 4 KB in Helidon) without it.
forj-http shipped K (`Body.writer`, `Body.streaming`); `docs/guide.md` section 8 points HTTP
users at both. Confirmed by forj-plugins' load run: the stream row is at 106.9–107.1k req/s
with `Content-Length`, matching `json(...)`, on the normal forj-json 0.1.0-SNAPSHOT.

Open, not investigated (only if the user asks): at 22.9 KB, `json(...)` does 68.8k req/s and
the stream row 43.4k, with the stream row's mean latency above its p99, so a few requests are
very slow. Above 8 KB, ForjWriter writes in several 8 KB writes, so Helidon goes chunked and
sends several chunks. Untested guess: small separate socket writes meeting Nagle and delayed
ACK (~40 ms stalls on a few requests). Check `TCP_NODELAY` on Helidon's socket and the
latency histogram before changing anything; this is likely a forj-http/Helidon matter, not
codec.

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
