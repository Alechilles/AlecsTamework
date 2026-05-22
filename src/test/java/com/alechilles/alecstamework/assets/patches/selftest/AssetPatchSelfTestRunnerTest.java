package com.alechilles.alecstamework.assets.patches.selftest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.alechilles.alecstamework.assets.patches.AssetPatchDefinition;
import com.alechilles.alecstamework.assets.patches.AssetPatchEngine;
import com.alechilles.alecstamework.assets.patches.AssetPatchStatus;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class AssetPatchSelfTestRunnerTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Test
    void runVerifiesGeneratedOutputsAndReloadClassification(@TempDir Path tempDir) {
        AssetPatchSelfTestPack pack = new AssetPatchSelfTestPack(tempDir.resolve("data"), null, null);
        FakeReloadHandle reloadHandle = new FakeReloadHandle(tempDir.resolve("generated"), pack);
        AssetPatchSelfTestRunner runner = new AssetPatchSelfTestRunner(
                pack,
                reloadHandle,
                AssetPatchSelfTestCase.defaultCases(),
                null
        );

        AssetPatchSelfTestResult result = runner.run();

        assertTrue(result.passed());
        assertEquals(5, result.generatedCount());
        assertEquals(5, result.hotReloadedCount());
        assertEquals(0, result.restartRequiredCount());
        assertTrue(result.cases().stream().anyMatch(caseResult ->
                caseResult.id().equals("npc-template")
                        && caseResult.reloadOutcome() == AssetPatchSelfTestResult.ReloadOutcome.HOT_RELOADED
        ));
        assertTrue(result.cases().stream().anyMatch(caseResult ->
                caseResult.id().equals("item-action")
                        && caseResult.reloadOutcome() == AssetPatchSelfTestResult.ReloadOutcome.HOT_RELOADED
        ));
        assertTrue(result.cases().stream().anyMatch(caseResult ->
                caseResult.id().equals("tamework-config")
                        && caseResult.reloadOutcome() == AssetPatchSelfTestResult.ReloadOutcome.HOT_RELOADED
        ));
        assertTrue(result.cases().stream().anyMatch(caseResult ->
                caseResult.id().equals("particle-system")
                        && caseResult.reloadOutcome() == AssetPatchSelfTestResult.ReloadOutcome.HOT_RELOADED
        ));
        assertTrue(result.cases().stream().anyMatch(caseResult ->
                caseResult.id().equals("common-asset")
                        && caseResult.reloadOutcome() == AssetPatchSelfTestResult.ReloadOutcome.HOT_RELOADED
        ));
    }

    @Test
    void runReportsFailedGeneratedCheck(@TempDir Path tempDir) {
        AssetPatchSelfTestPack pack = new AssetPatchSelfTestPack(tempDir.resolve("data"), null, null);
        FakeReloadHandle reloadHandle = new FakeReloadHandle(tempDir.resolve("generated"), pack);
        reloadHandle.skipGeneratedTargets(Set.of("particle-system"));
        AssetPatchSelfTestRunner runner = new AssetPatchSelfTestRunner(
                pack,
                reloadHandle,
                AssetPatchSelfTestCase.defaultCases(),
                null
        );

        AssetPatchSelfTestResult result = runner.run();

        assertFalse(result.passed());
        assertTrue(result.cases().stream().anyMatch(caseResult ->
                caseResult.id().equals("particle-system")
                        && !caseResult.passed()
                        && caseResult.reloadOutcome() == AssetPatchSelfTestResult.ReloadOutcome.FAILED
                        && caseResult.detail().contains("missing or unreadable generated output")
        ));
    }

    @Test
    void runReportsReloadFailureEvenWhenRestartRequiredIsAccepted(@TempDir Path tempDir) {
        AssetPatchSelfTestPack pack = new AssetPatchSelfTestPack(tempDir.resolve("data"), null, null);
        FakeReloadHandle reloadHandle = new FakeReloadHandle(tempDir.resolve("generated"), pack);
        reloadHandle.failReloadTargets(Set.of("item-action"));
        AssetPatchSelfTestRunner runner = new AssetPatchSelfTestRunner(
                pack,
                reloadHandle,
                AssetPatchSelfTestCase.defaultCases(),
                null
        );

        AssetPatchSelfTestResult result = runner.run();

        assertFalse(result.passed());
        assertTrue(result.cases().stream().anyMatch(caseResult ->
                caseResult.id().equals("item-action")
                        && !caseResult.passed()
                        && caseResult.reloadOutcome() == AssetPatchSelfTestResult.ReloadOutcome.HOT_RELOADED
                        && caseResult.detail().contains("reload failed")
        ));
    }

    @Test
    void cleanupRemovesFixturesAndReportsGeneratedOutputsRemoved(@TempDir Path tempDir) throws Exception {
        AssetPatchSelfTestPack pack = new AssetPatchSelfTestPack(tempDir.resolve("data"), null, null);
        FakeReloadHandle reloadHandle = new FakeReloadHandle(tempDir.resolve("generated"), pack);
        AssetPatchSelfTestRunner runner = new AssetPatchSelfTestRunner(
                pack,
                reloadHandle,
                AssetPatchSelfTestCase.defaultCases(),
                null
        );
        assertTrue(runner.run().passed());

        AssetPatchSelfTestResult cleanup = runner.cleanup();

        assertTrue(cleanup.passed());
        assertEquals(0, cleanup.generatedCount());
        for (AssetPatchSelfTestCase selfTestCase : AssetPatchSelfTestCase.defaultCases()) {
            assertFalse(Files.exists(pack.resolveRelative(selfTestCase.sourcePath())));
            assertFalse(Files.exists(pack.resolveRelative(selfTestCase.patchPath())));
            assertFalse(Files.exists(reloadHandle.generatedPatchCacheRoot().resolve(selfTestCase.sourcePath())));
        }
    }

    private static final class FakeReloadHandle implements AssetPatchSelfTestReloadHandle {
        private final Path generatedRoot;
        private final AssetPatchSelfTestPack pack;
        private final AssetPatchEngine engine = new AssetPatchEngine();
        private Set<String> skippedCaseIds = Set.of();
        private Set<String> failedReloadCaseIds = Set.of();
        private Set<String> observedHotReloadCaseIds =
                Set.of("npc-template", "item-action", "tamework-config", "particle-system", "common-asset");

        private FakeReloadHandle(@Nonnull Path generatedRoot, @Nonnull AssetPatchSelfTestPack pack) {
            this.generatedRoot = generatedRoot.toAbsolutePath().normalize();
            this.pack = pack;
        }

        void skipGeneratedTargets(@Nonnull Set<String> skippedCaseIds) {
            this.skippedCaseIds = Set.copyOf(skippedCaseIds);
        }

        void failReloadTargets(@Nonnull Set<String> failedReloadCaseIds) {
            this.failedReloadCaseIds = Set.copyOf(failedReloadCaseIds);
        }

        @Override
        @Nonnull
        public AssetPatchStatus reload() {
            AssetPatchStatus status = new AssetPatchStatus();
            for (AssetPatchSelfTestCase selfTestCase : AssetPatchSelfTestCase.defaultCases()) {
                try {
                    Path source = pack.resolveRelative(selfTestCase.sourcePath());
                    Path patch = pack.resolveRelative(selfTestCase.patchPath());
                    Path generated = generatedRoot.resolve(selfTestCase.sourcePath()).normalize();
                    if (!Files.exists(source) || !Files.exists(patch)) {
                        removeGenerated(generated, selfTestCase, status);
                        continue;
                    }
                    if (!skippedCaseIds.contains(selfTestCase.id())) {
                        writeGenerated(source, patch, generated, selfTestCase, status);
                    }
                } catch (Exception ex) {
                    status.addFailed(selfTestCase.id() + ": " + ex.getMessage());
                }
                addReloadOutcome(selfTestCase, status);
                if (failedReloadCaseIds.contains(selfTestCase.id())) {
                    status.addFailed("Failed to hot-reload generated patch target "
                            + selfTestCase.sourcePath()
                            + "; restart required.");
                }
            }
            return status;
        }

        @Override
        @Nonnull
        public Path generatedPatchCacheRoot() {
            return generatedRoot;
        }

        @Override
        @Nonnull
        public Set<String> awaitHotReloadedTargets(@Nonnull Collection<String> targets,
                                                   long sinceSequence,
                                                   @Nonnull Duration timeout) {
            LinkedHashSet<String> observed = new LinkedHashSet<>();
            for (AssetPatchSelfTestCase selfTestCase : AssetPatchSelfTestCase.defaultCases()) {
                if (observedHotReloadCaseIds.contains(selfTestCase.id())
                        && targets.contains(selfTestCase.sourcePath())) {
                    observed.add(selfTestCase.sourcePath());
                }
            }
            return Set.copyOf(observed);
        }

        private void writeGenerated(@Nonnull Path source,
                                    @Nonnull Path patch,
                                    @Nonnull Path generated,
                                    @Nonnull AssetPatchSelfTestCase selfTestCase,
                                    @Nonnull AssetPatchStatus status) {
            try {
                JsonObject sourceJson = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                AssetPatchDefinition definition = AssetPatchDefinition.parse(
                        JsonParser.parseString(Files.readString(patch, StandardCharsets.UTF_8)).getAsJsonObject(),
                        "selftest",
                        selfTestCase.patchPath()
                );
                JsonObject patched = engine.apply(sourceJson, List.of(definition)).patched();
                Files.createDirectories(generated.getParent());
                Files.writeString(generated, GSON.toJson(patched), StandardCharsets.UTF_8);
                status.addGeneratedTarget(selfTestCase.sourcePath());
            } catch (Exception ex) {
                status.addFailed(selfTestCase.id() + ": " + ex.getMessage());
            }
        }

        private static void addReloadOutcome(@Nonnull AssetPatchSelfTestCase selfTestCase,
                                             @Nonnull AssetPatchStatus status) {
            status.addRestartRequiredTarget(selfTestCase.sourcePath());
        }

        private static void removeGenerated(@Nonnull Path generated,
                                            @Nonnull AssetPatchSelfTestCase selfTestCase,
                                            @Nonnull AssetPatchStatus status) {
            try {
                if (Files.deleteIfExists(generated)) {
                    status.addRemovedGeneratedTarget(selfTestCase.sourcePath());
                }
            } catch (Exception ex) {
                status.addFailed("remove " + selfTestCase.id() + ": " + ex.getMessage());
            }
        }
    }
}
