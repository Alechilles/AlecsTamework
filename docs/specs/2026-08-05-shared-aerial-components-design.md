# Shared Aerial Components Design

## Goal

Move three reusable autonomous-aerial NPC behaviors into Tamework so HyDragon,
Animal Husbandry, and future downstream mods can consume stable shared
components without copying their instruction graphs.

This change does not consolidate aerial combat. Combat remains a separate,
later refactor.

## Scope

Tamework will own these stable component IDs:

- `Component_Tamework_Instruction_Hold_Flying`
- `Component_Tamework_Instruction_SeekFood_PlayerFollow_Flying`
- `Component_Tamework_Instruction_Airborne_Mode_Transition`

Tamework remains version `3.0.0`. Existing downstream Tamework dependency
ranges remain unchanged.

## Flying Hold Contract

The shared flying Hold component will use the active Animal Husbandry behavior
as its behavioral baseline. Entering Hold releases the combat target and resets
the grounded-pose flag. While the Tamework flying-companion landing controller
owns descent, the instruction contributes no competing body motion. After the
NPC switches to the walk controller, it clears landing rays, applies the
configurable grounded animation once, and remains stationary.

The component retains these public parameters:

- `HoldGroundAnimation`
- `HoldLandingSearchRange`
- `HoldLandingSearchAngle`
- `HoldLandingSlowDownDistance`
- `HoldLandingStopDistance`
- `HoldLandingGoalLenience`

The landing parameters remain available for compatibility even though descent
is currently owned by Tamework's targeted landing controller. Removing them
would unnecessarily break existing downstream `Modify` blocks.

Animal Husbandry will replace its local component with the Tamework reference.
HyDragon's unreferenced local flying Hold component will be deleted; its active
roles continue using the existing grounded Hold component unless explicitly
wired to flying Hold in a later behavior change.

## Flying Favorite-Item Follow Contract

The shared flying favorite-item component will be the aerial counterpart to
`Component_Tamework_Instruction_SeekFood_PlayerFollow`. It will preserve the
current Animal Husbandry state machine:

1. Track a non-hostile player holding an attractive item.
2. Ignore that target for avoidance.
3. Take off when pursuit begins on the ground.
4. Seek the target while outside the landing approach radius.
5. Store a nearby landing position and land safely.
6. Approach on foot after touchdown.
7. Release the target and return to the imported idle state when the item is
   lost, without interrupting a landing in progress.

The component will retain public parameters for the attractive item set,
target slot, landing-position slot, flying stop distance, and grounded approach
distance. Animal Husbandry will provide its existing values through `Modify`
where they differ from the shared defaults.

The public interface becomes `Tamework.Instruction.SeekFood.PlayerFollow.Flying`.
The imported parent state remains configurable rather than embedding an
Animal Husbandry role name.

## Airborne-Mode Transition Contract

One parameterized Tamework component will replace the two Animal Husbandry
transition components and the HyDragon transition component. It will:

- neutralize stale Tamework landing mode on entry;
- consume a configurable `TameworkHook` ID;
- toggle a configurable airborne flag;
- take off when airborne mode is enabled;
- find a safe landing position and land when airborne mode is disabled; and
- clear the configurable landing ray after touchdown.

The public parameters will cover at least:

- hook ID;
- airborne flag name;
- grounded-activity gate flag name;
- landing-ray name;
- landing block set;
- takeoff jump speed;
- landing range, angle, slowdown distance, stop distance, height difference,
  goal lenience, and desired-altitude weight.

The grounded-activity gate is always present. Consumers without a husbandry
activity gate will supply a private flag name that they never set, making the
`Set: false` condition true without introducing a second instruction graph.
Animal Husbandry keeps `AerialGroundedActivity`; HyDragon and the Frost Dragon
use isolated unused gate names.

Consumer-specific hook IDs and flag/ray names remain downstream configuration.
No HyDragon or Animal Husbandry command IDs become Tamework defaults or public
Tamework policy.

## Downstream Ownership

Downstream mods continue to own:

- command hook registration and hook IDs;
- role state placement and instruction ordering;
- attractive item sets and species movement tuning;
- husbandry activity flags;
- species-specific wrappers and `Modify` blocks; and
- all aerial combat, attacks, talents, and cooldown policy.

The migrations must not alter mounted/rider-controlled flight, command state,
combat behavior, or species-specific follow tuning.

## Compatibility and Failure Handling

The old downstream component files are removed only after all references are
rewired. Contract tests or repository verifiers will reject stale local IDs,
missing Tamework references, lost `Modify` values, duplicate component IDs, and
unintended dependency/version changes.

Existing Tamework component IDs are not renamed. The three new IDs are public
asset API and must be documented as such.

## Verification

Verification will include:

- JSON parsing for every changed component and role/template;
- Tamework asset-contract tests for the three public components and their
  required parameters/instruction stages;
- HyDragon checks proving local copies are absent and transition references
  preserve hook/flag/ray configuration;
- Animal Husbandry's complete flying-companion verifier, extended to prove the
  shared references and preserved favorite-item, Hold, generic-aerial, and
  Frost Dragon configuration;
- stale-reference and duplicate-definition searches across all three repos;
- exact-profile Hytale asset validation where the current project profile can
  resolve the cross-mod candidate graph; and
- the available full project test suites, with unrelated baseline failures
  reported separately.
