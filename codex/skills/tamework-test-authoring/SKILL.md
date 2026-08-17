---
name: tamework-test-authoring
description: Use when a Tamework change has passed the test-value gate and needs a focused Java behavior regression, config parser or resolver test, ECS behavior test, bug reproduction, or narrow static guard for a documented high-impact invariant. Distinguishes Java tests from asset and live evidence.
---

# Tamework Test Authoring

Codify behavior expectations as repeatable tests.

## Workflow

1. Read `references/test-scenarios-checklist.md`.
2. Read `references/regression-template.md`.
3. Name the exact production regression and observable result. If the test only
   proves source shape, registration, declaration order, or file/asset
   presence, do not add it.
4. Write the smallest fixture that invokes production behavior.
5. Assert a returned value, persisted state, emitted effect/event,
   authorization decision, recovery outcome, or user-visible output.
6. When expected behavior depends on base Hytale API/gamedata semantics, use `hytale-workshop-mcp` to ground the contract before writing assertions.
7. When the change also modifies NPC JSON, use `$hytale-asset-tools` to
   inspect actionable advisories, validate the exact-profile candidate with
   affected scope, and generate behavior verification. Java tests do not
   replace asset validation or live evidence.
8. Run test suite (or targeted tests) and report results.
9. For runtime, tick, ECS, async, or thread-affinity tests, also use
   `$tamework-runtime-safety` and run its current guard checks.

## Output Contract

Return:
- Tests added/updated.
- Behavior contract covered.
- Command used to run tests and pass/fail status.
- For asset-coupled changes: profile/knowledge/snapshot identity, candidate
  result, and static/live verification gaps.

## Guardrails

1. Prefer deterministic tests over timing-sensitive tests.
2. Keep tests close to changed subsystem.
3. Include a negative path only when it protects a distinct known failure
   mode.
4. Keep Workshop evidence in the test rationale, not as a runtime test dependency.
5. Do not encode a remembered builder/type inventory in tests. Resolve IDs from
   current registration and assert profile compatibility separately.
6. Do not label Java unit success as live NPC behavior proof.
7. Static source guards are allowed only for a documented, high-impact safety
   invariant that cannot be exercised reliably at runtime. Keep them narrow.
