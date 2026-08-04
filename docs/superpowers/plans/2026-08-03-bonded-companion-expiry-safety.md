# Bonded Companion Expiry Warnings and Safe Landing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Alert a bonded companion owner before a finite lease expires and prevent fall damage after an expiry-caused dismount.

**Architecture:** A pure warning policy computes due thresholds from a finite lease. A once-per-second world-thread system resolves the owner and sends notifications. An in-memory protection service is activated only during exact LEASE_EXPIRED projection cleanup; player systems clear it at ground or fluid contact and filter only fall-damage events.

**Tech Stack:** Java 25, Hytale 0.5.7 ECS, Tamework bonded persistence, JUnit 5, Maven.

## Global Constraints

- Finite leases warn at exactly 60, 30, 10, 5, 4, 3, 2, and 1 seconds; zero-expiry leases never warn.
- Use NotificationStyle.Warning for 60/30/10 and NotificationStyle.Danger for 5–1; copy is <NPC Name> expires in <#>s.
- Resolve player entities only through the active world/store on its world thread; never use PlayerRef.getComponent(Player) in a tick path.
- Create protection only when LEASE_EXPIRED cleanup finds a live rider. It covers only DamageCause.FALL, ends at ground/fluid contact, and expires at 60,000 ms.
- Do not change persistence, APIs, roster assets, voluntary dismounts, or other forced-dismount paths.

---

## File Structure

- companion/bonded/BondedCompanionExpiryWarningSchedule.java: pure threshold/style policy.
- companion/bonded/runtime/BondedCompanionExpiryWarningSystem.java: lease scan, owner/name lookup, and notification dispatch.
- damage/ExpiryDismountFallProtectionService.java: player-ID deadline state.
- damage/ExpiryDismountFallProtectionCleanupSystem.java: ground/fluid/deadline cleanup.
- damage/ExpiryDismountFallDamageFilterSystem.java: protected fall-damage cancellation.
- HytaleBondedCompanionWorldGateway.java: expiry-only rider detection before exact projection removal.
- TameworkBondedCompanionComposition.java and Tamework.java: compose and register runtime services.
- Focused JUnit tests and the bonded acceptance/player guide document the behavior.

### Task 1: Define and test the expiry-warning policy

**Files:**

- Create: src/main/java/com/alechilles/alecstamework/companion/bonded/BondedCompanionExpiryWarningSchedule.java
- Create: src/test/java/com/alechilles/alecstamework/companion/bonded/BondedCompanionExpiryWarningScheduleTest.java

**Interfaces:**

- Produces warning(long expiresAtMs, long nowMs): Optional<Warning>.
- Warning has secondsRemaining() and NotificationStyle style().

- [ ] **Step 1: Write the failing threshold tests**

~~~java
@Test
void selects_yellow_warnings_at_60_30_and_10_seconds() {
    assertEquals(new Warning(60, NotificationStyle.Warning), warning(160_000L, 100_000L).orElseThrow());
    assertEquals(new Warning(30, NotificationStyle.Warning), warning(130_000L, 100_000L).orElseThrow());
    assertEquals(new Warning(10, NotificationStyle.Warning), warning(110_000L, 100_000L).orElseThrow());
}

@Test
void selects_red_warnings_for_final_five_seconds_only() {
    for (int seconds = 1; seconds <= 5; seconds++) {
        assertEquals(new Warning(seconds, NotificationStyle.Danger),
                warning(100_000L + seconds * 1_000L, 100_000L).orElseThrow());
    }
    assertTrue(warning(106_000L, 100_000L).isEmpty());
}
~~~

- [ ] **Step 2: Run the test to verify it fails**

Run: ./mvnw test -Dtest=BondedCompanionExpiryWarningScheduleTest

Expected: compilation fails because BondedCompanionExpiryWarningSchedule does not exist.

- [ ] **Step 3: Implement the minimal signed-time policy**

~~~java
public static Optional<Warning> warning(long expiresAtMs, long nowMs) {
    if (expiresAtMs == 0L) return Optional.empty();
    long seconds = Math.floorDiv(expiresAtMs - nowMs, 1_000L);
    return switch ((int) seconds) {
        case 60, 30, 10 -> Optional.of(new Warning((int) seconds, NotificationStyle.Warning));
        case 5, 4, 3, 2, 1 -> Optional.of(new Warning((int) seconds, NotificationStyle.Danger));
        default -> Optional.empty();
    };
}
~~~

- [ ] **Step 4: Add unlimited, expired, and sub-second coverage; run green**

~~~java
assertTrue(warning(0L, 100_000L).isEmpty());
assertTrue(warning(100_000L, 100_000L).isEmpty());
assertEquals(10, warning(110_999L, 100_000L).orElseThrow().secondsRemaining());
~~~

Run: ./mvnw test -Dtest=BondedCompanionExpiryWarningScheduleTest

Expected: PASS.

- [ ] **Step 5: Commit**

~~~bash
git add src/main/java/com/alechilles/alecstamework/companion/bonded/BondedCompanionExpiryWarningSchedule.java src/test/java/com/alechilles/alecstamework/companion/bonded/BondedCompanionExpiryWarningScheduleTest.java
git commit -m "Feat: add bonded expiry warning schedule"
~~~

