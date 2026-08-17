---
name: tamework-api-evolution
description: Use when adding, changing, deprecating, or removing a Tamework public API interface, method, DTO, result/status, capability, event, API version, adapter, self-test, recipe, or downstream integration contract. Also use before adding a convenience method that may already be expressible through the current API.
---

# Tamework API Evolution

Treat every public symbol as a compatibility contract with unknown downstream
implementations and consumers.

## Discover the Existing Contract

1. Read `references/api-change-matrix.md`.
2. Search interfaces, implementations, replacement/degraded adapters, tests,
   self-tests, docs, recipes, and sibling mods for the requested behavior.
3. Prefer composition through an existing method when it preserves the same
   validation, authority, threading, status, and event semantics.
4. Identify the owning sub-API. Do not add a domain method to root
   `TameworkApi` only because that interface is easy to find.

## Design for Compatibility

- Adding an abstract interface method can break external implementers. Prefer a
  safe default method or a new capability-gated sub-interface when old
  implementations need to keep working.
- Use the existing result and status vocabulary. Do not replace explicit
  invalid, missing, unloaded, unsupported, conflict, or error outcomes with a
  boolean or null.
- Add a capability when clients must distinguish runtime support. Update every
  full, replacement, degraded, and test implementation.
- Add or change an event only when consumers need an observable semantic
  transition. Define ordering, payload stability, and failure behavior.
- Keep API implementation classes as adapters. Put new domain logic in a
  focused service, especially when `TameworkApiImpl` is already oversized.
- Change the public API version and mod version only through the intentional
  release workflow. Document compatibility and migration impact.

## Route Related Work

- Use `$mod-integration-bridge` when another mod consumes the existing API or
  when adapting an external integration.
- Use `$tamework-companion-progression` for progression rules behind an API.
- Use `$tamework-persistence` for durable mutations, idempotency, snapshots,
  or recovery semantics.
- Use `$tamework-runtime-safety` for world-thread and ECS mutation boundaries.
- Use `$hytale-docs-sync` for broad README/wiki/changelog synchronization.

## Verify the Contract

1. Test observable behavior through the public interface, including at least
   one failure status that the change can regress.
2. Update the API self-test when the contract is suitable for a live fixture.
3. Compile and test Tamework plus available sibling/downstream consumers.
4. Update the public reference, recipe when useful, capability guidance,
   compatibility note, and changelog.
5. Do not add tests that only assert method presence, enum membership, or
   implementation structure.
6. Report source/API versions, compatibility strategy, capabilities, adapters,
   results, events, self-test coverage, downstream checks, and evidence gaps.
