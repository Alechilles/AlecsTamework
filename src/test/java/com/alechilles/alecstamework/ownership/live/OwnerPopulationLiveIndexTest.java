package com.alechilles.alecstamework.ownership.live;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Regression tests for the thread-independent loaded-owner population index. */
class OwnerPopulationLiveIndexTest {
    private static final UUID OWNER =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID OTHER_OWNER =
            UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final UUID FIRST_NPC =
            UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID SECOND_NPC =
            UUID.fromString("00000000-0000-0000-0000-000000000202");

    @Test
    void countsGlobalAndPerWorldWithoutWorldAccess() {
        OwnerPopulationLiveIndex index = new OwnerPopulationLiveIndex();
        index.observe(FIRST_NPC, OWNER, "alpha");
        index.observe(SECOND_NPC, OWNER, "beta");

        assertEquals(
                2,
                index.count(
                        OWNER,
                        TwGlobalConfig.PerPlayerLimitScope.GLOBAL,
                        null
                )
        );
        assertEquals(
                1,
                index.count(
                        OWNER,
                        TwGlobalConfig.PerPlayerLimitScope.PER_WORLD,
                        "alpha"
                )
        );
    }

    @Test
    void repeatedObservationMovesRatherThanDuplicatesEntry() {
        OwnerPopulationLiveIndex index = new OwnerPopulationLiveIndex();
        index.observe(FIRST_NPC, OWNER, "alpha");
        index.observe(FIRST_NPC, OTHER_OWNER, "beta");

        assertEquals(1, index.size());
        assertEquals(
                0,
                index.count(
                        OWNER,
                        TwGlobalConfig.PerPlayerLimitScope.GLOBAL,
                        null
                )
        );
        assertEquals(
                1,
                index.count(
                        OTHER_OWNER,
                        TwGlobalConfig.PerPlayerLimitScope.PER_WORLD,
                        "beta"
                )
        );
    }

    @Test
    void removalAndOwnerClearAreIdempotent() {
        OwnerPopulationLiveIndex index = new OwnerPopulationLiveIndex();
        index.observe(FIRST_NPC, OWNER, "alpha");
        index.observe(FIRST_NPC, null, "alpha");
        index.remove(FIRST_NPC);

        assertEquals(0, index.size());
    }

    @Test
    void perWorldCountRequiresWorldContext() {
        OwnerPopulationLiveIndex index = new OwnerPopulationLiveIndex();
        index.observe(FIRST_NPC, OWNER, "alpha");

        assertEquals(
                -1,
                index.count(
                        OWNER,
                        TwGlobalConfig.PerPlayerLimitScope.PER_WORLD,
                        null
                )
        );
    }
}
