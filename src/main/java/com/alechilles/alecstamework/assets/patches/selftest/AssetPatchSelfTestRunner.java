package com.alechilles.alecstamework.assets.patches.selftest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.alechilles.alecstamework.assets.patches.AssetPatchReloadMode;
import com.alechilles.alecstamework.assets.patches.AssetPatchService;
import com.alechilles.alecstamework.assets.patches.AssetPatchStatus;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.logger.HytaleLogger;

/**
 * Runs the live optional asset patch self-test by writing fixtures and invoking the real patch reload path.
 */
public final class AssetPatchSelfTestRunner {
    private final AssetPatchSelfTestPack pack;
    private final AssetPatchSelfTestReloadHandle reloadHandle;
    private final List<AssetPatchSelfTestCase> cases;
    @Nullable
    private final HytaleLogger logger;

    public AssetPatchSelfTestRunner(@Nonnull AssetPatchSelfTestPack pack,
                                    @Nonnull AssetPatchService patchService,
                                    @Nonnull HytaleLogger logger) {
        this(pack, new ServiceReloadHandle(patchService), AssetPatchSelfTestCase.defaultCases(), logger);
    }

    AssetPatchSelfTestRunner(@Nonnull AssetPatchSelfTestPack pack,
                             @Nonnull AssetPatchSelfTestReloadHandle reloadHandle,
                             @Nonnull List<AssetPatchSelfTestCase> cases,
                             @Nullable HytaleLogger logger) {
        this.pack = pack;
        this.reloadHandle = reloadHandle;
        this.cases = List.copyOf(cases);
        this.logger = logger;
    }

    @Nonnull
    public AssetPatchSelfTestResult run() {
        String runId = Instant.now().toString();
        try {
            pack.writeRunFixtures(runId, cases);
        } catch (IOException ex) {
            return setupFailure("Failed to write self-test fixtures: " + ex.getMessage(), ex, false);
        }
        AssetPatchStatus status = reloadHandle.reload();
        AssetPatchSelfTestResult result = verifyRun(runId, status);
        logResult(result);
        return result;
    }

    @Nonnull
    public AssetPatchSelfTestResult cleanup() {
        try {
            pack.cleanupFixtures(cases);
        } catch (IOException ex) {
            return setupFailure("Failed to clean self-test fixtures: " + ex.getMessage(), ex, true);
        }
        AssetPatchStatus status = reloadHandle.reload();
        List<AssetPatchSelfTestResult.CaseResult> results = new ArrayList<>();
        Set<String> removedTargets = Set.copyOf(status.getRemovedGeneratedTargets());
        for (AssetPatchSelfTestCase selfTestCase : cases) {
            boolean removed = removedTargets.contains(selfTestCase.sourcePath());
            results.add(new AssetPatchSelfTestResult.CaseResult(
                    selfTestCase.id(),
                    selfTestCase.sourcePath(),
                    false,
                    AssetPatchSelfTestResult.ReloadOutcome.NOT_APPLICABLE,
                    removed || !generatedOutputExists(selfTestCase),
                    removed ? "removed generated target" : "generated target already absent"
            ));
        }
        AssetPatchSelfTestResult result = new AssetPatchSelfTestResult(results, true);
        logResult(result);
        return result;
    }

    @Nonnull
    private AssetPatchSelfTestResult verifyRun(@Nonnull String runId, @Nonnull AssetPatchStatus status) {
        Set<String> hotReloaded = Set.copyOf(status.getHotReloadedTargets());
        Set<String> restartRequired = Set.copyOf(status.getRestartRequiredTargets());
        List<String> failures = status.getFailed();
        List<AssetPatchSelfTestResult.CaseResult> results = new ArrayList<>();
        for (AssetPatchSelfTestCase selfTestCase : cases) {
            results.add(verifyCase(selfTestCase, runId, hotReloaded, restartRequired, failures));
        }
        return new AssetPatchSelfTestResult(results, false);
    }

    @Nonnull
    private AssetPatchSelfTestResult.CaseResult verifyCase(@Nonnull AssetPatchSelfTestCase selfTestCase,
                                                          @Nonnull String runId,
                                                          @Nonnull Set<String> hotReloaded,
                                                          @Nonnull Set<String> restartRequired,
                                                          @Nonnull List<String> failures) {
        JsonObject generated;
        try {
            generated = readGeneratedOutput(selfTestCase);
        } catch (IOException | RuntimeException ex) {
            return new AssetPatchSelfTestResult.CaseResult(
                    selfTestCase.id(),
                    selfTestCase.sourcePath(),
                    false,
                    AssetPatchSelfTestResult.ReloadOutcome.FAILED,
                    false,
                    "missing or unreadable generated output: " + ex.getMessage()
            );
        }

        String checkFailure = firstCheckFailure(selfTestCase, runId, generated);
        String reloadFailure = firstReloadFailure(selfTestCase, failures);
        AssetPatchSelfTestResult.ReloadOutcome reloadOutcome = reloadOutcome(selfTestCase, hotReloaded, restartRequired);
        boolean reloadPass = switch (reloadOutcome) {
            case HOT_RELOADED -> true;
            case RESTART_REQUIRED -> selfTestCase.acceptsRestartRequired();
            case NOT_APPLICABLE, FAILED -> false;
        };
        boolean passed = checkFailure == null && reloadFailure == null && reloadPass;
        String detail = checkFailure == null ? "generated checks passed" : checkFailure;
        if (reloadFailure != null) {
            detail += "; reload failed: " + reloadFailure;
        }
        if (!reloadPass) {
            detail += "; reload expectation failed";
        }
        return new AssetPatchSelfTestResult.CaseResult(
                selfTestCase.id(),
                selfTestCase.sourcePath(),
                true,
                reloadOutcome,
                passed,
                detail
        );
    }

