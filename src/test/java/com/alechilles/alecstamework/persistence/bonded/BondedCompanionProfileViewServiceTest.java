package com.alechilles.alecstamework.persistence.bonded;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.alechilles.alecstamework.companion.bonded.BondedCompanionPolicyResolver;
import com.alechilles.alecstamework.companion.bonded.BondedCompanionState;
import com.alechilles.alecstamework.config.assets.TwBondedCompanionRosterConfig;
import com.alechilles.alecstamework.config.bonded.BondedCompanionRosterRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

/** Ensures panel views expose the configured timer duration for saved states. */
class BondedCompanionProfileViewServiceTest {
    private static final UUID OWNER = UUID.fromString(
            "73000000-0000-0000-0000-000000000001");

    @Test
    void exposesSummonDurationForStoredAndReviveDurationForDeadProfiles()
            throws Exception {
        BondedCompanionRosterRegistry rosters = rosterRegistry();
        BondedCompanionPolicyResolver policies =
                new BondedCompanionPolicyResolver(rosters);
        BondedCompanionProfileViewService views =
                new BondedCompanionProfileViewService(
                        store(), rosters, policies,
                        new BondedCompanionReviveQuoteSupport(), () -> 2_000L);

        BondedCompanionRecord.Profile stored = profile(
                "stored", BondedCompanionState.STORED, null);
        BondedCompanionRecord.Profile dead = profile(
                "dead", BondedCompanionState.DEAD, 1_000L);

        var storedView = views.view(stored, null, List.of(stored, dead));
        var deadView = views.view(dead, null, List.of(stored, dead));

        assertEquals("30000",
                storedView.snapshotPresentationData().get("cooldownDurationMs"));
        assertEquals("45000",
                deadView.snapshotPresentationData().get("cooldownDurationMs"));
    }

    private static BondedCompanionRosterRegistry rosterRegistry()
            throws Exception {
        TwBondedCompanionRosterConfig config =
                TwBondedCompanionRosterConfig.CODEC.decode(
                        BsonDocument.parse("""
                                {
                                  "RosterId": "test:roster",
                                  "FamilyId": "test:family",
                                  "AllowedRoles": ["test:role"],
                                  "MaximumActive": 1,
                                  "SummonCooldownSeconds": 30,
                                  "ReviveCooldownSeconds": 45
                                }
                                """), new com.hypixel.hytale.codec.ExtraInfo());
        Field id = TwBondedCompanionRosterConfig.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(config, "test:roster-policy");

        BondedCompanionRosterRegistry rosters =
                new BondedCompanionRosterRegistry();
        if (!rosters.replace(List.of(config), 7L).applied()) {
            throw new AssertionError("test roster policy was rejected");
        }
        return rosters;
    }

    private static BondedCompanionRecord.Profile profile(
            String profileId, BondedCompanionState state, Long diedAtMs) {
        return new BondedCompanionRecord.Profile(
                profileId, OWNER, "test:roster", "test:family", "test:role",
                state, 1L,
                BondedCompanionPayload.of("{}".getBytes(StandardCharsets.UTF_8)),
                0L, 0L, Map.of(), profileId, "Test Animal", "Female",
                diedAtMs, 0L, 0L, null, null);
    }

    private static BondedCompanionStore store() {
        return (BondedCompanionStore) Proxy.newProxyInstance(
                BondedCompanionStore.class.getClassLoader(),
                new Class<?>[] {BondedCompanionStore.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "listExtensionData" -> List.of();
                    case "toString" -> "ProfileViewTestStore";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError(
                            "Unexpected store call: " + method.getName());
                });
    }
}
