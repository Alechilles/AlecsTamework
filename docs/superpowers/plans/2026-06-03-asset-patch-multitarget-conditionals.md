# Asset Patch Multi-Target Conditionals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let one optional asset patch apply the same operations to multiple targets and let patch authors gate patches with conditions such as "only when this mod/asset pack is installed."

**Architecture:** Keep the existing one-target engine contract intact. Expand multi-target patch files into multiple `AssetPatchDefinition` instances during scanning, and evaluate `When` conditions before adding definitions. Add a small condition parser/evaluator beside the current patch parser instead of growing `AssetPatchService`.

**Tech Stack:** Java, Gson, JUnit 5, Hytale `AssetPack`, existing `AssetPatchScanner` / `AssetPatchDefinition` / `AssetPatchEngine` package.

---

## File Structure

- Modify: `src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchDefinition.java`
  - Parse `Target` or `Targets`; expose a `parseAll(...)` helper that returns one definition per target.
- Create: `src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchCondition.java`
  - Parse the optional `When` object and evaluate it against registered pack names.
- Create: `src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchConditionContext.java`
  - Immutable context containing installed asset pack IDs and generated pack ID.
- Modify: `src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchScanner.java`
  - Build the condition context once per scan, call `parseAll`, and record skipped conditional patches.
- Modify: `src/test/java/com/alechilles/alecstamework/assets/patches/AssetPatchScannerTest.java`
  - Cover multi-target expansion and conditional skipping/application.
- Create: `src/test/java/com/alechilles/alecstamework/assets/patches/AssetPatchConditionTest.java`
  - Cover condition parsing, `All` / `Any` / `Not`, and invalid condition shapes.
- Modify: `src/main/resources/Server/Tamework/Patches/Examples/Tamework_Example_Patch.json`
  - Add a small condition example only if it remains clear and does not hide the primary sample.
- Modify: `README.md`
  - Update the public asset patch summary with multi-target and conditional support.
- Modify or create external wiki source under `C:\Users\22ale\AppData\Roaming\Hytale\My Mod Docs` if the Asset Patches guide is present.
  - Document the schema, examples, and compatibility notes.
- Modify: `CHANGELOG.md`
  - Add an unreleased player/modder-facing entry.

## Schema Decision

Existing patches keep working:

```json
{
  "Id": "SinglePatch",
  "Target": "Server/NPC/Roles/_Core/Templates/AH_Template_Livestock.json",
  "Operations": []
}
```

New multi-target patches use `Targets`:

```json
{
  "Id": "LivestockSharedTameworkPatch",
  "Targets": [
    "Server/NPC/Roles/_Core/Templates/AH_Template_Cow.json",
    "Server/NPC/Roles/_Core/Templates/AH_Template_Sheep.json"
  ],
  "Operations": []
}
```

Rules:
- `Target` and `Targets` are mutually exclusive.
- `Targets` must be a non-empty array of non-empty strings.
- Duplicate normalized targets in one patch file should fail parse with a clear message.
- Each target receives a separate `AssetPatchDefinition` using the same `Id`, `Priority`, `Enabled`, `When`, and operation list.

New conditional patches use `When`:

```json
{
  "Id": "AnimalHusbandryOnlyPatch",
  "Targets": [
    "Server/NPC/Roles/_Core/Templates/AH_Template_Cow.json",
    "Server/NPC/Roles/_Core/Templates/AH_Template_Sheep.json"
  ],
  "When": {
    "ModInstalled": "alec:animal_husbandry"
  },
  "Operations": []
}
```

Supported initial condition leaves:
- `ModInstalled`: string asset pack ID. This is the direct implementation of "if X mod is installed" because the patch system scans registered Hytale asset packs.

Supported combinators:

```json
{
  "When": {
    "All": [
      { "ModInstalled": "alec:animal_husbandry" },
      { "Not": { "ModInstalled": "conflicting:mod" } }
    ]
  }
}
```

Rules:
- Missing `When` means apply.
- `All` and `Any` must be non-empty arrays of condition objects.
- `Not` must contain exactly one condition object.
- A condition object must contain exactly one recognized key.
- Failed conditions add a skipped status row like `AnimalHusbandryOnlyPatch condition not met: ModInstalled alec:animal_husbandry`.
- Condition checks must ignore the generated patch pack itself.

## Task 1: Multi-Target Scanner Tests

**Files:**
- Modify: `src/test/java/com/alechilles/alecstamework/assets/patches/AssetPatchScannerTest.java`

