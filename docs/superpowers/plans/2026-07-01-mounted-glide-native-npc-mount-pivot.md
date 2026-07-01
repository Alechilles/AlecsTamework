# Mounted Glide Native NPC Mount Pivot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild `TameworkMountedGlide` on Hytale's vanilla NPC mount flow so the HyDragon NordicDrake can mount, dismount, and move through player velocity instead of the failed `MountedComponent` NPC motion-controller path.

**Architecture:** The glide session uses `NPCMountComponent` as the attachment authority, allowing base `NPCMountSystems.OnAdd` to set `Player.mountEntityId` and send the client `MountNPC` packet. Tamework stores glide state on the NPC and a lightweight rider marker on the player, captures rider controls/look, and applies glide output to the rider's `Velocity` component. The NPC motion-controller/body-motion glide path is retired from active wiring.

**Tech Stack:** Java 21, Hytale 0.5.6 server APIs, Tamework NPC interactions, ECS systems, `TwMountedGlideConfig`, JUnit architecture tests.

---

## Evidence

- Hytale Workshop `release/0.5.6`, `com.hypixel.hytale.builtin.mounts.NPCMountSystems.OnAdd#onEntityAdded`: adds NPC mount state by sending `MountNPC` and setting `Player.mountEntityId`.
- Hytale Workshop `release/0.5.6`, `com.hypixel.hytale.builtin.mounts.npc.ActionMount#execute`: vanilla NPC mounting adds `NPCMountComponent`, changes the NPC to `Empty_Role`, and applies rider movement config.
- Hytale Workshop `release/0.5.6`, `com.hypixel.hytale.builtin.mounts.MountSystems.HandleMountInput#tick`: `MountedComponent` is a separate mount path; it applies relative/absolute movement updates to a target transform and is not the vanilla NPC mount flow.
- Endgame & QoL `endgame.plugin.systems.pet.PetMountFlightTickSystem`: mounted pet flight queries `NPCMountComponent`, reads rider movement states/head pitch, and applies `Velocity.addInstruction(..., ChangeVelocityType.Set)` to the rider.

## File Structure

- Modify `src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java`: change `applyTameworkMountedGlideMount` from `MountedComponent` to `NPCMountComponent`.
- Modify `src/main/java/com/alechilles/alecstamework/npc/network/MountedRidePacketHandler.java`: make glide dismount clean up `NPCMountComponent`/player mount state instead of only removing `MountedComponent`.
- Modify `src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureSystem.java`: remove `MountedComponent` dependency and capture controls/look from the vanilla mounted player.
- Create `src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlidePlayerVelocitySystem.java`: update glide physics and apply rider velocity.
- Modify `src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideCleanupSystem.java`: treat `NPCMountComponent` ownership as the active-session link.
- Modify `src/main/java/com/alechilles/alecstamework/Tamework.java`: register the new velocity system and stop registering `MountedGlideNativeInputIsolationSystem`, `MountedGlideStateSystem`, and `MountedGlideAuthoritativePoseSystem` for glide.
- Modify `src/main/resources/Server/NPC/Roles/_Core/Templates/Template_Tamework_Example*.json` and `Tamework_Example_Patch.json`: remove the requirement for `MountGlideController` motion/body-motion wiring from docs/comments, but leave old builders registered for compatibility if assets still reference them.
- Modify `docs/Mounted-Glide.md`, `docs/Interactions.md`, and `CHANGELOG.md`: document the native NPC mount/player velocity architecture.
- Modify tests under `src/test/java/com/alechilles/alecstamework/npc`: replace architecture assertions that require `MountedComponent` with assertions requiring `NPCMountComponent` and rider velocity.

---

### Task 1: Lock In The New Architecture With Tests

**Files:**
- Modify: `src/test/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureArchitectureTest.java`
- Modify: `src/test/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffectsTest.java`
- Create: `src/test/java/com/alechilles/alecstamework/npc/systems/MountedGlidePlayerVelocitySystemArchitectureTest.java`

