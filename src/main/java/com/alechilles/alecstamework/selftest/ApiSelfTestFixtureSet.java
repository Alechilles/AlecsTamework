package com.alechilles.alecstamework.selftest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record ApiSelfTestFixtureSet(@Nonnull String fixtureSetId,
                                    @Nonnull UUID ownerPlayerUuid,
                                    @Nonnull String worldName,
                                    @Nonnull String toolId,
                                    @Nonnull Map<String, ApiSelfTestFixtureRecord> fixtures) {
    public ApiSelfTestFixtureSet {
        fixtures = Map.copyOf(new LinkedHashMap<>(fixtures));
    }

    @Nullable
    public ApiSelfTestFixtureRecord getFixture(@Nonnull String fixtureKey) {
        return fixtures.get(fixtureKey);
    }
}
