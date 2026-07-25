package com.alechilles.alecstamework.companion.provisioning;

import com.alechilles.alecstamework.companion.command.timed.TimedSummonSessionId;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleRevision;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Durable result evidence for one confirmed initial live activation. */
public record ProvisioningActivationOutcome(
        @Nonnull ProfileId profileId,
        @Nonnull NpcAlias liveAlias,
        @Nonnull String worldKey,
        @Nonnull LifecycleRevision lifecycleRevision,
        @Nonnull String receiptKey,
        @Nullable TimedSummonSessionId timedSessionId,
        long activatedAtMs
) {
    public ProvisioningActivationOutcome {
        if (profileId == null || liveAlias == null
                || lifecycleRevision == null
                || worldKey == null || worldKey.isBlank()
                || receiptKey == null || receiptKey.isBlank()) {
            throw new IllegalArgumentException(
                    "Complete provisioning activation outcome is required"
            );
        }
        worldKey = worldKey.trim();
        receiptKey = receiptKey.trim();
    }
}

