# Benchmarks: process, reasoning and history (for agents)

For AI agents and maintainers continuing performance work on forj-codec: how the benchmark
numbers were measured, the experiments and reasoning behind them, and how to continue.

The human-facing summary is [benchmarks.md](benchmarks.md); keep it to current state only (no
history or reasoning) and record process here.

Contents: rules for measuring · chronological log · the 1,000-users-encode investigation ·
hypothesis status · pitfalls found · raw data · how to continue.

## 1. Rules for measuring

- **Fairness invariant.** `bench/src/test/.../BenchEquivalenceTest` asserts forj and Jackson
  write byte-identical JSON for every corpus shape and decode each other's output. Run
  `./mill bench.test` after any change to codecs, the writer or the corpus. A speed claim from
  a patch that breaks equivalence is invalid.
- **Jackson configuration is fixed** in `Corpus.JACKSON`: `JavaTimeModule`, `Jdk8Module`,
  `WRITE_DATES_AS_TIMESTAMPS` off, `FAIL_ON_UNKNOWN_PROPERTIES` off, inclusion `NON_ABSENT`.
  It matches forj's output format; don't tune it (no Afterburner/Blackbird) without adding a
  separate row.
- **Noise.** On the M2 Pro laptop on battery: 1 fork × 5 iterations drifted up to about 4%
  between runs, and Jackson's error bars sometimes reached ±12%. Conclusions in this file use
  **2 forks × 8 iterations** (`-f 2 -i 8`), giving ±1–5%. Always measure Jackson in the same
  run and compare ratios (forj ÷ Jackson) as well as forj's own change against a same-session
  baseline. Prefer mains power.
- **Allocation** is `gc.alloc.rate.norm` from JMH's `-prof gc` (bytes per operation). It's
  exact and stable (errors under 0.1%).
- **Experiments are patch → measure → revert.** Save originals first (the investigation kept
  copies in `out/jfr/*.orig`), confirm `grep -rn EXPERIMENT json bench` is empty afterwards,
  and rerun `./mill __.test`.
- **No reflection** (a project anti-goal) applies to forj-codec code, including anything a
  performance fix introduces. Reflection-based libraries appear only as reference rows.

## 2. Chronological log

### Run 1 (2026-10-06): first version, no optimization

Setup as in benchmarks.md, 1 fork × 5 iterations, on battery. Corpus sizes: user 225 B,
users 285,170 B, route 510,092 B.

| Shape | Op | forj MB/s | Jackson MB/s | forj ÷ Jackson | forj KB/op | Jackson KB/op | Error (forj / Jackson) |
|---|---|---:|---:|---:|---:|---:|---|
| user | decode | 193 | 203 | 0.95× | 2.8 | 2.9 | ±1.9% / ±8.3% |
| user | encode | 464 | 445 | 1.04× | 1.0 | 1.0 | ±2.7% / ±1.2% |
| users | decode | 218 | 229 | 0.95× | 2,339 | 2,376 | ±1.2% / ±1.1% |
| users | encode | 407 | 453 | 0.90× | 1,715 | 938 | ±0.8% / ±0.5% |
| route | decode | 182 | 149 | 1.22× | 2,312 | 3,681 | ±1.1% / ±0.2% |
| route | encode | 296 | 340 | 0.87× | 3,233 | 3,102 | ±0.5% / ±1.0% |

Reading at the time: parity overall. forj led on number decoding because it parses doubles
from bytes, while Jackson builds a `String` per number by default. It trailed on large encodes.

Suspects listed from code reading (not profiled):
1. `Json.encode` went through `ByteArrayOutputStream`: copy on every growth plus a final copy.
2. Doubles written via `Double.toString`: a `String` per number.
3. `Instant`/`UUID` written and parsed through `String` (`DateTimeFormatter`,
   `UUID.fromString`).
4. Boxing: `Function<T, F>` getters when encoding, an `Object[]` of boxes for the
   constructor when decoding.
5. Field-name matching hashes each name.

### Fixes 1 and 2 (2026-10-06)

- **In-memory writer.** `JsonBackend.writer()` (no stream) and `JsonWriter.toByteArray()`.
  `ForjWriter` keeps each filled buffer as a segment and starts a new one sized
  `min(256 KB, max(current, total so far))`; `toByteArray()` copies all segments once.
  `Json.encode(value)` uses it. The TCK's `write` helper now writes every document to a
  stream and to memory and asserts identical bytes, plus `onlyInMemoryWritersHaveBytes`.