### Task 2: Dispatch one owner warning per due lease threshold

**Files:**

- Create: src/main/java/com/alechilles/alecstamework/companion/bonded/runtime/BondedCompanionExpiryWarningSystem.java
- Modify: src/main/java/com/alechilles/alecstamework/TameworkBondedCompanionComposition.java
- Modify: src/main/java/com/alechilles/alecstamework/Tamework.java
- Create: src/test/java/com/alechilles/alecstamework/companion/bonded/runtime/BondedCompanionExpiryWarningSystemTest.java

**Interfaces:**

- Consumes LeaseExpectation, the schedule, TameworkUiMessageService, and current-world Player components.
- Produces tick(world, store, nowMs), sending at most one warning for each (leaseToken, secondsRemaining).
- Composition exposes bounded exact-world live leases through activeLeasesInWorld(worldKey, limit).

- [ ] **Step 1: Write failing dispatch and deduplication tests**

~~~java
@Test
void sends_one_danger_notification_for_a_due_active_lease() {
    system.tick(world, store, 95_000L);
    system.tick(world, store, 95_000L);
    assertEquals(List.of(new Sent(ownerId, "Ember expires in 5s", NotificationStyle.Danger)), messages.sent());
}

@Test
void skips_unlimited_or_unavailable_owners() {
    system.tick(world, store, 100_000L);
    assertTrue(messages.sent().isEmpty());
}
~~~

- [ ] **Step 2: Run the focused test to verify it fails**

Run: ./mvnw test -Dtest=BondedCompanionExpiryWarningSystemTest

Expected: compilation fails because BondedCompanionExpiryWarningSystem does not exist.

- [ ] **Step 3: Implement world-thread dispatch and registration**

~~~java
for (LeaseExpectation lease : composition.activeLeasesInWorld(world.getName(), 64)) {
    warning(lease.expiresAtMs(), nowMs)
            .filter(due -> emitted.add(new Emission(lease.leaseToken(), due.secondsRemaining())))
            .ifPresent(due -> resolveOwner(world, store, lease.ownerUuid())
                    .ifPresent(player -> messages.show(player,
                            displayName(lease) + " expires in " + due.secondsRemaining() + "s",
                            due.style())));
}
~~~

Resolve a player reference through world.getEntityRef(ownerUuid), then read Player from the supplied store. Register this after BondedCompanionMaintenanceSystem.

- [ ] **Step 4: Add display-name fallback and stale-emission purge tests; run green**

~~~java
assertEquals("Tamed Dragon expires in 30s", messages.sent().getFirst().text());
system.tick(worldWithoutLease, store, 101_000L);
assertFalse(system.emissionsForTest().containsKey(oldLeaseToken));
~~~

Run: ./mvnw test -Dtest=BondedCompanionExpiryWarningScheduleTest,BondedCompanionExpiryWarningSystemTest

Expected: PASS.

- [ ] **Step 5: Commit**

~~~bash
git add src/main/java/com/alechilles/alecstamework/companion/bonded/runtime/BondedCompanionExpiryWarningSystem.java src/main/java/com/alechilles/alecstamework/TameworkBondedCompanionComposition.java src/main/java/com/alechilles/alecstamework/Tamework.java src/test/java/com/alechilles/alecstamework/companion/bonded/runtime/BondedCompanionExpiryWarningSystemTest.java
git commit -m "Feat: warn before bonded companion expiry"
~~~

### Task 3: Add bounded fall-damage protection and connect expiry cleanup

**Files:**

- Create: src/main/java/com/alechilles/alecstamework/damage/ExpiryDismountFallProtectionService.java
- Create: src/main/java/com/alechilles/alecstamework/damage/ExpiryDismountFallProtectionCleanupSystem.java
- Create: src/main/java/com/alechilles/alecstamework/damage/ExpiryDismountFallDamageFilterSystem.java
- Modify: src/main/java/com/alechilles/alecstamework/companion/bonded/runtime/HytaleBondedCompanionWorldGateway.java
- Modify: src/main/java/com/alechilles/alecstamework/TameworkBondedCompanionComposition.java
- Modify: src/main/java/com/alechilles/alecstamework/Tamework.java
- Create: src/test/java/com/alechilles/alecstamework/damage/ExpiryDismountFallProtectionServiceTest.java
- Create: src/test/java/com/alechilles/alecstamework/damage/ExpiryDismountFallDamageFilterSystemTest.java

**Interfaces:**

- protect(UUID playerUuid, long nowMs) records a 60,000-ms maximum protection deadline.
- protectedAt(UUID playerUuid, long nowMs) expires lazily; clear(UUID playerUuid) removes one player's marker.
- Gateway invokes protect only for LEASE_EXPIRED and a valid rider UUID found on the exact TameworkRideMountComponent or TameworkMountedGlideComponent.

- [ ] **Step 1: Write failing protection-service tests**