- [ ] **Step 1: Add a failing test for `Targets` expansion**

Add this test to `AssetPatchScannerTest`:

```java
@Test
void expandsPatchDefinitionsForMultipleTargets() throws Exception {
    Path packRoot = tempDir.resolve("multi-target-pack");
    Path patchDir = packRoot.resolve(AssetPatchScanner.PATCH_DIRECTORY).resolve("Shared");
    Files.createDirectories(patchDir);
    Files.writeString(
            patchDir.resolve("SharedPatch.json"),
            """
                    {
                      "Id": "SharedPatch",
                      "Targets": [
                        "Server/NPC/Roles/_Core/Templates/Cow.json",
                        "Server/NPC/Roles/_Core/Templates/Sheep.json"
                      ],
                      "Operations": [
                        {
                          "Id": "flag",
                          "Op": "Add",
                          "Path": "/Patched",
                          "Value": true
                        }
                      ]
                    }
                    """,
            StandardCharsets.UTF_8
    );

    AssetPatchStatus status = new AssetPatchStatus();
    List<AssetPatchDefinition> definitions = new AssetPatchScanner(null)
            .scan(List.of(pack("ModPack", packRoot)), "GeneratedPack", status);

    assertEquals(2, definitions.size());
    assertEquals("Server/NPC/Roles/_Core/Templates/Cow.json", definitions.get(0).getTarget());
    assertEquals("Server/NPC/Roles/_Core/Templates/Sheep.json", definitions.get(1).getTarget());
    assertEquals("SharedPatch", definitions.get(0).getId());
    assertEquals("SharedPatch", definitions.get(1).getId());
    assertEquals(0, status.getFailed().size());
}
```

- [ ] **Step 2: Run the targeted test and verify failure**

Run:

```powershell
.\mvnw.cmd '-Dtest=AssetPatchScannerTest#expandsPatchDefinitionsForMultipleTargets' test
```

Expected: FAIL because `Targets` is not currently parsed and `Target` is required.

## Task 2: Multi-Target Parser Implementation

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchDefinition.java`
- Modify: `src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchScanner.java`

- [ ] **Step 1: Add `parseAll` and target list parsing**

Implement this shape in `AssetPatchDefinition`:

```java
@Nonnull
public static List<AssetPatchDefinition> parseAll(@Nonnull JsonObject root,
                                                  @Nonnull String sourcePack,
                                                  @Nonnull String sourcePath) {
    String fallbackId = sourcePack + ":" + sourcePath;
    String id = readString(root, "Id", fallbackId);
    int priority = readInt(root, "Priority", 0);
    boolean enabled = readBoolean(root, "Enabled", true);
    List<AssetPatchOperation> operations = readOperations(root, id);
    List<String> targets = readTargets(root, id);
    List<AssetPatchDefinition> definitions = new ArrayList<>();
    for (String target : targets) {
        definitions.add(new AssetPatchDefinition(
                id,
                normalizeAssetPath(target),
                priority,
                enabled,
                operations,
                sourcePack,
                sourcePath
        ));
    }
    return definitions;
}
```

Keep the existing `parse(...)` as a compatibility wrapper for tests and direct callers:

```java
@Nonnull
public static AssetPatchDefinition parse(@Nonnull JsonObject root,
                                         @Nonnull String sourcePack,
                                         @Nonnull String sourcePath) {
    List<AssetPatchDefinition> definitions = parseAll(root, sourcePack, sourcePath);
    if (definitions.isEmpty()) {
        throw new IllegalArgumentException("Patch '" + sourcePack + ":" + sourcePath + "' produced no definitions.");
    }
    return definitions.getFirst();
}
```

Add `readTargets(...)` as a private helper. It should reject both `Target` and `Targets`, reject neither, reject non-string array entries, and reject duplicate normalized target paths.

- [ ] **Step 2: Wire scanner to add all definitions**

Replace the single-definition parse in `AssetPatchScanner.parsePatchFile(...)` with:

```java
List<AssetPatchDefinition> parsed =
        AssetPatchDefinition.parseAll((JsonObject) element, pack.getName(), sourcePath);
for (AssetPatchDefinition definition : parsed) {
    if (definition.isEnabled()) {
        definitions.add(definition);
    } else {
        status.addSkipped(definition.getId() + " disabled");
    }
}
```

- [ ] **Step 3: Run the targeted scanner tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=AssetPatchScannerTest' test
```

Expected: PASS.