- **Doubles without `String`.** A per-writer reused `StringBuilder`; `append(double)` (JDK
  19+) runs the same shortest-digits algorithm as `Double.toString` straight into the
  builder, whose chars are copied into the buffer. Output is byte-identical (equivalence test
  passed). Considered and rejected: porting Schubfach/Ryu (≈300 lines plus tables, licensing
  questions for the JDK's GPL+CE copy) and using `jdk.internal.math` (needs `--add-exports`
  at runtime).

### Run 2 (2026-10-06): after fixes 1 and 2 (current numbers in benchmarks.md)

Same settings. Jackson's own numbers moved at most 3% from run 1 (noise).

| Shape | Op | forj MB/s (run 1) | Jackson MB/s | forj ÷ Jackson (run 1) | forj KB/op (run 1) | Jackson KB/op |
|---|---|---:|---:|---:|---:|---:|
| user | decode | 201 (193) | 198 | 1.02× (0.95×) | 2.8 (2.8) | 2.9 |
| user | encode | 472 (464) | 437 | 1.08× (1.04×) | 0.8 (1.0) | 1.0 |
| users | decode | 215 (218) | 223 | 0.96× (0.95×) | 2,339 (2,339) | 2,376 |
| users | encode | 433 (407) | 445 | 0.97× (0.90×) | 1,141 (1,715) | 938 |
| route | decode | 188 (182) | 150 | 1.25× (1.22×) | 2,312 (2,312) | 3,681 |
| route | encode | 446 (296) | 339 | 1.32× (0.87×) | 1,315 (3,233) | 3,102 |

Confirmed: suspect 2 was the route-encode cost (0.87× → 1.32×, allocation −60%). Suspect 1
cut users-encode allocation by a third and closed most of its gap (0.90× → 0.97×). Decode is
unchanged, as expected.

### json-jackson rows added (2026-10-22)

- Six benchmarks `*_forjOnJackson`: the same `Json.encode`/`Json.decode` calls as the `forj`
  rows, with `given JsonBackend backend = JacksonBackend.DEFAULT;` as the only difference. They
  separate parser cost from codec cost.
- Equivalence: `json-jackson`'s `SameBehaviourTest` checks both backends write identical bytes
  on a record covering every built-in kind (sealed, `Set`, `Either`, `Optional`, `Instant`,
  `UUID`, escapes, a non-BMP character, `Long.MIN_VALUE`). The adapter disables Jackson's
  uppercase hex escapes and enables `COMBINE_UNICODE_SURROGATES_IN_UTF8` for that.
- A smoke run (1 × 1 s, on battery at 16%) only checked the rows execute; not recorded.
- Adapter costs to keep in mind when reading the numbers: `nextField` looks up the interned
  name in a `HashMap` (`FieldNames.indexOf(String)`); `beginObject`/`beginArray` allocate one
  `JsonLocation` each (for container start offsets); every `value(String)` scans for
  surrogates; the in-memory writer uses a `ByteArrayOutputStream`.

### Run 3 (2026-10-22): mains power, with the json-jackson rows

`out/bench-results/codec-20261022-172453.json`. Same settings (1 fork × 5), **AC power**
(battery 18%, charging). Error bars 0.3–3.8%.

| Shape | Op | forj MB/s | forjOnJackson MB/s | Jackson MB/s | forj ÷ J | fOJ ÷ J | forj ÷ fOJ | KB/op forj / fOJ / J | forj, J vs run 2 |
|---|---|---:|---:|---:|---:|---:|---:|---|---|
| user | decode | 201 | 182 | 196 | 1.03 | 0.93 | 1.10 | 2.8 / 3.0 / 2.9 | 1.00, 0.99 |
| user | encode | 470 | 322 | 436 | 1.08 | 0.74 | 1.46 | 0.8 / 1.3 / 1.0 | 1.00, 1.00 |
| users | decode | 224 | 203 | 223 | 1.00 | 0.91 | 1.10 | 2,339 / 2,363 / 2,376 | 1.04, 1.00 |
| users | encode | 424 | 332 | 436 | 0.97 | 0.76 | 1.28 | 1,141 / 1,716 / 938 | 0.98, 0.98 |
| route | decode | 190 | 149 | 163 | 1.17 | 0.92 | 1.28 | 2,312 / 3,291 / 3,135 | 1.01, 1.08 |
| route | encode | 449 | 297 | 336 | 1.33 | 0.88 | 1.51 | 1,315 / 4,202 / 3,102 | 1.01, 0.99 |

