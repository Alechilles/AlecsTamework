package com.alechilles.alecstamework.items;

import java.util.UUID;
import javax.annotation.Nonnull;

/** Immutable-request boundary for retiring a historical alias held by another entity store. */
public interface ManagedCoopCrossWorldAliasRetirement {
    void request(@Nonnull ManagedCoopCrossWorldAliasRetirementCoordinator.RetirementRequest request);

    void invalidateNpc(@Nonnull UUID npcUuid);

    @Nonnull
    static ManagedCoopCrossWorldAliasRetirement noop() {
        return new ManagedCoopCrossWorldAliasRetirement() {
            @Override
            public void request(
                    ManagedCoopCrossWorldAliasRetirementCoordinator.RetirementRequest request) {
            }

            @Override
            public void invalidateNpc(UUID npcUuid) {
            }
        };
    }
}
