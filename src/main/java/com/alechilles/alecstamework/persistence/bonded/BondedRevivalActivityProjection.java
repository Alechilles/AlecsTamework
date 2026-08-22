package com.alechilles.alecstamework.persistence.bonded;

import com.alechilles.alecstamework.activity.ActivityRuntime;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Maps settled bonded-revival proof to the process-local activity feed. */
final class BondedRevivalActivityProjection {
    private BondedRevivalActivityProjection() {
    }

    static void publish(
            String paymentOperationId,
            BondedCompanionRecord.Profile profile,
            boolean recovered
    ) {
        if (paymentOperationId == null || paymentOperationId.isBlank()
                || profile == null) {
            return;
        }
        ActivityRuntime.publishRevival(
                stableUuid("operation", paymentOperationId),
                profile.ownerUuid(),
                profile.ownerUuid(),
                stableUuid("profile", profile.profileId()),
                profile.profileId(),
                "bonded",
                "stored",
                "settled",
                recovered,
                profile.updatedAtMs()
        );
    }

    private static UUID stableUuid(String kind, String value) {
        if ("profile".equals(kind)) {
            try {
                return UUID.fromString(value);
            } catch (IllegalArgumentException ignored) {
                // Older bonded profile IDs can be arbitrary stable text.
            }
        }
        return UUID.nameUUIDFromBytes(
                ("tamework:bonded-revival:" + kind + ":" + value)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
