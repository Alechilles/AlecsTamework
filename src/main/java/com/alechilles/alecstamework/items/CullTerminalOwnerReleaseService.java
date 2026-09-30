package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.flow.ReleaseFlow;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import javax.annotation.Nullable;

/**
 * Releases the companion record of a live cull or transformation target before its death or
 * removal is applied, so a terminal cull cannot leave population capacity occupied. The caller
 * removes or kills the body itself; the release only unregisters it.
 */
final class CullTerminalOwnerReleaseService {
    enum Outcome {
        RELEASED,
        NOT_TRACKED,
        UNAVAILABLE
    }

    @FunctionalInterface
    interface Port {
        CompletionStage<Outcome> release(UUID ownerUuid, UUID npcUuid);
    }

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private final ReleaseFlow releaseFlow;
    private final CompanionQueries companions;

    private CullTerminalOwnerReleaseService(ReleaseFlow releaseFlow, CompanionQueries companions) {
        this.releaseFlow = releaseFlow;
        this.companions = companions;
    }

    static Port from(@Nullable ReleaseFlow releaseFlow, @Nullable CompanionQueries companions) {
        return releaseFlow == null || companions == null
                ? (ownerUuid, npcUuid) -> unavailable()
                : new CullTerminalOwnerReleaseService(releaseFlow, companions)::release;
    }

    private CompletionStage<Outcome> release(UUID ownerUuid, UUID npcUuid) {
        if (ownerUuid == null || npcUuid == null) {
            return unavailable();
        }
        try {
            CompanionRecord record = companions.byNpcUuid(npcUuid);
            // No record for this body: an ordinary live NPC with nothing durable to release.
            if (record == null) {
                return CompletableFuture.completedFuture(Outcome.NOT_TRACKED);
            }
            Outcome outcome = switch (releaseFlow.release(record.profileId(), ownerUuid).result()) {
                case RELEASED -> Outcome.RELEASED;
                case NOT_FOUND -> Outcome.NOT_TRACKED;
                case NOT_OWNER, NOT_RELEASABLE -> Outcome.UNAVAILABLE;
            };
            return CompletableFuture.completedFuture(outcome);
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Could not release cull target %s", npcUuid);
            return unavailable();
        }
    }

    private static CompletionStage<Outcome> unavailable() {
        return CompletableFuture.completedFuture(Outcome.UNAVAILABLE);
    }
}