- [ ] **Step 4: Commit**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchDefinition.java src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchScanner.java src/test/java/com/alechilles/alecstamework/assets/patches/AssetPatchScannerTest.java
git commit -m 'Feat: support multi-target asset patches'
```

## Task 3: Conditional Parser Tests

**Files:**
- Create: `src/test/java/com/alechilles/alecstamework/assets/patches/AssetPatchConditionTest.java`
- Modify: `src/test/java/com/alechilles/alecstamework/assets/patches/AssetPatchScannerTest.java`

- [ ] **Step 1: Add condition unit tests**

Create `AssetPatchConditionTest` with tests covering:

```java
@Test
void modInstalledMatchesRegisteredPack() {
    AssetPatchCondition condition = AssetPatchCondition.parse(object("""
            {
              "ModInstalled": "alec:animal_husbandry"
            }
            """));
    AssetPatchConditionContext context = new AssetPatchConditionContext(
            "generated:patches",
            List.of("alec:animal_husbandry", "other:mod", "generated:patches")
    );

    assertTrue(condition.matches(context));
    assertEquals("ModInstalled alec:animal_husbandry", condition.describe());
}
```

Also add tests for:
- missing pack returns false
- `All` requires every child
- `Any` requires at least one child
- `Not` negates the child
- an object with both `ModInstalled` and `Any` throws `IllegalArgumentException`
- empty `All` or `Any` throws `IllegalArgumentException`

Use this helper in the test:

```java
private static JsonObject object(String json) {
    return JsonParser.parseString(json).getAsJsonObject();
}
```

- [ ] **Step 2: Add scanner tests for conditional skip/apply**

Add two tests to `AssetPatchScannerTest`:
- `skipsPatchWhenConditionIsNotMet`
- `addsPatchWhenConditionIsMet`

The skipped case should assert:

```java
assertEquals(0, definitions.size());
assertEquals(1, status.getSkipped().size());
assertTrue(status.getSkipped().getFirst().contains("condition not met"));
```

- [ ] **Step 3: Run targeted tests and verify failure**

Run:

```powershell
.\mvnw.cmd '-Dtest=AssetPatchConditionTest,AssetPatchScannerTest' test
```

Expected: FAIL because the condition classes and scanner wiring do not exist yet.

## Task 4: Conditional Parser Implementation

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchCondition.java`
- Create: `src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchConditionContext.java`
- Modify: `src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchScanner.java`

- [ ] **Step 1: Implement `AssetPatchConditionContext`**

Create an immutable final class:

```java
public final class AssetPatchConditionContext {
    private final String generatedPackId;
    private final Set<String> installedPacks;

    public AssetPatchConditionContext(@Nonnull String generatedPackId,
                                      @Nonnull Iterable<String> installedPacks) {
        this.generatedPackId = generatedPackId;
        Set<String> packs = new LinkedHashSet<>();
        for (String pack : installedPacks) {
            if (pack != null && !pack.isBlank() && !generatedPackId.equals(pack)) {
                packs.add(pack.trim());
            }
        }
        this.installedPacks = Set.copyOf(packs);
    }

    public boolean hasInstalledPack(@Nonnull String packId) {
        return installedPacks.contains(packId);
    }
}
```

- [ ] **Step 2: Implement `AssetPatchCondition`**

Create a final class with:
- `static AssetPatchCondition always()`
- `static AssetPatchCondition parse(JsonObject object)`
- `boolean matches(AssetPatchConditionContext context)`
- `String describe()`

Internally, use private leaf/composite implementations or an enum-backed type. Keep this file under 500 lines and do not touch `AssetPatchEngine`.

- [ ] **Step 3: Wire scanner condition evaluation**

In `AssetPatchScanner.scan(...)`, build context before scanning packs:

```java
AssetPatchConditionContext conditionContext = new AssetPatchConditionContext(
        generatedPackId,
        packs.stream()
                .filter(pack -> pack != null)
                .map(AssetPack::getName)
                .toList()
);
```

Pass `conditionContext` into `scanPack(...)` and `parsePatchFile(...)`.

In `parsePatchFile(...)`, before `parseAll`, read optional `When`:

```java
AssetPatchCondition condition = AssetPatchCondition.parseOptional((JsonObject) element);
if (!condition.matches(conditionContext)) {
    String id = AssetPatchDefinition.readString((JsonObject) element, "Id", pack.getName() + ":" + sourcePath);
    status.addSkipped(id + " condition not met: " + condition.describe());
    return;
}
```

Add `parseOptional(...)` if it keeps scanner code cleaner:

