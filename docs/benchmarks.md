# Benchmarks

forj-codec compared with Jackson databind on the same records and the same bytes.

## Current results

| Data | Operation | forj | Jackson | forj ÷ Jackson | Memory per call (forj / Jackson) |
|---|---|---:|---:|---:|---|
| 1 user (225 B) | decode | 201 MB/s | 198 MB/s | 1.02× | 2.8 / 2.9 KB |
| 1 user | encode | 472 MB/s | 437 MB/s | 1.08× | 0.8 / 1.0 KB |
| 1,000 users (285 KB) | decode | 215 MB/s | 223 MB/s | 0.96× | 2.3 / 2.3 MB |
| 1,000 users | encode | 433 MB/s | 445 MB/s | 0.97× | 1.1 / 0.9 MB |
| 10,000 points (510 KB) | decode | 188 MB/s | 150 MB/s | **1.25×** | 2.3 / 3.7 MB |
| 10,000 points | encode | 446 MB/s | 339 MB/s | **1.32×** | 1.3 / 3.1 MB |

**In short:** forj is level with Jackson on string-heavy records and 25–32% faster on
number-heavy data, where it also allocates less than half the memory.

Measured 2026-10-06 on an Apple M2 Pro (10 cores, 32 GB, macOS 27.0.1) running on battery,
JDK 28-ea+18. Differences under about 5% are within run-to-run noise on this machine.

## Known limits

- **Records are encoded through a generic field loop.** A record encoder generated for each
  type would be faster; this is planned as part of `derives`.
- **`Instant` and `UUID` values go through `String` formatting and parsing** in the JDK.
  Jackson does the same, so this limits how far ahead forj can get.
- **Encoding a large document into memory allocates more than it needs:** the writer's
  buffers grow by doubling up to 256 KB. This costs memory, not speed, in these benchmarks.

## What's measured

- **Operations:** encode (record → `byte[]`) and decode (`byte[]` → record), each through the
  library's usual API: `Json.encode`/`Json.decode` for forj,
  `ObjectMapper.writeValueAsBytes`/`readValue` for Jackson.
- **Fairness:** a test (`BenchEquivalenceTest`) checks that both libraries write
  byte-identical JSON for every data set and read each other's output.
- **Jackson:** 2.22.3 databind, set up the way most applications use it: the `java.time` and
  `Optional` modules, ISO-8601 instants, empty `Optional`s left out, unknown fields ignored.
- **Data** (`bench/.../Corpus.java`, generated from fixed seeds):

  | Data | What it is | Size |
  |---|---|---|
  | 1 user | one record: strings (some non-ASCII or escaped), numbers, an `Instant`, a `UUID`, a list, a map, an optional nested record | 225 B |
  | 1,000 users | a list of those records | 285 KB |
  | 10,000 points | a name and 10,000 `{lat, lon}` pairs | 510 KB |

- **JMH settings:** 1.37, throughput mode, 1 fork, 3 × 2 s warmup, 5 × 2 s measurement,
  `-Xms2g -Xmx2g`, with the GC profiler for memory per call. MB/s is 10⁶ bytes of JSON per
  second.

## Running them

```sh
bin/bench                           # everything; JSON results in out/bench-results/
bin/bench users_encode              # benchmarks matching a pattern
bin/bench users_encode -f 2 -i 8    # more forks and iterations, for tighter error bars
```

Run on mains power for numbers worth comparing.

## Not covered yet

- **jsoniter-scala,** the fastest JVM JSON library in most benchmarks.
- **Jackson 3** (3.2.3), which Spring Boot 4 uses.
- **forj's codecs on Jackson's parser** (the `json-jackson` adapter), so only the parser
  differs.
- **Streams:** decoding from an `InputStream` and encoding to an `OutputStream`, the way an
  HTTP server uses them.

---

How these numbers were reached, the experiments behind the known limits, and notes for
continuing the work: [benchmarks-for-agents.md](benchmarks-for-agents.md).