- [ ] **Step 1: Replace the stale MountedComponent assertion**

In `MountedGlideInputCaptureArchitectureTest`, replace `mountedGlideUsesBaseMountedComponentAttachmentInsteadOfLegacyNpcMount` with:

```java
@Test
void mountedGlideUsesNativeNpcMountComponentInsteadOfMountedComponent() throws IOException {
    String interaction = Files.readString(Path.of(
            "src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java"
    ));
    int start = interaction.indexOf("private boolean applyTameworkMountedGlideMount");
    int end = interaction.indexOf("private String resolveGlideMovementConfigId", start);
    String method = interaction.substring(start, end);

    assertTrue(method.contains("NPCMountComponent.getComponentType()"));
    assertTrue(method.contains("createdMount.setOwnerPlayerRef(playerRefComponent)"));
    assertTrue(method.contains("createdMount.setAnchor(anchorX, anchorY, anchorZ)"));
    assertTrue(method.contains("RoleChangeSystem.requestRoleChange"));
    assertFalse(method.contains("new MountedComponent("));
    assertFalse(method.contains("MountController.Minecart"));
}
```

- [ ] **Step 2: Add the velocity-system architecture test**

Create `MountedGlidePlayerVelocitySystemArchitectureTest.java`:

```java
package com.alechilles.alecstamework.npc.systems;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MountedGlidePlayerVelocitySystemArchitectureTest {
    @Test
    void mountedGlideAppliesVelocityToRiderNotNpcMotionController() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlidePlayerVelocitySystem.java"
        ));

        assertTrue(source.contains("NPCMountComponent"));
        assertTrue(source.contains("Velocity.getComponentType()"));
        assertTrue(source.contains("Velocity.addInstruction"));
        assertTrue(source.contains("ChangeVelocityType.Set"));
        assertTrue(source.contains("MountedGlidePhysics.update"));
    }
}
```

- [ ] **Step 3: Run the failing tests**

Run:

```powershell
.\mvnw.cmd -Dtest=MountedGlideInputCaptureArchitectureTest,InteractionMountEffectsTest,MountedGlidePlayerVelocitySystemArchitectureTest test
```

Expected: FAIL because `InteractionMountEffects` still uses `MountedComponent`, and `MountedGlidePlayerVelocitySystem.java` does not exist.

- [ ] **Step 4: Commit the test lock**

Run:

```powershell
git add src/test/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureArchitectureTest.java src/test/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffectsTest.java src/test/java/com/alechilles/alecstamework/npc/systems/MountedGlidePlayerVelocitySystemArchitectureTest.java
git commit -m "Test: lock mounted glide native NPC mount architecture"
```

---

### Task 2: Change Mount Application To Vanilla NPC Mount

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffectsTest.java`

- [ ] **Step 1: Replace glide attachment setup**

Inside `applyTameworkMountedGlideMount`, require `NPCMountComponent` instead of `MountedComponent`:

```java
ComponentType<EntityStore, NPCMountComponent> nativeMountType = NPCMountComponent.getComponentType();
if (glideMountType == null || glideRiderType == null || nativeMountType == null) {
    return failMount(role, "mounted_glide", "component_type_unavailable",
            "glideMountType=%s glideRiderType=%s nativeMountType=%s",
            glideMountType != null,
            glideRiderType != null,
            nativeMountType != null);
}
```

- [ ] **Step 2: Use native mount-state checks**

Replace the existing-state check with:

```java
NPCMountComponent existingNativeMount = store.getComponent(npcRef, nativeMountType);
if (hasActiveNativeMount(playerComponent)
        || existingNativeMount != null
        || store.getComponent(npcRef, glideMountType) != null
        || store.getComponent(playerRef, glideRiderType) != null) {
    return failMount(role, "mounted_glide", "existing_mount_state",
            "playerMountEntityId=%s hasNativeMount=%s hasGlideMount=%s hasGlideRider=%s",
            playerComponent.getMountEntityId(),
            existingNativeMount != null,
            store.getComponent(npcRef, glideMountType) != null,
            store.getComponent(playerRef, glideRiderType) != null);
}
```

- [ ] **Step 3: Add the native mount component and request Empty_Role**

Replace the `store.putComponent(playerRef, mountedType, new MountedComponent(...))` block with:

```java
int originalRoleIndex = NPCPlugin.get().getIndex(role.getRoleName());
int emptyRoleIndex = NPCPlugin.get().getIndex(EMPTY_ROLE_ID);
if (originalRoleIndex < 0 || emptyRoleIndex < 0) {
    return failMount(role, "mounted_glide", "missing_role_index",
            "originalRoleIndex=%s emptyRoleIndex=%s", originalRoleIndex, emptyRoleIndex);
}

