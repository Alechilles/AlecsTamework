# Shared Aerial Components Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish reusable flying Hold, flying favorite-item follow, and airborne-mode transition components from Tamework and migrate HyDragon and Animal Husbandry without changing behavior.

**Architecture:** Tamework owns three complete parameterized instruction graphs. Downstream roles consume them by `Reference` and preserve hook IDs, state flags, landing-ray names, item sets, and movement tuning through `Modify`; species combat remains local.

**Tech Stack:** Hytale NPC JSON assets, Python contract verifiers, Gradle/JUnit validation, Git worktrees.

## Global Constraints

- Tamework remains version `3.0.0`.
- HyDragon keeps `Alechilles:Alec's Tamework!` at `>=3.0.0 <4.0.0`.
- Animal Husbandry keeps `Alechilles:Alec's Tamework!` at `>=3.0 <4.0`.
- Do not change aerial combat, mounted/rider flight, command ordering, or species follow tuning.
- Remove downstream structural copies only after every reference is migrated.
- Keep downstream hook IDs and husbandry flags out of Tamework defaults.

---

### Task 1: Publish the Tamework component API

**Files:**
- Create: `scripts/tools/verify-shared-aerial-components.py`
- Create: `src/main/resources/Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Hold_Flying.json`
- Create: `src/main/resources/Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_SeekFood_PlayerFollow_Flying.json`
- Create: `src/main/resources/Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Airborne_Mode_Transition.json`
- Modify: `docs/Actions-Sensors-Components.md`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `TameworkSetFlyingCompanionMode`, `TameworkHook`, standard motion controllers, and the established Tamework component parameter/`Modify` convention.
- Produces: the three stable component IDs named in the design spec.

- [ ] **Step 1: Write the failing Tamework verifier**

Create a Python verifier that loads the three expected files and asserts:

```python
EXPECTED = {
    "Component_Tamework_Instruction_Hold_Flying.json": "Tamework.Instruction.Hold",
    "Component_Tamework_Instruction_SeekFood_PlayerFollow_Flying.json":
        "Tamework.Instruction.SeekFood.PlayerFollow.Flying",
    "Component_Tamework_Instruction_Airborne_Mode_Transition.json":
        "Tamework.Instruction.AirborneModeTransition",
}
```

It must also assert the manifest version is `3.0.0`, required parameter names
exist, transition hook/flag/ray fields use `Compute`, favorite-item exits use
`ReturnParentState`, Hold does not issue a competing airborne `Land` motion,
and serialized shared assets contain none of `HyDragon`, `AnimalHusbandry`, or
`AH_`.

- [ ] **Step 2: Verify RED**

Run:

```bash
python scripts/tools/verify-shared-aerial-components.py
```

Expected: failure because all three shared files are absent.

- [ ] **Step 3: Add the minimal shared components**

Copy the active Animal Husbandry flying Hold and flying favorite-item graphs,
rename their comments/interfaces generically, and add `ReturnParentState` with
`ParentState.State: {"Compute": "ReturnParentState"}`. Build the transition
from the generic Animal Husbandry graph with these parameters:

```text
ToggleAirborneModeHookId
AirborneModeFlagName
GroundedActivityFlagName
LandingRayName
LandingBlocks
TakeOffJumpSpeed
LandingSearchRange
LandingSearchAngle
LandingSlowDownDistance
LandingStopDistance
LandingHeightDifference
LandingGoalLenience
LandingDesiredAltitudeWeight
```

Use generic Tamework defaults for all string names and parameterize every
consumer-specific hook, flag, and ray field.

- [ ] **Step 4: Verify GREEN and parse JSON**

Run:

```bash
python scripts/tools/verify-shared-aerial-components.py
python -m json.tool src/main/resources/Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Hold_Flying.json >/dev/null
python -m json.tool src/main/resources/Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_SeekFood_PlayerFollow_Flying.json >/dev/null
python -m json.tool src/main/resources/Server/NPC/Roles/_Core/Components/Component_Tamework_Instruction_Airborne_Mode_Transition.json >/dev/null
bash gradlew compileJava processResources
```

Expected: verifier and JSON parsing pass; production compilation succeeds.

- [ ] **Step 5: Document and commit the public API**

Document all three IDs, parameters, and `Reference`/`Modify` examples. Add the
features under Tamework `3.0.0` in the changelog. Run `git diff --check`, then:

```bash
git add scripts/tools/verify-shared-aerial-components.py \
  src/main/resources/Server/NPC/Roles/_Core/Components \
  docs/Actions-Sensors-Components.md CHANGELOG.md
git commit -m "Feat: publish shared aerial components"
```

---

### Task 2: Migrate HyDragon

**Files:**
- Modify: `scripts/validate_assets.py`
- Modify: `scripts/tests/test_validate_assets.py`
- Modify: `src/main/resources/Server/NPC/Roles/Creature/HyDragon/Templates/Template_HyDragon_Dragon_Tamed.json`
- Modify: `src/main/resources/Server/NPC/Roles/Creature/HyDragon/Templates/Template_Wyvern_Mini_Flying_Tamed.json`
- Delete: `src/main/resources/Server/NPC/Roles/Creature/HyDragon/Components/Component_HyDragon_Instruction_Airborne_Mode_Transition.json`
- Delete: `src/main/resources/Server/NPC/Roles/Creature/HyDragon/Components/Component_Tamework_Instruction_Hold_Flying.json`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: `Component_Tamework_Instruction_Airborne_Mode_Transition` from Task 1.
- Produces: a HyDragon role with the same hook, `AirborneMode` flag, landing ray, and landing tuning but no local transition/Hold copies.