```java
@Nonnull
public static AssetPatchCondition parseOptional(@Nonnull JsonObject root) {
    JsonElement raw = root.get("When");
    if (raw == null || raw.isJsonNull()) {
        return always();
    }
    if (!raw.isJsonObject()) {
        throw new IllegalArgumentException("When must be an object.");
    }
    return parse(raw.getAsJsonObject());
}
```

- [ ] **Step 4: Run targeted tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=AssetPatchConditionTest,AssetPatchScannerTest' test
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```powershell
git add src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchCondition.java src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchConditionContext.java src/main/java/com/alechilles/alecstamework/assets/patches/AssetPatchScanner.java src/test/java/com/alechilles/alecstamework/assets/patches/AssetPatchConditionTest.java src/test/java/com/alechilles/alecstamework/assets/patches/AssetPatchScannerTest.java
git commit -m 'Feat: add conditional asset patch gates'
```

## Task 5: End-to-End Patch Engine Safety Tests

**Files:**
- Modify: `src/test/java/com/alechilles/alecstamework/assets/patches/AssetPatchEngineTest.java`

- [ ] **Step 1: Add an engine-level regression for expanded definitions**

Add a test that builds two definitions from one multi-target JSON, applies each definition to its own source JSON, and asserts both receive the same operation. This proves target expansion does not require engine changes.

- [ ] **Step 2: Run patch package tests**

Run:

```powershell
.\mvnw.cmd '-Dtest=com.alechilles.alecstamework.assets.patches.*Test' test
```

Expected: PASS.

- [ ] **Step 3: Commit**

Run:

```powershell
git add src/test/java/com/alechilles/alecstamework/assets/patches/AssetPatchEngineTest.java
git commit -m 'Test: cover expanded asset patch definitions'
```

## Task 6: Examples, Docs, and Changelog

**Files:**
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify or create wiki source under `C:\Users\22ale\AppData\Roaming\Hytale\My Mod Docs`
- Optionally modify: `src/main/resources/Server/Tamework/Patches/Examples/Tamework_Example_Patch.json`

- [ ] **Step 1: Update README patch summary**

Update the feature bullets to mention:
- one patch file can target one asset with `Target` or multiple assets with `Targets`
- `When.ModInstalled` gates optional cross-mod integrations

- [ ] **Step 2: Update wiki/source docs**

Find the source page first:

```powershell
rg -n "Asset Patches|Server/Tamework/Patches|optional asset patch" 'C:\Users\22ale\AppData\Roaming\Hytale\My Mod Docs'
```

If the guide exists, add sections for `Targets` and `When`. If it does not exist locally, add a concise repo doc under `docs/Asset-Patches.md` and link it from `README.md`.

- [ ] **Step 3: Add changelog entry**

Add an `Unreleased` entry with player/modder wording:

```markdown
- Added multi-target and conditional optional asset patches so integration authors can apply the same patch to several assets and gate patches on installed mods.
```

- [ ] **Step 4: Commit**

Run:

```powershell
git add README.md CHANGELOG.md src/main/resources/Server/Tamework/Patches/Examples/Tamework_Example_Patch.json
if (Test-Path docs/Asset-Patches.md) { git add docs/Asset-Patches.md }
git commit -m 'Docs: document conditional multi-target patches'
```

If the wiki source is outside the repo, do not include it in this commit. Summarize the external path changed in the final response.

## Task 7: Full Validation

**Files:**
- No new files unless validation exposes failures.

- [ ] **Step 1: Run all Java tests**

Run:

```powershell
.\mvnw.cmd test
```

Expected: PASS.

- [ ] **Step 2: Run thread-safety grep required by AGENTS.md**

Run:

```powershell
rg "PlayerRef\.getComponent\(Player|getComponent\(Player\.getComponentType\(\)\)|Universe\.get\(\).*getPlayers" -n src/main/java
```

Expected: no new matches caused by this patch-system work.

- [ ] **Step 3: Inspect git status**

Run:

```powershell
git status --short
```

Expected: only intentional files are modified, or clean after commits.

## Self-Review Notes

- Backward compatibility is preserved because existing `Target` patch files keep parsing.
- The engine remains target-local and does not need to understand multi-target patches.
- Conditions are evaluated during scan, before target resolution and publication, which avoids false "missing target" failures when an optional mod is absent.
- The first condition leaf is intentionally `ModInstalled` only. Add `AssetExists` later if real integrations need asset-level gates, because that requires deciding whether existence checks should inspect winning targets or any registered pack.
- This work touches no runtime ECS/player access paths, so the thread-affinity risk is low.
