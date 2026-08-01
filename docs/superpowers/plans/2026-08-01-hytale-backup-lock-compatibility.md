# Hytale Backup Lock Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent Tamework's held persistence lock files from causing base-game Hytale backups to fail on Windows while preserving single-process ownership and safe upgrades from older Tamework builds.

**Architecture:** Move every actively held Tamework file lock to a file whose final path element is exactly `LOCK`, matching Hytale 0.5.7's backup exclusion contract. Before acquiring the engine lock, atomically retire the former engine lock file into an empty directory sentinel so an older Tamework build cannot silently start against an upgraded world. Keep engine manifest lineage and import behavior unchanged.

**Tech Stack:** Java 25, Java NIO `FileChannel`/`FileLock`, JUnit 5, Maven Wrapper, Hytale 0.5.7 Shared Source contract

## Global Constraints

- Preserve existing engine-owner, manifest, clean/unclean shutdown, and import serialization semantics.
- Do not change SQLite transaction handling, backup scheduling, save orchestration, or public asset schemas.
- Treat an unavailable legacy engine lock as `persistence_engine_lock_unavailable` and fail closed.
- Never delete or replace a legacy path that is not a regular file or directory.
- Keep unrelated working-tree edits untouched.
- Use Git Bash for all commands and commit each task independently.

---

### Task 1: Make the persistence-engine lease backup-compatible

**Files:**
- Create: `src/main/java/com/alechilles/alecstamework/persistence/control/LegacyEngineLockSentinel.java`
- Modify: `src/main/java/com/alechilles/alecstamework/persistence/control/PersistenceEngineLease.java`
- Modify: `src/test/java/com/alechilles/alecstamework/persistence/control/PersistenceEngineLeaseTest.java`
- Verify: `src/test/java/com/alechilles/alecstamework/persistence/control/PersistenceEngineLeaseProcessTest.java`

- [ ] **Step 1: Add failing compatibility and migration tests**

Add tests proving:

1. An acquired lease creates a held `<data>/LOCK` file, and a faithful Hytale filter excludes it:

```java
try (PersistenceEngineLease lease = PersistenceEngineLease.acquireReplacement(tempDir)) {
    Path lockPath = tempDir.resolve(PersistenceEngineLease.LOCK_FILENAME);
    assertTrue(Files.isRegularFile(lockPath));
    try (Stream<Path> paths = Files.walk(tempDir)) {
        List<Path> backupFiles = paths
                .filter(Files::isRegularFile)
                .filter(path -> !path.endsWith("LOCK"))
                .toList();
        assertFalse(backupFiles.contains(lockPath));
        for (Path backupFile : backupFiles) {
            assertDoesNotThrow(() -> Files.readAllBytes(backupFile));
        }
    }
}
```

2. A former `.tamework-persistence-engine.lock` regular file is replaced by an empty directory sentinel before the new lease is acquired.
3. A held former lock remains a regular file and acquisition fails with `persistence_engine_lock_unavailable`.
4. An already-created legacy directory sentinel is retained.
5. A legacy symbolic link, when supported by the test filesystem, is rejected without replacement.

Run:

```bash
./mvnw -Dtest=PersistenceEngineLeaseTest test
```

Expected: new tests fail because the active file is still named `.tamework-persistence-engine.lock` and no sentinel migration exists.

- [ ] **Step 2: Implement the focused legacy-lock migration helper**

Create a package-private `LegacyEngineLockSentinel` with class-level JavaDoc and these responsibilities only:

```java
final class LegacyEngineLockSentinel {
    static final String LEGACY_LOCK_FILENAME =
            ".tamework-persistence-engine.lock";

    static void prepare(Path dataDirectory) throws IOException {
        Path legacyPath = dataDirectory.resolve(LEGACY_LOCK_FILENAME);
        if (Files.notExists(legacyPath, LinkOption.NOFOLLOW_LINKS)) {
            createDirectorySentinel(legacyPath);
            return;
        }
        if (Files.isDirectory(legacyPath, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isRegularFile(legacyPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("persistence_engine_legacy_lock_path_invalid");
        }
        retireRegularFile(legacyPath);
    }
}
```

`retireRegularFile` must open the old path for writing, call `tryLock()`, map both a `null` result and `OverlappingFileLockException` to `IllegalStateException("persistence_engine_lock_unavailable")`, release and close the lock/channel, then delete the file and create the directory sentinel. If any lock remains held, the old file must not be deleted.

Handle a create race only by accepting the resulting path when it is a real directory under `NOFOLLOW_LINKS`; otherwise propagate the failure.

- [ ] **Step 3: Acquire the new engine lock only after migration**

In `PersistenceEngineLease`:

```java
public static final String LOCK_FILENAME = "LOCK";
```

After `Files.createDirectories(directory)`, call `LegacyEngineLockSentinel.prepare(directory)`, then open and lock `directory.resolve(LOCK_FILENAME)` using the existing lifecycle and exception cleanup. Do not alter manifest or shutdown behavior.

- [ ] **Step 4: Verify engine lease behavior**

Run:

```bash
./mvnw -Dtest=PersistenceEngineLeaseTest,PersistenceEngineLeaseProcessTest test
```