~~~java
@Test
void protects_only_until_sixty_second_deadline() {
    service.protect(playerId, 1_000L);
    assertTrue(service.protectedAt(playerId, 60_999L));
    assertFalse(service.protectedAt(playerId, 61_000L));
}

@Test
void clear_removes_only_landed_players_protection() {
    service.protect(first, 1_000L); service.protect(second, 1_000L);
    service.clear(first);
    assertFalse(service.protectedAt(first, 1_001L));
    assertTrue(service.protectedAt(second, 1_001L));
}
~~~

- [ ] **Step 2: Run the service test to verify it fails**

Run: ./mvnw test -Dtest=ExpiryDismountFallProtectionServiceTest

Expected: compilation fails because ExpiryDismountFallProtectionService does not exist.

- [ ] **Step 3: Implement deadline state and write the failing filter test**

~~~java
public boolean protectedAt(UUID playerUuid, long nowMs) {
    Long deadline = deadlines.get(playerUuid);
    if (deadline == null || nowMs >= deadline) {
        deadlines.remove(playerUuid, deadline);
        return false;
    }
    return true;
}

@Test
void cancels_only_protected_fall_damage() {
    protection.protect(playerId, clock.now());
    filter.handle(playerDamage(DamageCause.FALL));
    assertTrue(fallDamage.isCancelled());
    filter.handle(playerDamage(DamageCause.FIRE));
    assertFalse(fireDamage.isCancelled());
}
~~~

- [ ] **Step 4: Implement filter, contact cleanup, expiry-only gateway activation, and registration**

~~~java
if (isFallDamage(damage) && protection.protectedAt(player.getUuid(), clock.getAsLong())) {
    damage.setAmount(0.0f);
    damage.setCancelled(true);
}
if (movementStates.onGround || movementStates.inFluid) {
    protection.clear(player.getUuid());
}
if ("LEASE_EXPIRED".equals(intent.reason())) {
    riderUuid(reference, store).ifPresent(rider -> protection.protect(rider, clock.getAsLong()));
}
~~~

The rider lookup reads only the exact projection reference. Malformed or absent rider IDs do nothing. Register the contact and damage filter systems with Tamework's player and damage systems.

- [ ] **Step 5: Add expiry-only activation, ground/fluid clearing, ordinary-damage regression tests; run green**

~~~java
assertFalse(gateway.protectsForCleanup("store", mountedProjection));
assertTrue(gateway.protectsForCleanup("LEASE_EXPIRED", mountedProjection));
cleanup.tick(groundedPlayerChunk());
assertFalse(protection.protectedAt(playerId, clock.now()));
~~~

Run: ./mvnw test -Dtest=ExpiryDismountFallProtectionServiceTest,ExpiryDismountFallDamageFilterSystemTest

Expected: PASS.

- [ ] **Step 6: Commit**

~~~bash
git add src/main/java/com/alechilles/alecstamework/damage/ExpiryDismountFallProtectionService.java src/main/java/com/alechilles/alecstamework/damage/ExpiryDismountFallProtectionCleanupSystem.java src/main/java/com/alechilles/alecstamework/damage/ExpiryDismountFallDamageFilterSystem.java src/main/java/com/alechilles/alecstamework/companion/bonded/runtime/HytaleBondedCompanionWorldGateway.java src/main/java/com/alechilles/alecstamework/TameworkBondedCompanionComposition.java src/main/java/com/alechilles/alecstamework/Tamework.java src/test/java/com/alechilles/alecstamework/damage/ExpiryDismountFallProtectionServiceTest.java src/test/java/com/alechilles/alecstamework/damage/ExpiryDismountFallDamageFilterSystemTest.java
git commit -m "Feat: protect riders on bonded expiry"
~~~

### Task 4: Document and verify

**Files:**

- Modify: docs/testing/bonded-companion-acceptance-checklist.md
- Modify: wiki/Player-Guides/Companion-Controls/Linked-Panel-Guide.md

- [ ] **Step 1: Add the finite-session acceptance assertions**

~~~markdown
- [ ] Observe yellow notifications at 60/30/10, red notifications at 5–1, and exact <NPC Name> expires in <#>s copy.
- [ ] While mounted and airborne at expiry, verify fall damage is prevented until landing and no longer than 60 seconds.
~~~

- [ ] **Step 2: Add player-facing behavior**

~~~markdown
Finite bonded companions warn their owner before their active session expires. If expiration dismounts an airborne rider, Tamework prevents fall damage until the rider lands, with a one-minute maximum safety window.
~~~

- [ ] **Step 3: Run full verification**

~~~bash
./mvnw test
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
~~~

Expected: Maven exits 0 and no newly introduced unsafe player-access pattern appears.

- [ ] **Step 4: Validate engine-facing Java references against Hytale Workshop 0.5.7**

Use validate_hytale_code_refs on every changed gateway/ECS Java file.

Expected: no not_found engine references.

- [ ] **Step 5: Commit**

~~~bash
git add docs/testing/bonded-companion-acceptance-checklist.md wiki/Player-Guides/Companion-Controls/Linked-Panel-Guide.md
git commit -m "Docs: cover bonded expiry safety"
~~~
