---
title: docs
---

# forj-codec guides

API reference: [api](https://critium.github.io/forj-codec/api/),
[jackson](https://critium.github.io/forj-codec/jackson/),
[tck](https://critium.github.io/forj-codec/tck/).

- **[Using forj-codec](guide.md)**: for Java developers using forj-codec: how to turn Java
  values into JSON and back, covering records, collections, sealed types, custom codecs,
  choosing codecs with givens, errors, streams and effects, and backends.
- **[Benchmarks](benchmarks.md)**: for anyone choosing a JSON library: how fast forj-codec is
  compared with Jackson databind on the same records and the same bytes, how much memory each
  uses, and where forj-codec's limits are.

<small>For agents and maintainers continuing performance work:
[how the benchmarks were measured, and the reasoning behind them](benchmarks-for-agents.md).</small>