Findings:
- **Run 2 (battery) reproduced on mains:** forj and Jackson within 0–4% of run 2, except
  Jackson route decode +8% (so that ratio is 1.17× now, not 1.25×). Battery wasn't distorting
  the earlier results much on this machine.
- **forj's backend vs Jackson's parser under identical codecs: 1.10–1.51×.** Larger on encode
  (1.28–1.51×) than decode (1.10–1.28×).
- **The adapter is 0.74–0.93× Jackson databind,** although both use jackson-core. So
  `forj ÷ fOJ` overstates the parser difference: it includes adapter overhead.

Untested hypotheses for the adapter's overhead (profile `*_forjOnJackson` before acting):
1. **Encoding into `ByteArrayOutputStream`** instead of Jackson's `ByteArrayBuilder` with
   recycled segments: users encode allocates 1,716 KB vs databind's 938 KB.
2. **Doubles:** `JsonGenerator.writeNumber(double)` goes through `NumberOutput.toString`, a
   `String` per number, like forj before fix 2. Route encode allocates 4,202 KB.
   (Databind pays the same, so this alone doesn't explain fOJ < J.)
3. **The surrogate scan** on every `value(String)`, and the structure checks duplicated with
   Jackson's own.
4. **Decode:** `nextField` is a `HashMap` lookup on the interned name, plus a `JsonLocation`
   per object for start offsets; databind uses its own property lookup.

## 3. Investigation: why encoding 1,000 users isn't faster (2026-10-06)

**Question:** after run 2, `users_encode` was level with Jackson (0.97×) while number-heavy
shapes led by 25–32%. Why no lead on string-heavy records?

**Method:** a loop of architect (propose hypotheses from evidence), critic (challenge them
against data, demand experiments) and judge (rank the top 3) passes, until two consecutive
rounds produced the same top 3. Converged after round 3.

### Evidence: JFR profiles (round 1)

`bin/bench`-equivalent run with `-prof "jfr:dir=out/jfr;configName=profile"` on
`users_encode`, aggregated with a script (`out/jfr/agg.py`: `jfr print --json`, self and
inclusive CPU per method, allocation by type and by top-3-frame site).

forj, about 785 CPU samples:
- self: `ForjWriter.writeString` 35.9%, `ProductCodec.encode` 11.0%, `String.charAt` 6.5%,
  `DateTimePrintContext.getValue` 6.2%, `ForjWriter.value` 5.6%.
- inclusive: `Instant.toString`/`DateTimeFormatter.format` 12.7%.
- allocation by site: `ForjWriter.flushBuffer` (new segments) 33%, `toByteArray` 25%,
  `DateTimeFormatter` (StringBuilder + String + print context) about 23%, getter path
  (`ProductCodec.encode` line 27, `field.getter().apply`) about 11%, `Map` entry iteration
  (`KeyValueHolder`, iterators) about 10%.

Jackson, about 791 CPU samples:
- self: `UTF8JsonGenerator._writeStringSegment` 18.0%, `writeFieldName` 7.3%,
  `DateTimePrintContext.getValue` 7.1%, `BeanPropertyWriter.serializeAsField` 5.4%.
- inclusive: `writeString` 30.6%.
- allocation: `ByteArrayBuilder.toByteArray` 22.7%, `ByteArrayBuilder._allocMore` 20.5%,
  `UUIDSerializer` 8.9%, `Double.toString` 6.9% (Jackson does allocate per double), the same
  `DateTimeFormatter` path, `Map` iteration.

Profile-reading caveats:
- JFR's `ObjectAllocationSample.objectClass` labels a sample's whole weight with whichever
  object triggered it. Samples at the getter line were labelled `java.util.Optional`, which
  the code doesn't allocate there. Trust the allocation **site**, not the type label.
- `jdk.internal.reflect.DirectMethodHandleAccessor.invoke` appeared under about 80% of
  samples. It's JMH's `BenchmarkHandler$BenchmarkTask.call` starting each iteration with
  `Method.invoke`, not reflection in forj-codec.

### Rounds

**Round 1.**
- Architect: (1) segment growth over-allocates, (2) per-character overhead in `writeString`,
  (3) `Instant` via `DateTimeFormatter`, (4) boxing via `Function` getters, (5) megamorphic
  `codec.encode` in `ProductCodec`, (6) `Map` entry allocation.
- Critic: 1 explains allocation but zeroing/copying didn't show as CPU; 2 is the strongest
  CPU signal; 3 and 6 are shared with Jackson; 4 and 5 are comparable to Jackson's own
  dispatch; reflection refuted.
- Judge: 2, 1, 3. Untested; experiments required.

**Round 2.** Experiments, each patched, verified for byte-identical output, measured, and
reverted:

| Experiment | Change | forj ops/s | Jackson ops/s | Result |
|---|---|---:|---:|---|
| Baseline (1 fork × 5) | none | 1,536 ± 51 | 1,562 ± 141 | 0.98× |
| E1 (1 fork × 5) | in-memory segments fixed at 16 KB | 1,509 ± 98 | 1,542 ± 286 | allocation 1,168 → **932 KB/op**, speed unchanged |
| E2 (1 fork × 5) | `writeString`: `getChars` into a reused `char[]`, one space check per chunk of `(room / 6)` chars | 1,594 ± 76 | 1,609 ± 14 | +3.8% vs Jackson +3.0%: **no relative change** |
| E3 (1 fork × 5) | `Instant` written by direct date arithmetic (`LocalDate.ofEpochDay`, 20-byte array, Latin-1 `String`), falling back to `toString` for non-zero nanos or years outside 1970–9999 | 1,611 ± 19 | 1,516 ± 185 | +4.9% on forj's own baseline |
| Baseline (2 forks × 8) | none | 1,508 ± 14 | 1,582 ± 16 | 0.95× |
| E4 (2 forks × 8) | hand-specialized `User` encoder: direct accessors, typed `value(long/double/boolean)`, pre-built `FieldNames`, inline loops for tags/address/scores | 1,659 ± 84 | 1,564 ± 41 | **+10%, 1.06×**; allocation 1,087 KB/op |

- Architect (revised): A) string encoding is the same work in both libraries (E2); B) shared
  JDK formatting costs cap both (E3); C) generic per-field dispatch (tested by E4); D)
  segments are memory-only (E1).
