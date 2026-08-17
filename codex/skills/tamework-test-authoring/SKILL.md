---
name: tamework-test-authoring
description: Use when adding or maintaining Java tests for Tamework systems, ECS components, config parsing, config inheritance, bug fixes, regression coverage, thread-affinity rules, or executable behavior contracts. Distinguishes Java unit coverage from exact-profile NPC candidate and verification evidence.
---

# Tamework Test Authoring

Codify behavior expectations as repeatable tests.

## Workflow

1. Read `references/test-scenarios-checklist.md`.
2. Read `references/regression-template.md`.
3. Identify behavior contract and failure mode.
4. Write focused test fixtures for the changed path.
5. Add regression test for the reported bug pattern.
6. When expected behavior depends on base Hytale API/gamedata semantics, use `hytale-workshop-mcp` to ground the contract before writing assertions.
7. When the change also modifies NPC JSON, use `$hytale-asset-tools` to
   inspect actionable advisories, validate the exact-profile candidate with
   affected scope, and generate behavior verification. Java tests do not
   replace asset validation or live evidence.
8. Run test suite (or targeted tests) and report results.
9. For thread-affinity bug fixes, add a static safety check step in the report:
   - `rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java`
   - verify no unsafe usages remain in tick/runtime paths.
   - if async/deferred callbacks are part of the fix, verify they pass IDs (UUIDs) rather than captured `Player` component instances.

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
3. Include negative-path assertions for known failure modes.
4. Keep Workshop evidence in the test rationale, not as a runtime test dependency.
5. Do not encode a remembered builder/type inventory in tests. Resolve IDs from
   current registration and assert profile compatibility separately.
6. Do not label Java unit success as live NPC behavior proof.
