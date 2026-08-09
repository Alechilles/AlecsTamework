# Avatar Flight Launch-Charge Movement Lock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent grounded directional movement during an active avatar-flight launch charge while preserving mouse-look, release, and unrelated physics motion.

**Architecture:** Extend the existing grounded movement-speed synchronization call with an explicit charge-lock flag. `AvatarFlightGroundMovementService` will send native `MovementSettings.baseSpeed = 0` only during the grounded charge window and return to the configured grounded speed as soon as charging ends.

**Tech Stack:** Java 21, Hytale ECS `CommandBuffer`, Hytale `MovementManager`/`MovementSettings`, Gradle/JUnit 5, Markdown documentation.

## Global Constraints

- Do not set or clear horizontal velocity while charging.
- Do not change charge timing, Vigour, VFX, audio, HUD, launch impulses, mouse-look, or release behavior.
- Do not add a configuration field.
- Preserve the unrelated working-tree change in `src/main/resources/manifest.json`.
- Do not add a source-shape or trivial speed-selection test.

---

### Task 1: Lock native grounded speed during launch charging

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystem.java:129-137`
- Modify: `src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightGroundMovementService.java:12-43`
- Modify: `docs/Avatar-Flight.md` (`Controls` and `Launch` sections)
- Modify: `CHANGELOG.md` (`3.1.0` Changed section)

**Interfaces:**
- Consumes: `AvatarFlightInputComponent.isLaunchCharging(): boolean`
- Produces: `AvatarFlightGroundMovementService.sync(..., double groundedMoveSpeed, boolean grounded, boolean movementLocked): void`

- [ ] **Step 1: Pass the active launch-charge state into grounded movement synchronization**

Update the call in `AvatarFlightMovementSystem.tick(...)`:

```java
groundMovementService.sync(
        ref,
        commandBuffer,
        flight,
        config.getMovement().getGroundedMoveSpeed(),
        output.mode() == AvatarFlightMode.GROUNDED
                && controllerInput.onGround()
                && !controllerInput.inFluid(),
        input != null && input.isLaunchCharging()
);
```

- [ ] **Step 2: Select zero native base speed only while the grounded charge lock is active**

Add `boolean movementLocked` to `AvatarFlightGroundMovementService.sync(...)` and replace its target calculation with:

```java
float target = movementLocked ? 0.0f : positiveFloat(groundedMoveSpeed, 8.0f);
```

Keep the existing snapshot and restoration behavior intact so release/cancellation returns to the configured grounded speed and deactivation restores the pre-avatar-flight speed.

- [ ] **Step 3: Document the player-visible behavior**

Add to `docs/Avatar-Flight.md` that grounded directional movement is locked while crouch-launch charging, while mouse-look and release remain available. Add one player-facing `CHANGELOG.md` Changed bullet describing the same behavior.

- [ ] **Step 4: Validate engine references and repository behavior**

Run:

```bash
bash ../gradlew :alecstamework:test
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
git diff --check
```

Expected: Gradle tests pass; the thread-affinity grep introduces no new match in the changed files; `git diff --check` reports no whitespace errors.

- [ ] **Step 5: Commit the implementation**

```bash
git add CHANGELOG.md docs/Avatar-Flight.md \
  src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightGroundMovementService.java \
  src/main/java/com/alechilles/alecstamework/avatarflight/AvatarFlightMovementSystem.java
git commit -m "Fix: lock movement during avatar launch charge" \
  -m "Test status: bash ../gradlew :alecstamework:test"
```