- Judge: C, B, A.

**Round 3.** Test whether C and B are independent:

| Experiment | forj ops/s | Jackson ops/s | Result |
|---|---:|---:|---|
| E5 = E1 + E3 + E4 (2 × 8) | 1,946 ± 69 | 1,520 ± 13 | +29%, 1.28×; 811 KB/op |
| E6 = E3 + E4 (2 × 8) | 1,960 ± 35 | 1,528 ± 30 | **+30%, 1.28×**; 1,047 KB/op |

Super-additive: +10% (E4) and +5% (E3) combine to +30%. Interpretation: in the generic
`ProductCodec` loop the `codec.encode` call site is megamorphic (ten codec classes), so the
JIT can't inline the component codecs, and escape analysis can't remove their temporaries
(here E3's `LocalDate` and 20-byte array). Specialized code inlines them. E1 adds nothing to
speed (E5 ≈ E6).

Judge: same top 3 as round 2. **Converged.**

### Conclusions

1. **Generic per-field dispatch in `ProductCodec`** (`Function` getters boxing primitives; a
   megamorphic `codec.encode`): +10% alone, and it blocks inlining and escape analysis of
   component codecs. Implication: `derives` should generate a specialized encoder (direct
   accessor calls, typed writes, monomorphic calls into component codecs), not a
   `Codec.product(...)` call. The same likely applies to decoding (see section 4).
2. **`Instant` via `DateTimeFormatter`** (12.7% CPU, about 23% of allocation): shared with
   Jackson, so it caps the lead. Full benefit needs a `JsonWriter` method that writes ASCII
   from bytes or chars without a `String`. `UUID.toString` is probably similar.
3. **The rest is equivalent work:** about 40% of time is UTF-8 encoding of short strings, and
   Jackson's is equally tight (pre-encoded field names via `SerializedString`, ASCII loops).
   E2 showed nothing to gain from per-character overhead.

Ruled out for speed: segment over-allocation (E1; memory only, single-threaded; may matter
under concurrent load or GC pressure) and reflection (JMH frames).

## 4. Hypothesis status

| Hypothesis | Status | Evidence |
|---|---|---|
| `ByteArrayOutputStream` double copy in `Json.encode` | **Confirmed, fixed** | run 1 → run 2 |
| `Double.toString` per number when encoding | **Confirmed, fixed** | run 1 → run 2 |
| Generic per-field dispatch / boxing when encoding | **Confirmed, open** (+10%, +30% with `Instant` fix) | E4, E6 |
| `Instant` formatting via `DateTimeFormatter` | **Confirmed, open** (+5% alone) | E3, E6 |
| Segment growth over-allocation | **Confirmed for memory, refuted for speed** | E1, E5 vs E6 |
| Per-character overhead in `writeString` | **Refuted** | E2 |
| Reflection in the hot path | **Refuted** (JMH's own frames) | JFR stacks |
| `UUID.toString`/`UUID.fromString` cost | Untested; likely like `Instant` | — |
| Decode: `Object[]` of boxes for the constructor | Untested | — |
| Decode: `Instant.parse`/`UUID.fromString` | Untested | — |
| Field-name matching by hash (decode) | Untested | — |
| `Map` entry iteration allocation | Shared with Jackson; not pursued | JFR |

## 5. Pitfalls found while benchmarking

- **Annotation processors break forj syntax.** With JMH's `jmh-generator-annprocess` on the
  classpath, javac re-parsed sources without forj's parser and failed on `given`. Fix used:
  `bench` compiles with `-proc:none`; `bench.harness` runs
  `org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator` (from `bench.generator`) over the
  compiled classes and compiles the generated plain Java. Logged in forj's `TODO.md`.
- **JMH forks with `java.home`'s `java`, which lacks `--enable-preview`.** `bin/bench` passes
  `-jvm .jdk/preview/bin/java`, and `@Fork(jvmArgsAppend = "--enable-preview")` is on the
  benchmark class.
- **A local variable named `forj` shadows the `forj` package** in code the plugin inserts
  (`forj.json.Codec...`). Don't name locals after top-level packages. Logged in forj's
  `TODO.md`.
- **Class-initialization cycle:** an interface with default methods is initialized when an
  implementing class is. `JsonBackend`'s `given forj = ForjBackend.DEFAULT` then read `null`
  if `ForjBackend` loaded first. `JsonBackend` has no default methods for this reason.
- **Tiny test buffers** (16 bytes, used by `SmallBufferTest`) can't hold a formatted long or
  double; the writer falls back to `writeAscii` there. Keep that fallback in any rewrite of
  number writing.
- **Mill output splits `printf` lines** at each format argument; join the lines when parsing.

## 6. Raw data

Under `out/`, which is gitignored and may be gone (`./mill clean` deletes it):
- `out/bench-results/codec-*.json`: JMH JSON for each `bin/bench` run (run 1:
  `codec-20261006-163506.json`, run 2: `codec-20261006-164923.json`).
- `out/jfr/`: the JFR recordings, `agg.py`, `run.sh` (quick `users_encode` runner with
  `-prof gc`), and the `.orig` copies used to revert experiments.

## 7. How to continue

Ordered by measured payoff:
1. **Specialized generated encoders** (part of `derives` in forj's plugin), then measure with
   2 forks × 8. Expect about +10% alone on `users_encode`.
2. **A raw-ASCII write method on `JsonWriter`** plus direct `Instant` (and `UUID`) writing.
   Expect about +20% on top of 1.
3. **Fixed-size or pooled in-memory segments** for memory (−236 KB/op on `users_encode`).
   Check under concurrent load whether it also affects speed.
4. **Decode side:** profile `users_decode` the same way, then test the untested decode
   hypotheses.

5. **json-jackson adapter overhead** (run 3 hypotheses), if the adapter's speed matters.

Before comparing new numbers with the tables here, rerun the baseline in the same session.
After each change, update the current-results table in benchmarks.md (current state only)
and append to the log in section 2.