NPCMountComponent createdMount = store.ensureAndGetComponent(npcRef, nativeMountType);
if (createdMount == null) {
    return failMount(role, "mounted_glide", "ensure_npc_mount_failed");
}
createdMount.setOriginalRoleIndex(originalRoleIndex);
createdMount.setOwnerPlayerRef(playerRefComponent);
createdMount.setAnchor(anchorX, anchorY, anchorZ);

store.putComponent(npcRef, glideMountType, glideMount);
store.putComponent(playerRef, glideRiderType, glideRider);
clearStatusAnimation(npcRef, npcComponent, store);
RoleChangeSystem.requestRoleChange(npcRef, role, emptyRoleIndex, false, null, null, store);
boolean movementConfigApplied =
        applyMovementConfig(playerRef, playerRefComponent, playerComponent, store, movementConfigId);
```

- [ ] **Step 4: Update debug message**

Change `mounted_component_attach` to `native_npc_mount_attach` and include `nativeMount=true`.

- [ ] **Step 5: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=MountedGlideInputCaptureArchitectureTest,InteractionMountEffectsTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffects.java src/test/java/com/alechilles/alecstamework/npc/actions/InteractionMountEffectsTest.java src/test/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureArchitectureTest.java
git commit -m "Fix: mount glide through native NPC mount component"
```

---

### Task 3: Capture Rider Controls Without MountedComponent

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureSystem.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureArchitectureTest.java`

- [ ] **Step 1: Change constructor dependencies**

Remove `MountedComponent mountedComponentType` from `MountedGlideInputCaptureSystem` and add `NPCMountComponent nativeMountComponentType`. The query remains player-centered:

```java
this.query = Query.and(playerInputComponentType, riderComponentType);
```

- [ ] **Step 2: Validate native mount ownership**

Replace `mountedStillAttachedToMount` with:

```java
private boolean nativeMountStillOwnedByRider(@Nonnull Ref<EntityStore> riderRef,
                                             @Nonnull Ref<EntityStore> mountRef,
                                             @Nonnull ComponentAccessor<EntityStore> accessor) {
    NPCMountComponent nativeMount = accessor.getComponent(mountRef, nativeMountComponentType);
    if (nativeMount == null || nativeMount.getOwnerPlayerRef() == null) {
        return false;
    }
    Ref<EntityStore> ownerRef = nativeMount.getOwnerPlayerRef().getReference();
    return ownerRef != null && ownerRef.isValid() && ownerRef.equals(riderRef);
}
```

- [ ] **Step 3: Keep queue capture passive**

Ensure the system reads `PlayerInput` updates but never clears `playerInput.getMovementUpdateQueue()`. Native player input must remain available to Hytale player movement.

- [ ] **Step 4: Update Tamework registration**

In `Tamework.java`, pass `NPCMountComponent.getComponentType()` into `MountedGlideInputCaptureSystem` instead of `MountedComponent.getComponentType()`.

- [ ] **Step 5: Run tests**

Run:

```powershell
.\mvnw.cmd -Dtest=MountedGlideInputCaptureArchitectureTest,EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureSystem.java src/main/java/com/alechilles/alecstamework/Tamework.java src/test/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureArchitectureTest.java
git commit -m "Fix: capture mounted glide input from native NPC mount state"
```

---

### Task 4: Apply Glide Physics To Rider Velocity

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlidePlayerVelocitySystem.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/systems/MountedGlidePlayerVelocitySystemArchitectureTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/movement/MountedGlidePhysicsTest.java`

