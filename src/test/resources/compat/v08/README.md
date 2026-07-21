# API 0.8 binary fixture

The `.class` files in this directory were compiled with JDK 25 against Tamework commit
`c0f83f20` (public API 0.8), before the API 0.9 additive interface changes in `7c638696`.
They are deliberately checked in as binary test fixtures: Maven does not compile the matching
source under `src/test/fixtures/api-v08-src`.

To reproduce the fixture, compile `Pre09ApiBinaryFixture.java` with the API 0.8 build output on
the class path, then copy the emitted package tree beneath this directory. The API 0.9 test loads
these exact binaries and verifies both directions of the compatibility contract:

- API 0.8 `invokeinterface` call sites still link to API 0.9 interfaces.
- API 0.8 implementors link under API 0.9 and inherit the new default fallbacks.
