# Mounted Glide Controller Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a clean-slate, configurable Tamework mounted glide controller that uses cooldown-gated flaps, pitch-weighted glide speed/lift conversion, sprint forward flaps, and crouch airbrake.

**Architecture:** Add a new mounted-glide stack beside the legacy `TameworkRide`/`TameworkFly` stack. The new path owns its own config family, components, input capture, body motion, motion controller, cleanup, and mount effect wiring, while reusing only generic base-game input and NPC motion APIs.

**Tech Stack:** Java, Hytale ECS/components, Hytale NPC builder/motion APIs, Tamework `Tw*Config` asset codecs, JUnit tests, Maven.

---

## File Structure

- Create `src/main/java/com/alechilles/alecstamework/config/assets/TwMountedGlideConfig.java`: role-scoped mounted glide tuning asset with inheritance.
- Create `src/main/java/com/alechilles/alecstamework/npc/components/TameworkMountedGlideComponent.java`: mount-side glide state and input snapshot.
- Create `src/main/java/com/alechilles/alecstamework/npc/components/TameworkMountedGlideRiderComponent.java`: rider-side mount UUID marker.
- Create `src/main/java/com/alechilles/alecstamework/npc/movement/MountedGlidePhysics.java`: deterministic pitch/flap/airbrake math for unit testing.
- Create `src/main/java/com/alechilles/alecstamework/npc/movement/MountedGlidePhysicsState.java`: small state object for glide speed, cooldown, and vertical velocity.
- Create `src/main/java/com/alechilles/alecstamework/npc/movement/BuilderBodyMotionTameworkMountedGlide.java`: body motion builder ID `TameworkMountedGlide`.
- Create `src/main/java/com/alechilles/alecstamework/npc/movement/BodyMotionTameworkMountedGlide.java`: converts input snapshot into NPC steering.
- Create `src/main/java/com/alechilles/alecstamework/npc/movement/BuilderMotionControllerTameworkMountedGlide.java`: motion controller builder ID `TameworkMountedGlide`.
- Create `src/main/java/com/alechilles/alecstamework/npc/movement/MotionControllerTameworkMountedGlide.java`: applies glide physics to NPC motion.
- Create `src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureSystem.java`: captures mounted player input before vanilla mount handling.
- Create `src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideCleanupSystem.java`: cleans stale sessions and restores NPC state.
- Create `src/main/java/com/alechilles/alecstamework/npc/network/MountedGlidePacketHandler.java`: supplements active session look/wish packets.
- Modify `src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java`: add glide mount setup while preserving legacy mount setup.
- Modify `src/main/java/com/alechilles/alecstamework/Tamework.java`: register new config, components, systems, and packet handler.
- Modify `src/main/java/com/alechilles/alecstamework/npc/TameworkNpcBuilderRegistrar.java`: register new body motion and motion controller builders.
- Modify `docs/Interactions.md`, `CHANGELOG.md`, and config wiki docs after code is working.

## Tasks

### Task 1: Config And Physics Tests

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/config/assets/TwMountedGlideConfig.java`
- Create: `src/main/java/com/alechilles/alecstamework/npc/movement/MountedGlidePhysics.java`
- Create: `src/main/java/com/alechilles/alecstamework/npc/movement/MountedGlidePhysicsState.java`
- Test: `src/test/java/com/alechilles/alecstamework/config/assets/TwMountedGlideConfigTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/movement/MountedGlidePhysicsTest.java`

- [ ] **Step 1: Write failing tests for config inheritance and role cache.**
  Add tests that construct parent/child config instances, call `inheritMissingTopLevelFrom`, and verify object sections inherit missing nested keys while `RoleIds` replaces when explicit.

- [ ] **Step 2: Write failing physics tests.**
  Add tests for held-jump cooldown flaps, sprint forward flaps, pitch-down speed/sink, pitch-up lift/speed drain, stall sink, and crouch airbrake.

- [ ] **Step 3: Implement `TwMountedGlideConfig`.**
  Follow `TwFoodConfig`/`TwCompanionConfig` structure: `AssetBuilderCodec`, `getAssetMap`, `resolveForRole`, `buildRoleCache`, `inheritMissingTopLevelFrom`, nested section copy methods, defaults, and public section getters.

- [ ] **Step 4: Implement deterministic glide physics.**
  Keep it independent of Hytale components so tests can verify glide behavior without a live world.

- [ ] **Step 5: Run focused tests.**
  Run: `.\mvnw.cmd -Dtest=TwMountedGlideConfigTest,MountedGlidePhysicsTest test`
  Expected: PASS.

- [ ] **Step 6: Commit.**
  Commit message: `Feat: add mounted glide config and physics`

### Task 2: Components And Registration

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/components/TameworkMountedGlideComponent.java`
- Create: `src/main/java/com/alechilles/alecstamework/npc/components/TameworkMountedGlideRiderComponent.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/TameworkNpcBuilderRegistrar.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/components/TameworkMountedGlideComponentTest.java`

- [ ] **Step 1: Write failing component tests.**
  Verify input snapshot capture, held-jump flap request behavior, cooldown persistence, clone behavior, and UUID sanitization.