- [ ] **Step 1: Create the tick system skeleton**

Create `MountedGlidePlayerVelocitySystem` as an `EntityTickingSystem<EntityStore>` querying:

```java
this.query = Query.and(mountComponentType, nativeMountComponentType, transformComponentType);
```

Dependencies:

```java
private final Set<Dependency<EntityStore>> dependencies = Set.of(
        new SystemDependency<>(Order.AFTER, MountedGlideInputCaptureSystem.class)
);
```

- [ ] **Step 2: Resolve the rider from `NPCMountComponent`**

Use:

```java
PlayerRef owner = nativeMount.getOwnerPlayerRef();
Ref<EntityStore> riderRef = owner == null ? null : owner.getReference();
if (riderRef == null || !riderRef.isValid() || riderRef.getStore() != store) {
    return;
}
```

- [ ] **Step 3: Compute pitch/yaw and update physics**

Use the existing component snapshot:

```java
double pitchRadians = mount.hasLookRotation()
        ? Math.toRadians(mount.getLookPitchDegrees())
        : 0.0;
double yawRadians = mount.hasLookRotation()
        ? Math.toRadians(mount.getLookYawDegrees())
        : mountTransform.getRotation().yaw();

MountedGlidePhysics.Output output = MountedGlidePhysics.update(
        mount.toPhysicsState(),
        config,
        new MountedGlidePhysics.Input(
                pitchRadians,
                mount.getForwardIntent(),
                mount.getStrafeIntent(),
                mount.isJumpHeld(),
                mount.isSprinting(),
                mount.isCrouching()
        ),
        dt
);
```

Then call `mount.applyPhysicsState(state)` and write the component back with `commandBuffer.putComponent(mountRef, mountComponentType, mount)`.

- [ ] **Step 4: Apply rider velocity**

Apply velocity to the player:

```java
double forwardX = -Math.sin(yawRadians);
double forwardZ = -Math.cos(yawRadians);
Vector3d velocityVector = new Vector3d(
        forwardX * output.forwardSpeed(),
        output.verticalVelocity(),
        forwardZ * output.forwardSpeed()
);
Velocity velocity = commandBuffer.getComponent(riderRef, velocityComponentType);
if (velocity != null) {
    velocity.addInstruction(velocityVector, null, ChangeVelocityType.Set);
}
```

- [ ] **Step 5: Register the system**

In `Tamework.java`, register `MountedGlidePlayerVelocitySystem` after input capture and before cleanup. Stop registering `MountedGlideStateSystem`, `MountedGlideNativeInputIsolationSystem`, and `MountedGlideAuthoritativePoseSystem` for glide.

- [ ] **Step 6: Run focused tests**

Run:

```powershell
.\mvnw.cmd -Dtest=MountedGlidePlayerVelocitySystemArchitectureTest,MountedGlidePhysicsTest,EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlidePlayerVelocitySystem.java src/main/java/com/alechilles/alecstamework/Tamework.java src/test/java/com/alechilles/alecstamework/npc/systems/MountedGlidePlayerVelocitySystemArchitectureTest.java
git commit -m "Feat: drive mounted glide through rider velocity"
```

---

