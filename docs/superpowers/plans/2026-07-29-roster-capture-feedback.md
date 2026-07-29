# Roster Capture Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a prominent two-line notification for successful roster captures and give players the exact reason for every capture denial.

**Architecture:** Introduce a small capture-feedback model that preserves the specific denial from policy and chance evaluation until the interaction boundary. Route both ordinary and bonded capture through it. The bonded completion context freezes player-safe NPC and command-item labels before the durable operation, then its terminal dispatcher sends the success notification only after persistence, effects, and source finalization succeed.

**Tech Stack:** Java 25, JUnit 5, Hytale `NotificationUtil`, `NotificationStyle.Success`, existing Tamework language assets.

## Global Constraints

- Preserve capture mechanics, ownership rules, chance formulas, and roster capacity behavior.
- Do not expose internal IDs or raw infrastructure errors to players.
- Emit exactly one terminal player message per capture attempt.
- Do not alter captured-item success behavior.
- Use `CommandItemDisplayResolver` for command-item labels and `SpawnerNpcIdentityService` for live NPC labels.
- Keep the existing unrelated working-tree edits untouched.

---

### Task 1: Capture denial model and localization

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/items/CaptureFeedbackReason.java`
- Create: `src/main/java/com/alechilles/alecstamework/items/CaptureFeedbackText.java`
- Modify: `src/main/resources/Server/Languages/*/server.lang`
- Test: `src/test/java/com/alechilles/alecstamework/items/CaptureFeedbackTextTest.java`

**Interfaces:**
- Consumes: policy reason codes such as `capture-power-below-minimum`, source/target labels, and integer capture power values.
- Produces: `CaptureFeedbackReason.fromPolicyCode(String)` and `CaptureFeedbackText.denial(CaptureFeedbackReason, CaptureFeedbackText.Context)` returning a localized key plus safe parameters.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void powerDenialNamesTheSourceAndTargetAndRequiredPower() {
    var text = CaptureFeedbackText.denial(
            CaptureFeedbackReason.POWER_TOO_LOW,
            new CaptureFeedbackText.Context("Draconic Stone", "Nordic Drake", 2, 4, null));

    assertEquals("tamework.ui.notifications.capture.powerTooLow", text.key());
    assertArrayEquals(new Object[] {"Draconic Stone", "Nordic Drake", 4}, text.arguments());
}

@Test
void unrecognizedPolicyReasonUsesTheSafeFallback() {
    assertEquals(CaptureFeedbackReason.UNAVAILABLE,
            CaptureFeedbackReason.fromPolicyCode("capture-random-provider-failed"));
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./mvnw -Dtest=CaptureFeedbackTextTest test`

Expected: FAIL because `CaptureFeedbackReason` and `CaptureFeedbackText` do not exist.

- [ ] **Step 3: Implement the minimal model and localized language keys**

```java
enum CaptureFeedbackReason {
    POWER_TOO_LOW,
    HEALTH_TOO_HIGH,
    REQUIRED_EFFECT_MISSING,
    TRANQUILIZATION_REQUIRED,
    COOLDOWN_ACTIVE,
    OUT_OF_RANGE,
    TARGET_INVALID,
    OWNER_DENIED,
    ROLE_DENIED,
    ROSTER_FULL,
    TOOL_REQUIRED,
    CHANCE_FAILED,
    UNAVAILABLE
}
```

Map exact known policy and chance codes to this enum. Keep unknown or infrastructure codes at `UNAVAILABLE`; do not display raw codes. Add parameterized strings for every shipped server locale, including the English `powerTooLow={0} is too weak to capture {1}. Requires capture power {2}.`.

- [ ] **Step 4: Run the focused test to verify it passes**

Run: `./mvnw -Dtest=CaptureFeedbackTextTest test`

Expected: PASS.

- [ ] **Step 5: Commit the isolated deliverable**

```bash
git add src/main/java/com/alechilles/alecstamework/items/CaptureFeedbackReason.java \
  src/main/java/com/alechilles/alecstamework/items/CaptureFeedbackText.java \
  src/main/resources/Server/Languages/en-US/server.lang \
  src/test/java/com/alechilles/alecstamework/items/CaptureFeedbackTextTest.java
git commit -m "Feat: define capture feedback reasons"
```

### Task 2: Exact denial propagation through ordinary and bonded capture

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/items/SpawnerCapturePolicyService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/SpawnerCaptureRollService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/SpawnerFeatureHandler.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/BondedCompanionCaptureRoute.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/BondedCompanionCaptureAuthor.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/BondedCompanionCaptureFeedbackDispatcher.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/SpawnerCapturePolicyFeedbackTest.java`
- Test: `src/test/java/com/alechilles/alecstamework/items/BondedCompanionCaptureFeedbackDispatcherTest.java`

**Interfaces:**
- Consumes: `CaptureFeedbackReason` and policy/chance evaluation facts.
- Produces: one reason-bearing denial result from policy/roll evaluation and `BondedCompanionCaptureFeedbackDispatcher.failure(..., CaptureFeedbackReason)` for terminal roster feedback.

- [ ] **Step 1: Write failing regression tests**

```java
@Test
void belowMinimumPowerIsNotCollapsedIntoAdmissionDenied() {
    var decision = BondedCompanionCaptureRoute.denialFor(
            new SpawnerCaptureChanceService.Evaluation(
                    Outcome.DENIED, "capture-power-below-minimum", 0, false, 0, null));

    assertEquals(CaptureFeedbackReason.POWER_TOO_LOW, decision);
}

@Test
void rosterFailureUsesTheSpecificPowerMessage() {
    var sink = new RecordingSink();
    var feedback = new BondedCompanionCaptureFeedbackDispatcher(sink);

    feedback.failure(null, null, CaptureFeedbackReason.POWER_TOO_LOW);

    assertEquals(1, sink.messages.size());
    assertEquals("tamework.ui.notifications.capture.powerTooLow", sink.messages.getFirst().key());
}
```

- [ ] **Step 2: Run the focused tests to verify they fail**

Run: `./mvnw -Dtest=SpawnerCapturePolicyFeedbackTest,BondedCompanionCaptureFeedbackDispatcherTest test`

Expected: FAIL because exact denial propagation and the reason-bearing dispatcher overload do not exist.

- [ ] **Step 3: Implement minimal propagation**

Replace boolean-only policy decisions with a compact result that carries `allowed`, `CaptureFeedbackReason`, and available contextual values (health threshold, distance, required effect, capture power). Keep the existing boolean methods as delegating compatibility wrappers only where an interaction API requires them. Make the ordinary route present the result once when an interaction is denied.

In the bonded route, map `BondedAdmissionEvidence` and `SpawnerCaptureRollService.Resolution.evaluation().reason()` to the same reason. Pass that reason to `BondedCompanionCaptureAuthor.reject` and then to the dispatcher. Preserve special durable statuses for capacity, ownership, role, tool, and persistence outcomes by mapping each to a specific feedback reason instead of `ADMISSION_DENIED`.

- [ ] **Step 4: Run the focused tests to verify they pass**

Run: `./mvnw -Dtest=SpawnerCapturePolicyFeedbackTest,BondedCompanionCaptureFeedbackDispatcherTest test`

Expected: PASS, with the existing tranquilization and required-effect tests still passing.

- [ ] **Step 5: Commit the isolated deliverable**

```bash
git add src/main/java/com/alechilles/alecstamework/items/SpawnerCapturePolicyService.java \
  src/main/java/com/alechilles/alecstamework/items/SpawnerCaptureRollService.java \
  src/main/java/com/alechilles/alecstamework/items/BondedCompanionCaptureRoute.java \
  src/main/java/com/alechilles/alecstamework/items/BondedCompanionCaptureAuthor.java \
  src/main/java/com/alechilles/alecstamework/items/BondedCompanionCaptureFeedbackDispatcher.java \
  src/test/java/com/alechilles/alecstamework/items/SpawnerCapturePolicyFeedbackTest.java \
  src/test/java/com/alechilles/alecstamework/items/BondedCompanionCaptureFeedbackDispatcherTest.java
git commit -m "Fix: explain capture denials"
```

### Task 3: Prominent roster-capture success notification

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/ui/TameworkUiMessageService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/BondedCompanionCaptureAdmissionService.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/BondedCompanionCaptureRoute.java`
- Modify: `src/main/java/com/alechilles/alecstamework/items/BondedCompanionCaptureFeedbackDispatcher.java`
- Modify: `src/main/resources/Server/Languages/*/server.lang`
- Test: `src/test/java/com/alechilles/alecstamework/items/BondedCompanionCaptureFeedbackDispatcherTest.java`

**Interfaces:**
- Consumes: the live NPC label, required command-item config, and confirmed bonded capture completion.
- Produces: `TameworkUiMessageService.show(player, primary, secondary, NotificationStyle.Success)` after a successful durable roster capture.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void appliedRosterCaptureSendsAStyledTwoLineSuccessNotice() {
    var sink = new RecordingSink();
    var feedback = new BondedCompanionCaptureFeedbackDispatcher(sink);
    var context = context("Nordic Drake", "Draconic Roster");

    feedback.success(intent(), context);

    assertEquals(new CaptureSuccessNotice("Nordic Drake captured",
            "Nordic Drake has been added to your Draconic Roster"),
            sink.successNotices.getFirst());
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./mvnw -Dtest=BondedCompanionCaptureFeedbackDispatcherTest test`