- [ ] **Step 2: Implement component codecs.**
  Register stable component IDs `TameworkMountedGlide` and `TameworkMountedGlideRider`.

- [ ] **Step 3: Register `TwMountedGlideConfig` asset store and clear cache on load/remove.**
  Use `Server/Tamework/Mounts/Glide` as the asset path.

- [ ] **Step 4: Register new NPC builders.**
  Add body motion and motion controller builder registrations without changing legacy registrations.

- [ ] **Step 5: Run focused tests.**
  Run: `.\mvnw.cmd -Dtest=TameworkMountedGlideComponentTest,TwMountedGlideConfigTest test`
  Expected: PASS.

- [ ] **Step 6: Commit.**
  Commit message: `Feat: register mounted glide runtime types`

### Task 3: Input Capture And Session Lifecycle

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureSystem.java`
- Create: `src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideCleanupSystem.java`
- Create: `src/main/java/com/alechilles/alecstamework/npc/network/MountedGlidePacketHandler.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureArchitectureTest.java`

- [ ] **Step 1: Add architecture guard tests.**
  Verify systems do not call `PlayerRef.getComponent(Player)`, do not consume Q/drop or mouse button input, and do not reference legacy `TameworkRideMountComponent`.

- [ ] **Step 2: Implement input capture system.**
  Capture `WishMovement`, relative/absolute movement, `SetBody`, `SetHead`, `SetMovementStates`, and `SetRiderMovementStates`; write normalized state to the mount component and clear only movement queue entries for active glide riders.

- [ ] **Step 3: Implement packet supplement handler.**
  Track active sessions by player UUID/rider UUID, capture client movement and mouse-motion look snapshots, delegate packets after capture, and leave mouse buttons untouched.

- [ ] **Step 4: Implement cleanup system.**
  Clear stale rider/mount markers and restore previous state/controller through `CommandBuffer` work.

- [ ] **Step 5: Run guard tests.**
  Run: `.\mvnw.cmd -Dtest=MountedGlideInputCaptureArchitectureTest,EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest test`
  Expected: PASS.

- [ ] **Step 6: Commit.**
  Commit message: `Feat: capture mounted glide input`

### Task 4: Body Motion, Motion Controller, And Mount Setup

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/movement/BuilderBodyMotionTameworkMountedGlide.java`
- Create: `src/main/java/com/alechilles/alecstamework/npc/movement/BodyMotionTameworkMountedGlide.java`
- Create: `src/main/java/com/alechilles/alecstamework/npc/movement/BuilderMotionControllerTameworkMountedGlide.java`
- Create: `src/main/java/com/alechilles/alecstamework/npc/movement/MotionControllerTameworkMountedGlide.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/movement/BodyMotionTameworkMountedGlideTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/movement/MotionControllerTameworkMountedGlideTest.java`

- [ ] **Step 1: Write body motion tests.**
  Verify yaw/pitch resolve from snapshot, WASD generates glide steering, and held jump remains a flap request instead of continuous climb.

- [ ] **Step 2: Write controller tests.**
  Verify movement state outputs, speed limits, and config values drive the physics helper.

- [ ] **Step 3: Implement body motion.**
  Suppress normal AI while mounted, resolve config for role, set steering yaw/pitch/translation from component snapshot and `MountedGlidePhysics`.

- [ ] **Step 4: Implement motion controller.**
  Extend `MotionControllerFly`, apply glide translation through the existing NPC motion controller `computeMove`/`executeMove` path, clamp speed/vertical velocity, and add collision recovery.

- [ ] **Step 5: Wire mount setup.**
  Add a separate glide mount setup path selected by role param/config and store previous state/controller for cleanup.

- [ ] **Step 6: Run focused tests.**
  Run: `.\mvnw.cmd -Dtest=BodyMotionTameworkMountedGlideTest,MotionControllerTameworkMountedGlideTest,MountedGlidePhysicsTest test`
  Expected: PASS.

- [ ] **Step 7: Commit.**
  Commit message: `Feat: add mounted glide controller`

### Task 5: Docs, Examples, And Full Verification

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/Interactions.md`
- Create or modify wiki config reference for `TwMountedGlideConfig`
- Create default example config under `src/main/resources/Server/Tamework/Mounts/Glide`

- [ ] **Step 1: Add player-facing changelog entry.**
  Mention the new beta mounted glide controller, pitch-driven glide, cooldown flaps, sprint flaps, and crouch airbrake.

- [ ] **Step 2: Add docs and default config example.**
  Document required role setup, config path, controls, and tuning fields.

- [ ] **Step 3: Run full tests and safety grep.**
  Run: `.\mvnw.cmd test`
  Run: `rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java`
  Expected: tests pass; grep has no new unsafe mounted-glide tick-path matches.

- [ ] **Step 4: Run agent docs check if package/docs layout changed.**
  Run: `.\scripts\tools\check-agent-docs.ps1`
  Expected: PASS or existing unrelated generated-index warnings documented.

- [ ] **Step 5: Commit.**
  Commit message: `Docs: document mounted glide controller`