- [ ] **Step 1: Write failing validator assertions**

Add `validate_shared_aerial_component_wiring(parsed, errors)` and a unittest
that asserts it rejects local transition/Hold files and rejects a full-dragon
transition reference that is not the Tamework ID or lacks these `Modify` keys:

```text
ToggleAirborneModeHookId = HyDragon.Command.ToggleAirborneMode
AirborneModeFlagName = AirborneMode
GroundedActivityFlagName = HyDragon_AirborneMode_UnusedGroundedActivity
LandingRayName = LandingRay
```

- [ ] **Step 2: Verify RED**

Run:

```bash
python -m unittest scripts.tests.test_validate_assets.ValidatorContractTest.test_rejects_local_shared_aerial_components
```

Expected: failure because the validator function or migrated wiring is absent.

- [ ] **Step 3: Rewire and remove local copies**

Replace both active local airborne transition references with the Tamework
component and the exact `Modify` values above. Preserve all numeric landing
defaults. Delete both local files; do not wire the orphaned flying Hold into
active roles.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
python -m unittest scripts.tests.test_validate_assets
python scripts/validate_assets.py
bash gradlew test
```

Expected: all HyDragon validator and Gradle tests pass.

- [ ] **Step 5: Commit HyDragon**

Update the changelog, run `git diff --check`, then:

```bash
git add -A
git commit -m "Refactor: consume shared aerial components"
```

---

### Task 3: Migrate Animal Husbandry

**Files:**
- Modify: `tools/verify_flying_companions.py`
- Modify: `Server/NPC/Roles/_Core/Templates/AH_Template_Aerial_Neutral.json`
- Modify: `Server/NPC/Roles/_Core/Templates/AH_Template_Aerial_Tamed.json`
- Modify: `Server/NPC/Roles/_Core/Templates/AH_Template_Dragon_Frost_Tamed.json`
- Delete: `Server/NPC/Roles/_Core/Components/AH_Component_Tamework_Instruction_Aerial_Follow_Item.json`
- Delete: `Server/NPC/Roles/_Core/Components/AH_Component_Tamework_Instruction_Aerial_Mode_Transition.json`
- Delete: `Server/NPC/Roles/_Core/Components/AH_Component_Tamework_Instruction_Hold_Flying.json`
- Delete: `Server/NPC/Roles/Creature/Mythic/Components/Component_AH_Instruction_Dragon_Frost_Airborne_Mode_Transition.json`
- Modify: `CHANGELOG.md`

**Interfaces:**
- Consumes: all three Task 1 components.
- Produces: generic aerial and Frost Dragon roles that preserve their item sets, states, hooks, activity gates, landing rays, and movement values through `Modify`.

- [ ] **Step 1: Extend the verifier first**

Require all four local files to be absent and assert the shared references. For
generic aerial roles require:

```text
Hold -> Component_Tamework_Instruction_Hold_Flying
Favorite item -> Component_Tamework_Instruction_SeekFood_PlayerFollow_Flying
Transition hook -> AnimalHusbandry.Command.ToggleAirborneMode
Grounded activity flag -> AerialGroundedActivity
Landing ray -> AH_Aerial_Mode_LandingRay
```

For Frost Dragon require:

```text
Transition hook -> AnimalHusbandry.Command.ToggleFrostDragonAirborneMode
Airborne flag -> AirborneMode
Unused grounded gate -> AH_Dragon_Frost_UnusedGroundedActivity
Landing ray -> LandingRay
```

Also assert existing favorite-item slots/distances and landing numeric values.

- [ ] **Step 2: Verify RED**

Run:

```bash
python tools/verify_flying_companions.py --scope all
```

Expected: failure because local files/references still exist.

- [ ] **Step 3: Rewire all consumers and delete copies**

Replace local references with the three Tamework IDs. Add explicit `Modify`
objects for every value listed in Step 1 and every species value that differs
from the shared defaults. Delete the four downstream component files.

- [ ] **Step 4: Verify GREEN**

Run:

```bash
python tools/verify_flying_companions.py --scope all
python -m compileall -q tools/verify_flying_companions.py
```

Expected: complete verifier passes.

- [ ] **Step 5: Commit Animal Husbandry**

Update the changelog, run JSON parsing and `git diff --check`, then:

```bash
git add -A
git commit -m "Refactor: consume shared aerial components"
```

---

### Task 4: Cross-repository verification

**Files:**
- Verify only; no production files.

**Interfaces:**
- Consumes: committed outputs of Tasks 1–3.
- Produces: evidence that definitions, references, versions, and behavior contracts are coherent across repositories.

- [ ] **Step 1: Audit definitions and stale IDs**

Search all three worktrees. Exactly one definition of each shared ID must exist,
under Tamework. No removed downstream ID may remain in a role/template.

- [ ] **Step 2: Run exact-profile checks**

Run both locked release `0.5.7` profile checks. Attempt affected-scope author
checks against the changed candidates; report the existing empty-`assetRoots`
limitation separately if cross-mod graph resolution remains unavailable.

- [ ] **Step 3: Run final repository verification**

Run the Tamework verifier and production compile, HyDragon full tests, and the
Animal Husbandry complete flying-companion verifier. Confirm all three
worktrees are clean and `git diff --check` passes.

- [ ] **Step 4: Review scope and commit state**

Confirm Tamework is `3.0.0`, downstream dependency ranges are unchanged, no
aerial combat file changed, and each repository contains only its scoped
commits.