### Task 5: Native Dismount And Cleanup

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/npc/network/MountedRidePacketHandler.java`
- Modify: `src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideCleanupSystem.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureArchitectureTest.java`

- [ ] **Step 1: Update packet dismount**

In `handleMountedGlideDismount`, remove the rider marker, unregister any ride session, and delegate vanilla NPC mount cleanup:

```java
Player player = store.getComponent(riderRef, Player.getComponentType());
if (player != null) {
    MountPlugin.checkDismountNpc(store, riderRef, player);
}
```

Then remove `TameworkMountedGlideRiderComponent` from the rider and `TameworkMountedGlideComponent` from the mount if the mount ref is still valid.

- [ ] **Step 2: Update cleanup stale-session checks**

In `MountedGlideCleanupSystem`, replace `MountedComponent` checks with `NPCMountComponent` owner checks:

```java
private boolean riderNativeMountedToMount(@Nonnull Ref<EntityStore> riderRef,
                                          @Nonnull Ref<EntityStore> mountRef,
                                          @Nonnull Store<EntityStore> store) {
    NPCMountComponent nativeMount = store.getComponent(mountRef, nativeMountComponentType);
    if (nativeMount == null || nativeMount.getOwnerPlayerRef() == null) {
        return false;
    }
    Ref<EntityStore> ownerRef = nativeMount.getOwnerPlayerRef().getReference();
    return ownerRef != null && ownerRef.isValid() && ownerRef.equals(riderRef);
}
```

- [ ] **Step 3: Restore Interactable on cleanup**

Keep:

```java
bufferStore.ensureAndGetComponent(mountRef, Interactable.getComponentType());
```

only after the native mount component has been removed or vanilla cleanup has reset the mount. This preserves the F prompt after dismount.

- [ ] **Step 4: Run tests**

Run:

```powershell
.\mvnw.cmd -Dtest=MountedGlideInputCaptureArchitectureTest,EcsWriteSafetyGuardTest,AsyncThreadSafetyGuardTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/network/MountedRidePacketHandler.java src/main/java/com/alechilles/alecstamework/npc/systems/MountedGlideCleanupSystem.java
git commit -m "Fix: clean up mounted glide through native dismount"
```

---

### Task 6: Optional F/Use Interaction Filter If Dismount Still Routes Through Prompt

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/npc/network/MountedGlideInteractionPacketFilter.java`
- Modify: `src/main/java/com/alechilles/alecstamework/Tamework.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureArchitectureTest.java`

- [ ] **Step 1: Add only if manual test shows F/use still re-enters Tamework interaction**

The runtime symptom that triggers this task is a debug line like:

```text
TameworkMount debug: stage=mounted_glide reason=existing_mount_state
```

after pressing F while mounted.

- [ ] **Step 2: Implement inbound packet filter**

Register a `PlayerPacketFilter` via:

```java
PacketFilter mountedGlideInteractionFilter = PacketAdapters.registerInbound(new MountedGlideInteractionPacketFilter());
```

The filter should detect `SyncInteractionChains` targeting the player's current `mountEntityId`, then schedule world-thread cleanup equivalent to `handleMountedGlideDismount`.

- [ ] **Step 3: Deregister filter during shutdown**

Store the returned `PacketFilter` in `Tamework` and call:

```java
PacketAdapters.deregisterInbound(mountedGlideInteractionFilter);
```

when the plugin tears down, matching Hytale's `PacketAdapters` contract.

- [ ] **Step 4: Commit only if implemented**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/npc/network/MountedGlideInteractionPacketFilter.java src/main/java/com/alechilles/alecstamework/Tamework.java src/test/java/com/alechilles/alecstamework/npc/systems/MountedGlideInputCaptureArchitectureTest.java
git commit -m "Fix: route mounted glide use interaction to dismount"
```

---

### Task 7: Assets And Docs

**Files:**
- Modify: `src/main/resources/Server/NPC/Roles/_Core/Templates/Template_Tamework_Example.json`
- Modify: `src/main/resources/Server/NPC/Roles/_Core/Templates/Template_Tamework_Example_Simple.json`
- Modify: `src/main/resources/Server/NPC/Roles/_Core/Templates/Tamework_Example_Patch.json`
- Modify: `docs/Mounted-Glide.md`
- Modify: `docs/Interactions.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Update role template wording**

