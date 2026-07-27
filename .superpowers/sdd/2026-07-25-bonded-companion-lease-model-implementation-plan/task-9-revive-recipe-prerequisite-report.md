# Task 9 prerequisite report: ordered bonded revive recipes

## Status

Implementation and local verification complete at `4b39b1d7`; fresh
exact-range review is pending.

## Integration

- Transplanted the accepted commits `414ce168..44e3db0a` onto routed head
  `b3940ff6` as `e215e136..cd9aba35`.
- Committed the reconciliation extraction and focused test split as
  `4b39b1d7`.
- The two ranges touched no common paths, so the routing authority changes were
  retained without conflict.
- Preserved ordered `ReviveCosts`, independent family recipes, per-line quote
  quantities, deterministic full-recipe proof, atomic multi-stack escrow,
  terminal verification, and restart recovery.
- Added no migration or SQLite schema revision and did not touch HyDragon.

## Reconciliation RED -> GREEN

- RED command:
  `./mvnw test -Dtest=*BondedCompanion*Test,TwBondedCompanionRosterConfigTest,CommandReviveCostPresentationTest,LinkedNpcPanelFeatureControllerTest,CommandLinkedPanelEntryStateAuthorityTest,LinkedNpcPanelCardLayoutTest`
- RED result: 335 tests, 1 failure. The architecture gate reported
  `BondedCompanionTransitionService.java has 516 lines; bonded domain ceiling is 500`.
- GREEN correction: extracted immutable ordered-recipe validation, equality,
  and length-prefixed fingerprint authoring into
  `BondedCompanionReviveRecipe`. The transition authority is now exactly 500
  lines and the collaborator is 41 lines.
- GREEN result: the same broad command passed 335/335.
- Refactor: moved five multi-item production-boundary scenarios from the
  existing large escrow suite into the focused
  `HytaleBondedCompanionMultiItemEscrowTest`; the combined behavior count is
  unchanged.

## Verification

- `./mvnw -DskipTests compile`: PASS.
- Config/policy/state/UI selector: 37 tests, 0 failures, 0 errors.
- API/lifecycle/escrow/recovery selector: 74 tests, 0 failures, 0 errors.
- Broad bonded/config/UI selector: 335 tests, 0 failures, 0 errors.
- Extracted recipe and focused escrow rerun: 52 tests, 0 failures, 0 errors.
- ECS, async, bonded boundary, and payment-recovery guards: 8 tests, 0
  failures, 0 errors.
- Required player-access/thread grep: no matches.
- `git diff --check`: PASS.
- No Maven or helper process remained after verification.

## Known untouched baseline

The repository-wide suite previously documented four unrelated failures in
capture architecture, capture-channel VFX, relocation recovery, and stacked
spawner validation. This bounded integration did not touch those paths and did
not rerun the entire repository suite.