    @Nonnull
    private AssetPatchSelfTestResult.ReloadOutcome reloadOutcome(@Nonnull AssetPatchSelfTestCase selfTestCase,
                                                                 @Nonnull Set<String> hotReloaded,
                                                                 @Nonnull Set<String> restartRequired) {
        if (hotReloaded.contains(selfTestCase.sourcePath())) {
            return AssetPatchSelfTestResult.ReloadOutcome.HOT_RELOADED;
        }
        if (selfTestCase.expectedReloadMode() == AssetPatchReloadMode.NPC_BUILDERS
                && hotReloaded.contains("Server/NPC/Roles/*")) {
            return AssetPatchSelfTestResult.ReloadOutcome.HOT_RELOADED;
        }
        if (selfTestCase.expectedReloadMode() == AssetPatchReloadMode.TAMEWORK_CONFIG
                && hotReloaded.contains("Server/Tamework/Items/*")) {
            return AssetPatchSelfTestResult.ReloadOutcome.HOT_RELOADED;
        }
        if (restartRequired.contains(selfTestCase.sourcePath())) {
            return AssetPatchSelfTestResult.ReloadOutcome.RESTART_REQUIRED;
        }
        return AssetPatchSelfTestResult.ReloadOutcome.FAILED;
    }

    private boolean generatedOutputExists(@Nonnull AssetPatchSelfTestCase selfTestCase) {
        return Files.exists(generatedPath(selfTestCase));
    }

    @Nonnull
    private JsonObject readGeneratedOutput(@Nonnull AssetPatchSelfTestCase selfTestCase) throws IOException {
        String json = Files.readString(generatedPath(selfTestCase), StandardCharsets.UTF_8);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Nonnull
    private Path generatedPath(@Nonnull AssetPatchSelfTestCase selfTestCase) {
        return reloadHandle.generatedPatchCacheRoot()
                .resolve(selfTestCase.sourcePath())
                .toAbsolutePath()
                .normalize();
    }

    private String firstCheckFailure(@Nonnull AssetPatchSelfTestCase selfTestCase,
                                     @Nonnull String runId,
                                     @Nonnull JsonObject generated) {
        for (AssetPatchSelfTestCase.GeneratedCheck check : selfTestCase.generatedChecks()) {
            JsonElement actual = resolve(generated, check.path());
            JsonElement expected = replaceRunId(check.expectedValue(), runId);
            if (actual == null || !actual.equals(expected)) {
                return "expected " + check.path() + "=" + expected + " but found " + actual;
            }
        }
        return null;
    }

    private static String firstReloadFailure(@Nonnull AssetPatchSelfTestCase selfTestCase,
                                             @Nonnull List<String> failures) {
        for (String failure : failures) {
            if (failure.contains(selfTestCase.sourcePath())) {
                return failure;
            }
            if (selfTestCase.expectedReloadMode() == AssetPatchReloadMode.NPC_BUILDERS
                    && failure.contains("Server/NPC/Roles/*")) {
                return failure;
            }
            if (selfTestCase.expectedReloadMode() == AssetPatchReloadMode.TAMEWORK_CONFIG
                    && failure.contains("Tamework item feature configs")) {
                return failure;
            }
        }
        return null;
    }

    @Nonnull
    private static JsonElement replaceRunId(@Nonnull JsonElement value, @Nonnull String runId) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && AssetPatchSelfTestCase.RUN_ID_TOKEN.equals(value.getAsString())) {
            return new com.google.gson.JsonPrimitive(runId);
        }
        return value;
    }

    private static JsonElement resolve(@Nonnull JsonElement root, @Nonnull String path) {
        JsonElement current = root;
        for (String token : path.split("/")) {
            if (token.isEmpty()) {
                continue;
            }
            if (current == null) {
                return null;
            }
            if (current.isJsonObject()) {
                current = current.getAsJsonObject().get(token);
            } else if (current.isJsonArray()) {
                current = current.getAsJsonArray().get(Integer.parseInt(token));
            } else {
                return null;
            }
        }
        return current;
    }

    @Nonnull
    private AssetPatchSelfTestResult setupFailure(@Nonnull String message,
                                                  @Nonnull Exception ex,
                                                  boolean cleanup) {
        if (logger != null) {
            logger.at(Level.WARNING).withCause(ex).log(message);
        }
        List<AssetPatchSelfTestResult.CaseResult> results = cases.stream()
                .map(selfTestCase -> new AssetPatchSelfTestResult.CaseResult(
                        selfTestCase.id(),
                        selfTestCase.sourcePath(),
                        false,
                        AssetPatchSelfTestResult.ReloadOutcome.FAILED,
                        false,
                        message
                ))
                .toList();
        AssetPatchSelfTestResult result = new AssetPatchSelfTestResult(results, cleanup);
        logResult(result);
        return result;
    }

    private void logResult(@Nonnull AssetPatchSelfTestResult result) {
        if (logger == null) {
            return;
        }
        Level level = result.passed() ? Level.INFO : Level.WARNING;
        logger.at(level).log(result.summaryLine());
        for (AssetPatchSelfTestResult.CaseResult caseResult : result.cases()) {
            logger.at(caseResult.passed() ? Level.INFO : Level.WARNING).log(caseResult.logLine());
        }
    }

    private record ServiceReloadHandle(@Nonnull AssetPatchService service) implements AssetPatchSelfTestReloadHandle {
        @Override
        @Nonnull
        public AssetPatchStatus reload() {
            return service.reload();
        }

        @Override
        @Nonnull
        public Path generatedPatchCacheRoot() {
            return service.getGeneratedPatchCacheRoot();
        }
    }
}