Expected: PASS, including same-process and cross-process exclusion.

- [ ] **Step 5: Commit the engine-lock change**

```bash
git add src/main/java/com/alechilles/alecstamework/persistence/control/LegacyEngineLockSentinel.java \
  src/main/java/com/alechilles/alecstamework/persistence/control/PersistenceEngineLease.java \
  src/test/java/com/alechilles/alecstamework/persistence/control/PersistenceEngineLeaseTest.java
git commit -m "Fix: make persistence engine lock backup-compatible"
```

---

### Task 2: Make the import admission lock backup-compatible

**Files:**
- Modify: `src/main/java/com/alechilles/alecstamework/persistence/migration/ImportAdmissionLock.java`
- Modify: `src/test/java/com/alechilles/alecstamework/persistence/migration/PublicPersistenceImporterTest.java`

- [ ] **Step 1: Add a failing import-lock backup-filter test**

Add package-private constants for test visibility in the production class plan:

```java
static final String LOCK_DIRECTORY = ".tamework-import-lock";
static final String LOCK_FILENAME = "LOCK";
```

Test that an acquired admission lock exists at `<data>/.tamework-import-lock/LOCK`, is excluded by `.filter(path -> !path.endsWith("LOCK"))`, and still causes a second acquisition to fail with `persistence_import_lock_unavailable`.

Run:

```bash
./mvnw -Dtest=PublicPersistenceImporterTest test
```

Expected: new test fails because the current lock is `<data>/.tamework-import.lock`.

- [ ] **Step 2: Nest the import lock under a stable directory**

Change `ImportAdmissionLock.acquire` to create `<data>/.tamework-import-lock/` and lock the exact child path `LOCK`:

```java
Path lockDirectory = targetDirectory.resolve(LOCK_DIRECTORY);
Files.createDirectories(lockDirectory);
Path lockPath = lockDirectory.resolve(LOCK_FILENAME);
```

Keep the existing `tryLock`, unavailable-error mapping, cleanup, and close semantics. Leave any former `.tamework-import.lock` file inert; do not delete or reinterpret it.

- [ ] **Step 3: Verify import serialization and filtering**

Run:

```bash
./mvnw -Dtest=PublicPersistenceImporterTest test
```

Expected: PASS.

- [ ] **Step 4: Commit the import-lock change**

```bash
git add src/main/java/com/alechilles/alecstamework/persistence/migration/ImportAdmissionLock.java \
  src/test/java/com/alechilles/alecstamework/persistence/migration/PublicPersistenceImporterTest.java
git commit -m "Fix: make import admission lock backup-compatible"
```

---

### Task 3: Document and fully verify the fix

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `wiki/Developer-Documentation/Data-and-Persistence/Persistence-Sqlite-and-Data-Paths.md`
- Sync if present: external Alec's Tamework wiki counterpart under `C:/Users/22ale/AppData/Roaming/Hytale/My Mod Docs`

- [ ] **Step 1: Update player-facing release notes**

Under the current `3.0.0` `Fixed` section, add:

```markdown
- Fixed automatic Hytale world backups failing on Windows while Tamework persistence was active. Tamework's process locks now use Hytale's backup-excluded `LOCK` layout.
```

- [ ] **Step 2: Document the data-directory entries**

Explain that:

- `Tamework/Data/LOCK` and `Tamework/Data/.tamework-import-lock/LOCK` are ephemeral process-ownership files that Hytale excludes from backups and Tamework recreates.
- `Tamework/Data/.tamework-persistence-engine.lock/` may remain as an empty upgrade sentinel. It contains no gameplay data and intentionally prevents older Tamework builds from reopening an upgraded world.

Use the `hytale-docs-sync` skill to locate and update the external wiki counterpart if one exists. Do not create a guessed external path.

- [ ] **Step 3: Run focused persistence safety suites**

```bash
./mvnw -Dtest=PersistenceEngineLeaseTest,PersistenceEngineLeaseProcessTest,PublicPersistenceImporterTest,ReplacementPersistenceArchitectureGuardTest,PersistenceProcessCrashMatrixTest test
```

Expected: PASS.

- [ ] **Step 4: Run the full project test suite**

```bash
./mvnw test
```

Expected: PASS. If unrelated user work causes a failure, preserve it and report the exact failing test separately.

- [ ] **Step 5: Review the final diff against the approved design**

Check all of the following before claiming completion:

- Both actively held lock files end with the exact final path element `LOCK`.
- The former engine lock becomes an empty directory only after exclusive ownership was proven.
- An unavailable former engine lock fails closed.
- Manifest lineage and clean/unclean shutdown code is unchanged.
- Import concurrency remains enforced.
- No backup scheduling, SQLite coordination, version, packaging, or release code changed.
- No placeholder text, skipped assertions, or unrelated working-tree edits entered the commits.

Run:

```bash
git diff HEAD~2 --check
git status --short
```

- [ ] **Step 6: Commit documentation**

```bash
git add CHANGELOG.md \
  wiki/Developer-Documentation/Data-and-Persistence/Persistence-Sqlite-and-Data-Paths.md
git commit -m "Docs: explain backup-compatible persistence locks"
```
