# Changelog

All notable changes to `com.tricoredb:tricoredb-kotlin` are recorded here. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the library uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-09-16

First release.

### Added

- `TriCore`: the native `tricore` wire protocol over TCP or TLS, with HELLO feature
  negotiation and password authentication. Every call is a `suspend` function; the only
  dependency is `kotlinx-coroutines-core`.
- SQL: `query` and `execute` with `?` placeholders bound **by the server**, plus
  one-request scripts (`transaction { }`) and session transactions (`begin`, `commit`,
  `rollback`).
- `TriCorePool`: a bounded pool that lends a connection to a block and never returns one
  with a transaction still open.
- Families for documents, cache (keys, lists, sets, hashes, streams), vectors, graphs,
  LLM context export and the admin reads, reached as `db.document`, `db.cache`,
  `db.vector`, `db.graph`, `db.llm` and `db.admin`.
- Typed failures under `TriCoreException`, each carrying the server's own `code`;
  `isRedirect` and `leaderHint` for a `not_leader` refusal.
- TLS and mutual TLS through `TlsOptions`, including a caller-supplied `SSLContext`.

### Fixed

- JSON escaping wrote the four characters `000C` where a form feed was meant, so a value
  holding one was corrupted in both directions. The crate had never compiled with this
  in place.
- `CacheField.fieldText` read the property's own backing field instead of the `field`
  byte array, which did not compile.

### Security

- A call that needs a capability the server did not grant — server-side parameters,
  session transactions — throws `FeatureNotGrantedException` before anything is sent,
  rather than falling back to a weaker behaviour.
- Both directions enforce the protocol's frame ceilings, and a declared length is
  checked before a payload byte is read, so a wrong or hostile peer cannot make the
  client allocate what it claimed.
- A connection that timed out or lost frame alignment is closed rather than reused: a
  late reply can never be read as the answer to the next request.
- `TriCoreConfig.toString()` prints `secret=***`.

[Unreleased]: https://github.com/trinesh14/tricoredb-sdk-kotlin/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/trinesh14/tricoredb-sdk-kotlin/releases/tag/v0.1.0
