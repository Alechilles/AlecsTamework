package com.alechilles.alecstamework.persistence.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import com.alechilles.alecstamework.api.ActivityView;
import com.alechilles.alecstamework.api.RevivalActivityView;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.config.managed.ManagedActivityConfigRegistry;
import com.alechilles.alecstamework.config.population.PopulationGroupConfigRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Behavior checks for bonded-revival activity identity and replay mapping. */
class BondedRevivalActivityProjectionTest {
    private static final UUID OWNER = UUID.fromString(
            "76000000-0000-0000-0000-000000000001");

    @AfterEach
    void clearActivityRuntime() {
        ActivityRuntime.clear();
    }

    @Test
    void arbitraryStableIdsMapToTheSameActivityIdentityOnRecovery() {
        List<ActivityView> activities = new ArrayList<>();
        ActivityRuntime.install(
                activities::add,
                new ManagedActivityConfigRegistry(
                        new PopulationGroupConfigRegistry()));
        BondedCompanionRecord.Profile profile = new BondedCompanionRecord.Profile(
                "profile-1", OWNER, "hydragon:dragons", "hydragon:dragon",
                "Bonded_Miniwyvern_Storm", BondedCompanionState.STORED, 5L,
                BondedCompanionPayload.of(new byte[]{1}), 1L, 2L, Map.of(),
                "Nimbus", "Miniwyvern", "Female", null, 0L, 1L,
                null, null);

        BondedRevivalActivityProjection.publish(
                "test-panel:revive-profile-1", profile, false);
        BondedRevivalActivityProjection.publish(
                "test-panel:revive-profile-1", profile, true);

        RevivalActivityView initial = assertInstanceOf(
                RevivalActivityView.class, activities.get(0));
        RevivalActivityView recovered = assertInstanceOf(
                RevivalActivityView.class, activities.get(1));
        assertEquals(initial.header().operationId(),
                recovered.header().operationId());
        assertEquals(initial.companionId(), recovered.companionId());
        assertEquals(OWNER, initial.actorId());
        assertEquals(OWNER, initial.ownerId());
        assertEquals("profile-1", initial.profileId());
        assertEquals("bonded", initial.revivalSource());
        assertEquals("stored", initial.resultingLifecycleState());
        assertEquals("settled", initial.paymentOutcome());
        assertFalse(initial.recovered());
        assertTrue(recovered.recovered());
    }
}
