package com.alechilles.alecstamework.assets.patches.selftest;

import java.util.List;

import javax.annotation.Nonnull;

/**
 * Command-facing result model for an asset patch self-test run.
 */
public record AssetPatchSelfTestResult(@Nonnull List<CaseResult> cases, boolean cleanup) {
    public AssetPatchSelfTestResult {
        cases = List.copyOf(cases);
    }

    public int passedCount() {
        return (int) cases.stream().filter(CaseResult::passed).count();
    }

    public int failedCount() {
        return (int) cases.stream().filter(result -> !result.passed()).count();
    }

    public int generatedCount() {
        return (int) cases.stream().filter(CaseResult::generated).count();
    }

    public int hotReloadedCount() {
        return (int) cases.stream().filter(result -> result.reloadOutcome() == ReloadOutcome.HOT_RELOADED).count();
    }

    public int restartRequiredCount() {
        return (int) cases.stream().filter(result -> result.reloadOutcome() == ReloadOutcome.RESTART_REQUIRED).count();
    }

    public boolean passed() {
        return failedCount() == 0;
    }

    @Nonnull
    public String summaryLine() {
        String prefix = cleanup ? "Patch self-test cleanup" : "Patch self-test";
        return prefix + ": passed=" + passedCount()
                + " failed=" + failedCount()
                + " generated=" + generatedCount()
                + " hotReloaded=" + hotReloadedCount()
                + " restartRequired=" + restartRequiredCount();
    }

    public enum ReloadOutcome {
        HOT_RELOADED,
        RESTART_REQUIRED,
        NOT_APPLICABLE,
        FAILED
    }

    public record CaseResult(@Nonnull String id,
                             @Nonnull String target,
                             boolean generated,
                             @Nonnull ReloadOutcome reloadOutcome,
                             boolean passed,
                             @Nonnull String detail) {
        @Nonnull
        public String logLine() {
            return "Patch self-test " + (passed ? "PASS" : "FAIL")
                    + " " + id
                    + " target=" + target
                    + " generated=" + generated
                    + " reload=" + reloadOutcome
                    + " detail=" + detail;
        }
    }
}