Change comments that say the role must add `TameworkMountedGlide` body motion/controller wiring. New wording:

```json
"Description": "Set to TameworkMountedGlide to use the native NPC mount glide system. Flight is driven by rider velocity rather than an NPC motion controller."
```

- [ ] **Step 2: Keep old builder references only for compatibility**

Do not remove the `TameworkMountedGlide` motion/body-motion builders in this pass if template inheritance still references them. Mark them as legacy in docs instead.

- [ ] **Step 3: Update docs**

`docs/Mounted-Glide.md` should describe:

- `NPCMountComponent` is the attachment authority.
- `MountGlideMovementConfig` still applies to the rider.
- Jump held repeats flaps on cooldown.
- Sprint modifies flap behavior.
- Crouch acts as airbrake.
- Mouse pitch trades speed/altitude through rider velocity.

- [ ] **Step 4: Update changelog**

Add a player-facing unreleased entry:

```markdown
- Reworked experimental NordicDrake mounted glide to use Hytale's native NPC mount flow and rider velocity, improving attachment and input behavior.
```

- [ ] **Step 5: Run docs/index check if agent docs or generated indexes changed**

Only if agent docs are touched:

```powershell
.\scripts\tools\check-agent-docs.ps1
```

- [ ] **Step 6: Commit**

Run:

```powershell
git add src/main/resources/Server/NPC/Roles/_Core/Templates/Template_Tamework_Example.json src/main/resources/Server/NPC/Roles/_Core/Templates/Template_Tamework_Example_Simple.json src/main/resources/Server/NPC/Roles/_Core/Templates/Tamework_Example_Patch.json docs/Mounted-Glide.md docs/Interactions.md CHANGELOG.md
git commit -m "Docs: document native mounted glide architecture"
```

---

### Task 8: Full Verification And Runtime Install

**Files:**
- No source file edits unless tests reveal defects.

- [ ] **Step 1: Run focused mounted glide tests**

Run:

```powershell
.\mvnw.cmd -Dtest=TwMountedGlideConfigTest,TameworkMountedGlideComponentTest,MountedGlidePhysicsTest,MountedGlideInputCaptureArchitectureTest,MountedGlidePlayerVelocitySystemArchitectureTest test
```

Expected: PASS.

- [ ] **Step 2: Run full tests**

Run:

```powershell
.\mvnw.cmd test
```

Expected: PASS.

- [ ] **Step 3: Run ECS thread-safety grep**

Run:

```powershell
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

Expected: no new matches in tick/runtime paths.

- [ ] **Step 4: Package and install**

Use the existing project packaging workflow. Verify source jar, install package, and runtime mod copy have the same size and fresh timestamp.

- [ ] **Step 5: Manual runtime test**

In Hytale:

```text
/tw debugride
```

Then test:

- Press F on HyDragon NordicDrake: rider attaches and `playerMountEntityId` becomes the dragon network id.
- Hold jump: flaps repeat on cooldown, not every tick.
- Look down: forward speed increases and descent increases.
- Look up: speed decays and altitude can increase only by trading speed or from flap output.
- Sprint while flapping: forward boost behavior applies.
- Crouch: airbrake behavior applies.
- Press F again: dismounts cleanly and the prompt returns.

- [ ] **Step 6: Commit any verification fixes**

Run only if verification required edits:

```powershell
git add <changed-files>
git commit -m "Fix: stabilize native mounted glide verification"
```

---

## Self-Review

- Spec coverage: The plan covers attachment, input, flap cooldown, pitch-based speed/altitude trade, sprint/crouch controls, dismount, docs, tests, and runtime packaging.
- Placeholder scan: No implementation step is left as `TBD`; the only conditional work is the packet filter, guarded by an explicit runtime symptom.
- Type consistency: The plan consistently uses `TameworkMountedGlideComponent` on the NPC, `TameworkMountedGlideRiderComponent` on the rider, `NPCMountComponent` for attachment, and `Velocity` on the rider for movement.
