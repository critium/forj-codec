# forj-codec

JSON codecs for [forj](../../forj): type class codecs derived at compile time, written once
against a streaming SPI, with a fast, low-allocation parser of our own as the default backend.

It is the codec layer of forj-http (an http4s-style server framework for forj), but it doesn't
depend on any HTTP code and can be used on its own. The full plan is in
[`../planning`](../planning/lets-make-something-meaningfully-warm-lark.md).

**Status:** the SPI, forj's reader and writer, the backend TCK, `Codec` and its instances are
in. `bench` and `json-jackson` come next, then `derives` (in forj's plugin).

```java
record User(long id, String name, Instant joined) {
    // by hand today; `record User(...) derives Codec` will generate the same member
    given Codec<User> codec = Codec.product(
            a -> new User((long) a[0], (String) a[1], (Instant) a[2]),
            Codec.<User, Long>field("id", User::id),
            Codec.<User, String>field("name", User::name),
            Codec.<User, Instant>field("joined", User::joined));
}

byte[] bytes = Json.encode(user);
User back = Json.decode(bytes);           // the codec is found from the typed local

// a closer given changes a component codec, or the whole parser
given Codec<Instant> millis = Codec.longs.imap(Instant::ofEpochMilli, Instant::toEpochMilli);
given JsonBackend backend = ForjBackend.of(limits, 16_384, 512);
```

`encode`/`decode` take a `byte[]` or an `OutputStream`/`InputStream`.

### Codec behaviour

- **Records are JSON objects,** with fields written in declaration order.
- **Unknown fields are skipped** without being decoded.
- **A missing or `null` required field is an error,** reported with the object's path:
  `missing field 'name' at $.users[3]`.
- **A repeated field is an error.** So is a repeated key in a `Map`.
- **`Optional` fields:** `empty` is left out when writing, and a missing field or `null` reads
  as `empty`. `Codec.nullable(codec)` opts a plain field into `null` instead.
- **A codec that rejects a well-formed value** (a malformed UUID, a short out of range) reports
  that value's path and offset, through `JsonReader.invalidValue`.
- **Built in:** `String`, `boolean`, `int`, `long`, `short`, `byte`, `double`, `float`,
  `char` (one-character strings), `BigDecimal`, `BigInteger`, `UUID` (canonical form only),
  `Instant` (ISO-8601), `List<A>`, `Map<String, A>`, `Optional<A>`.
- **Recursive types** wrap the self-reference in `Codec.lazy(() -> ...)`.

## Design goals

### 1. Codecs are written once; the parser is pluggable

- `Codec<A>` (encoder + decoder) targets `forj.json.spi.JsonReader` / `JsonWriter`, which are
  pull-style, token-level interfaces (`beginObject`, `nextField`, `readLong`, `skipValue`,
  `writeField`, ...).
- There is no tree in the SPI, so no backend forces an AST on the codecs, and no codec
  depends on a particular parser.
- `JsonBackend` creates readers and writers over a `byte[]`, an `InputStream` or an
  `OutputStream`.

### 2. Everything is decided at compile time

- **The backend is a given.**
  - forj's own backend is the lowest-priority instance, so it's the default.
  - An app swaps it with `given JsonBackend backend = ...;`, and a route or a test can swap it
    with a local given.
  - There's no `ServiceLoader`, no classpath scanning and no reflection.
- **Codecs are built from the record, not reflected.**
  - `Codec.product(constructor, fields...)` builds a record codec from its constructor and,
    for each component, its name, accessor and codec.
  - `derives Codec` (forj's javac plugin, coming) will generate exactly that member, so there
    is no separate schema type.
  - **The record gives the structure, and the resolver gives the component codecs.** Each
    component codec is a given, so a user's `given Codec<Instant>` applies wherever an
    `Instant` appears.
  - A missing instance is a compile error (`forj: no given ...`), not a runtime surprise.
  - Recursive records work: `Codec.lazy` resolves the self-reference on first use.
- **One way to build codecs.** Hand-written codecs, `derives` and (later) Smithy codegen all
  produce the same `Codec.product(...)` call.

### 3. The default backend has no third-party code

- Our own streaming `JsonReader`/`JsonWriter`, working on bytes in jsoniter style.
- Third-party parsers live only behind the SPI, in optional modules: `json-jackson` first;
  Gson, fastjson2 and Jakarta JSON-P later. The core never depends on them.

### 4. Built for virtual threads

- Blocking pull reads straight from an `InputStream`. A slow body parks the virtual thread
  instead of holding a carrier, with no callbacks and no executor.
- **No `ThreadLocal` buffer caches.** With a million virtual threads, a per-thread cache
  means a million caches. Buffers come from a bounded, lock-free shared pool and go back on
  `close()`.
- No locks on the hot path, and nothing assumes a small, fixed set of threads.

### 5. Low memory and low allocation

- **Streaming end to end.** A body is never fully materialized. Per-request memory is about
  one fixed buffer (8 KB by default) plus the nesting depth.
- **Field names are matched as bytes** against each record's precomputed `FieldNames`, with no
  `String` per field.
- **Numbers are parsed from the buffer:**
  - a fast path for `int`/`long` digit runs, with no allocation;
  - `double` and `BigDecimal` parsed from the byte slice, falling back to the JDK only for
    exotic forms.
- **Strings are decoded once,** straight into the target value.
- **Unknown fields are skipped without decoding** (`skipValue`).
- **The writer:**
  - pre-escapes and pre-encodes field names once per record;
  - has an ASCII fast path for escaping;
  - writes integers without going through `String`;
  - flushes a bounded buffer to the output stream, never to one big `byte[]`.
- Codecs are built once, at class init, and cached.

### 6. Strict, safe and precise about errors

- Strict RFC 8259: escapes and surrogate pairs handled, no trailing commas, no comments.
- Limits on nesting depth, string length and document size.
- Errors carry the byte offset and the JSON path (`$.users[3].name`). forj-http puts them in
  a 400 problem+json response.

### 7. Every backend behaves the same

- **The backend TCK is published as `forj-json-tck`,** one test suite that every backend
  must pass, wherever it lives:
  - the [JSONTestSuite](https://github.com/nst/JSONTestSuite) cases: every `y_` accepted,
    every `n_` rejected, every `i_` decided and documented;
  - property-style round trips (random value → write → read → equal);
  - error paths.
- Where an adapter can't match something (for example, the JSON path in its errors), the
  TCK marks that check optional, and this README says so.

### 8. Measured, not assumed

- `bench` runs on a fixed corpus and reports read and write throughput (MB/s) and bytes
  allocated per operation (JFR, `-XX:+UseEpsilonGC`). Its rows:

  | Row | Why it's there |
  |---|---|
  | forj codecs on the forj backend | what we ship |
  | forj codecs on `json-jackson` | the same codecs on another parser, so only the parser differs |
  | Jackson databind (`ObjectMapper`) | what most Java users run today |
  | jsoniter-scala | compile-time derived and streaming like us, usually the fastest on the JVM: the number to beat |

- The Jackson databind and jsoniter-scala rows are references only. They live in `bench` and
  never become a dependency of a published module. jsoniter-scala is a Scala module pinned to
  JDK 25 (the Scala compiler may not read JDK 28 EA class files), so every row records its
  JDK.
- A 100k-virtual-thread test checks that peak heap stays bounded (pool size × buffer) and
  that no per-thread caches show up in a heap histogram.

## Implementation-defined inputs

JSONTestSuite leaves some inputs (its `i_` cases) to each parser. forj's backend decides them
like this, and `ImplementationDefinedTest` pins every case:

- **Numbers of any size are accepted.** `1e999` and a 400-digit integer are valid JSON. Range
  is only checked by the typed read that needs it: `readLong` rejects values outside `long`,
  `readDouble` rejects values that overflow to infinity, and `readBigDecimal` takes anything
  `BigDecimal` can hold.
- **Malformed UTF-8 is rejected:** overlong forms, encoded surrogates, values above U+10FFFF,
  truncated sequences, Latin-1 and UTF-16 input.
- **Unpaired surrogates are rejected,** whether raw or written as `\u` escapes, because no
  valid UTF-8 can carry them.
- **A byte order mark is rejected.** RFC 8259 forbids sending one.
- **Nesting is accepted up to `JsonLimits.maxDepth`,** which is 512 by default.

## Anti-goals

**No reflection, anywhere.** This is a rule, not a missing feature:
- No `java.lang.reflect`, no looking up members by name with `MethodHandles`, no
  `ServiceLoader`, no annotation scanning, no runtime bytecode generation.
- Shapes come from `derives` or hand-written codecs, and backends from givens, all resolved
  at compile time.
- Adapters follow the same rule: `json-jackson` wraps jackson-core's streaming API, never
  databind.
- Reflection-based libraries appear only as reference rows in `bench`.

What this buys:
- errors at compile time instead of in production;
- no warm-up cost from reflective scans;
- nothing to configure for GraalVM native image or Leyden AOT;
- codecs a reader can follow.

## Non-goals

- **A tree model** (`JsonNode` and the like). Codecs stream; a tree can be built on top later
  if needed.
- **HTTP awareness.** The bridge to forj-http (`Ok(json(user))`, `req.<User>json()`) lives in
  forj-http's `http-json` module, so plain text never silently becomes JSON.
- **Lenient parsing modes** (comments, trailing commas, NaN), for now.

## Modules

| Module | Artifact | What it is |
|---|---|---|
| `json` | `forj:forj-json` | the SPI, the forj backend, `Codec<A>` and its instances |
| `json-tck` | `forj:forj-json-tck` | the backend test suite, for any backend in any repo |
| `json-jackson` | `forj:forj-json-jackson` | optional adapter over jackson-core's streaming API |
| `bench` | (not published) | the micro-benchmark: our backends, Jackson databind, jsoniter-scala |

**Instances (given):**
- primitives and their boxes, `String`, `BigDecimal`, `UUID`, `Instant`;
- `List<A>`, `Optional<A>`, `Map<String, A>`;
- derived records.

Sealed-interface sums (with a discriminator) come in milestone 2. If the benchmark shows
parsing on the hot path, SIMD-style scanning (Vector API) might follow.

## Requirements

- forj `0.1.0-SNAPSHOT`, published to `~/.m2` with `./mill __.publishM2Local` in the forj repo.
- The same pinned JDK 28 EA as forj, with `--enable-preview`, because forj's jars hold
  preview class files. See forj's README, "Using forj from another build".

## Building

```sh
(cd ../../forj && ./mill __.publishM2Local)   # forj-core and forj-plugin into ~/.m2
bin/fetch-jdk                                  # the pinned JDK into .jdk/ (once)
./mill __.test
```

`bin/fetch-jdk` downloads the same JDK build forj pins. If forj's `.jdk` is already there,
linking it saves the download:
`mkdir -p .jdk && ln -s "$(cd ../../forj/.jdk/current && pwd -P)" .jdk/current && bin/make-preview-jdk`.

`build.mill` copies forj's build traits (`PinnedJdk`, `ForjModule`, ...). Keep them in step
with forj until they ship as a Mill plugin.
