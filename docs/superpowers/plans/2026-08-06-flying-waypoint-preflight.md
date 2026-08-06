# Flying Waypoint Preflight Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make autonomous pass-through flight select a clear stable lane before committing while sharing the same bounded waypoint-clearance policy as wander flight.

**Architecture:** Keep continuous `FlyingObstacleAvoidance.adjust` unchanged for every moving mode. Refactor stable-waypoint clearance selection into mode-neutral helpers, generate a direct and two lateral pass-through candidates, probe them through the existing bounded `probeWaypoint` API, and capture the selected destination for the pass.

**Tech Stack:** Java 25, JUnit 5, JOML `Vector3d`, Hytale NPC `MotionControllerFly` probing.

## Global Constraints

- Preflight only modes that create stable waypoints: `WANDER_TARGET` and `PASS_THROUGH_TARGET`.
- Preserve `AvoidObstacles: false` and rider-controlled bypass behavior.
- Preserve the configured pass-through distance beyond the target.
- Use no new builder IDs or configuration fields.
- Do not touch unrelated concurrent worktree changes.

---

### Task 1: Shared stable-waypoint preflight

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/npc/movement/BodyMotionTameworkFlyingOrbit.java`
- Test: `src/test/java/com/alechilles/alecstamework/npc/movement/BodyMotionTameworkFlyingOrbitTest.java`

**Interfaces:**
- Consumes: `FlyingObstacleAvoidance.probeWaypoint(Vector3d, double, double, FlyingObstacleAvoidance.Probe)` and its per-update probe budget.
- Produces: mode-neutral waypoint selection plus three pass-through destination candidates: direct, left-offset, and right-offset.

- [ ] **Step 1: Write the failing behavior test**

Add a test whose hand-authored candidates and clearance fractions prove that a clear alternate destination is copied into the selected output instead of a blocked direct destination:

```java
@Test
void passThroughPreflightSelectsClearAlternateLane() {
    Vector3d[] candidates = {
            new Vector3d(18.0, 3.0, 0.0),
            new Vector3d(15.6, 3.0, -9.0),
            new Vector3d(15.6, 3.0, 9.0)
    };

    Vector3d selected = BodyMotionTameworkFlyingOrbit.selectWaypointDestination(
            candidates, new double[] { 0.2, 1.0, 0.7 }, 3, new Vector3d());

    assertEquals(candidates[1], selected);
}
```

This catches the production regression where pass-through always commits to the direct lane despite a probed clear alternative.

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
bash ../gradlew :alecstamework:test --tests '*BodyMotionTameworkFlyingOrbitTest.passThroughPreflightSelectsClearAlternateLane'
```

Expected: compilation failure because `selectWaypointDestination` does not exist.

- [ ] **Step 3: Implement the minimal shared selection helper**

Rename the mode-specific selector to `selectWaypointCandidate`, then add a helper that copies the selected candidate or returns `null` when selection is impossible:

```java
@Nullable
static Vector3d selectWaypointDestination(@Nonnull Vector3d[] candidates,
                                          @Nonnull double[] clearances,
                                          int candidateCount,
                                          @Nonnull Vector3d output) {
    int selected = selectWaypointCandidate(clearances, candidateCount);
    return selected >= 0 && selected < candidates.length
            ? output.set(candidates[selected]) : null;
}
```

Update the existing wander tests and caller to use the mode-neutral name without changing their behavior.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Write the failing pass-through geometry test**

Add a test that generates direct/left/right candidates and asserts all three remain exactly the configured distance beyond the target while the alternates occupy different lanes:

```java
@Test
void passThroughCandidatesPreserveConfiguredDistanceAcrossAlternateLanes() {
    Vector3d[] candidates = { new Vector3d(), new Vector3d(), new Vector3d() };
    BodyMotionTameworkFlyingOrbit.resolvePassThroughCandidates(
            -8.0, 0.0, 0.0, 10.0, 0.0, 18.0, 3.0, candidates);

    for (Vector3d candidate : candidates) {
        assertEquals(18.0, Math.hypot(candidate.x, candidate.z), EPSILON);
        assertEquals(13.0, candidate.y, EPSILON);
    }
    assertTrue(candidates[1].z < 0.0);
    assertTrue(candidates[2].z > 0.0);
}
```

This catches alternate-lane construction that shortens the strafing pass or places a candidate before the target.

- [ ] **Step 6: Run the geometry test and verify RED**

Run:

```bash
bash ../gradlew :alecstamework:test --tests '*BodyMotionTameworkFlyingOrbitTest.passThroughCandidatesPreserveConfiguredDistanceAcrossAlternateLanes'
```

Expected: compilation failure because `resolvePassThroughCandidates` does not exist.

- [ ] **Step 7: Implement pass-through candidate generation and preflight wiring**

Generate the direct lane and two lanes rotated by a fixed small angle around the target, preserving `PassThroughDistance` and the captured altitude. When pass-through first activates:

1. Build all three candidates.
2. If autonomous avoidance is active, probe each route with `probeWaypoint`, stopping on the first fully clear route.
3. Copy the first fully clear candidate or the candidate with greatest partial clearance into `passThroughDestination`.
4. If probing is unavailable or selection fails, retain the direct candidate.
5. Keep the existing continuous `adjust` call active during movement.

Use the existing reusable candidate vectors and clearance buffer; do not allocate per tick.

- [ ] **Step 8: Run focused movement tests and verify GREEN**

Run:

```bash
bash ../gradlew :alecstamework:test --tests '*BodyMotionTameworkFlyingOrbitTest'
```

Expected: all `BodyMotionTameworkFlyingOrbitTest` tests pass.

- [ ] **Step 9: Run full Tamework verification**

Run:

```bash
bash ../gradlew :alecstamework:test
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
```

Expected: Gradle exits 0. Review any grep hits as pre-existing or unrelated; this change introduces no player access.

- [ ] **Step 10: Commit only owned files**

```bash
git add src/main/java/com/alechilles/alecstamework/npc/movement/BodyMotionTameworkFlyingOrbit.java \
  src/test/java/com/alechilles/alecstamework/npc/movement/BodyMotionTameworkFlyingOrbitTest.java
git commit -m "Fix: preflight flying pass-through lanes"
```
