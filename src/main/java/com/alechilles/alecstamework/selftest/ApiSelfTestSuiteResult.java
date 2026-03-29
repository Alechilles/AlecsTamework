package com.alechilles.alecstamework.selftest;

import java.util.List;
import javax.annotation.Nonnull;

public record ApiSelfTestSuiteResult(@Nonnull String suiteName,
                                     @Nonnull List<ApiSelfTestAssertion> assertions) {
    public ApiSelfTestSuiteResult {
        assertions = List.copyOf(assertions);
    }

    public boolean passed() {
        return assertions.stream().allMatch(ApiSelfTestAssertion::passed);
    }

    public long passedCount() {
        return assertions.stream().filter(ApiSelfTestAssertion::passed).count();
    }

    public long failedCount() {
        return assertions.size() - passedCount();
    }
}