Expected: FAIL because completion context has no presentation fields and success does not send a notice.

- [ ] **Step 3: Implement the minimal success presentation**

Extend `TameworkUiMessageService` with the base-game two-message overload and use `NotificationStyle.Success`. Extend the bonded completion context with normalized NPC and command-item labels. Resolve those labels before the durable operation: use `SpawnerNpcIdentityService` for the target and `CommandItemDisplayResolver` plus the required command-item config's first item ID for the roster tool. If either value is unavailable, use the localized fallback label. Have the dispatcher send one success notice only after its effect and source-item finalization both succeed.

- [ ] **Step 4: Run the focused test to verify it passes**

Run: `./mvnw -Dtest=BondedCompanionCaptureFeedbackDispatcherTest test`

Expected: PASS, while existing success finalization failure tests still show only their actionable failure message.

- [ ] **Step 5: Commit the isolated deliverable**

```bash
git add src/main/java/com/alechilles/alecstamework/ui/TameworkUiMessageService.java \
  src/main/java/com/alechilles/alecstamework/items/BondedCompanionCaptureAdmissionService.java \
  src/main/java/com/alechilles/alecstamework/items/BondedCompanionCaptureRoute.java \
  src/main/java/com/alechilles/alecstamework/items/BondedCompanionCaptureFeedbackDispatcher.java \
  src/main/resources/Server/Languages/en-US/server.lang \
  src/test/java/com/alechilles/alecstamework/items/BondedCompanionCaptureFeedbackDispatcherTest.java
git commit -m "Feat: notify successful roster captures"
```

### Task 4: Full verification and player documentation

**Files:**
- Modify: `CHANGELOG.md`
- Modify: relevant player-facing capture guide under `wiki/Player-Guides` if one describes roster capture feedback.

- [ ] **Step 1: Add player-facing release notes**

Add concise entries stating that roster capture now confirms the companion and command roster, and denied captures explain the exact blocker.

- [ ] **Step 2: Run focused capture tests**

Run: `./mvnw -Dtest=CaptureFeedbackTextTest,SpawnerCapturePolicyFeedbackTest,BondedCompanionCaptureFeedbackDispatcherTest,BondedCompanionCapturePipelineTest test`

Expected: PASS.

- [ ] **Step 3: Run full test and safety checks**

Run:

```bash
./mvnw test
rg "PlayerRef\\.getComponent\\(Player|getComponent\\(Player\\.getComponentType\\(\\)\\)|Universe\\.get\\(\\).*getPlayers" -n src/main/java
git diff --check
```

Expected: Maven passes; the grep has no new matches in capture runtime paths; `git diff --check` prints nothing.

- [ ] **Step 4: Commit the final documentation and verification-ready changes**

```bash
git add CHANGELOG.md wiki/Player-Guides
git commit -m "Docs: explain capture feedback"
```
