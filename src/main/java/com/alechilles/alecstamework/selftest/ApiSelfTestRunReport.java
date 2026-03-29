package com.alechilles.alecstamework.selftest;

import java.util.List;
import javax.annotation.Nonnull;

public record ApiSelfTestRunReport(@Nonnull List<ApiSelfTestSuiteResult> suites) {
    public ApiSelfTestRunReport {
        suites = List.copyOf(suites);
    }

    public long totalAssertions() {
        return suites.stream().mapToLong(result -> result.assertions().size()).sum();
    }

    public long totalPassed() {
        return suites.stream().mapToLong(ApiSelfTestSuiteResult::passedCount).sum();
    }

    public long totalFailed() {
        return suites.stream().mapToLong(ApiSelfTestSuiteResult::failedCount).sum();
    }

    public boolean passed() {
        return totalFailed() == 0L;
    }
}
