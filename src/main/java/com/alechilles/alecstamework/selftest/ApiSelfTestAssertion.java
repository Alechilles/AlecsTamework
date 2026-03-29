package com.alechilles.alecstamework.selftest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record ApiSelfTestAssertion(@Nonnull String name,
                                   boolean passed,
                                   @Nullable String detail) {
}
