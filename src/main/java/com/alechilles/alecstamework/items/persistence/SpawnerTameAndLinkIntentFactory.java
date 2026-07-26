package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.api.CaptureSuccessDisposition;
import com.alechilles.alecstamework.companion.capture.CaptureAttemptResolution;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Composes one tame/link capture intent only from injected authoritative evidence.
 *
 * <p>This class selects no policies and synthesizes no persistence counts. Failure to prove any
 * authority leaves the successful roll unsubmitted.</p>
 */
public final class SpawnerTameAndLinkIntentFactory {
    private final SpawnerTameAndLinkEvidenceSource evidence;
    private final ThreadLocal<String> lastFailureReason = new ThreadLocal<>();

    public SpawnerTameAndLinkIntentFactory(
            @Nonnull SpawnerTameAndLinkEvidenceSource evidence
    ) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    /** Creates an exact intent, or null when the target/evidence is not wild and authoritative. */
    @Nullable
    public SpawnerCaptureIntent create(@Nonnull Input input) {
        lastFailureReason.remove();
        if (input == null
                || !input.resolution().successful()
                || input.resolution().successDisposition()
                != CaptureSuccessDisposition.TAME_AND_COMMAND_LINK
                || input.liveOwnerId() != null) {
            lastFailureReason.set("invalid-tame-link-input");
            return null;
        }
        SpawnerTameAndLinkIntentEvidence frozen;
        try {
            frozen = evidence.freeze(input);
        } catch (RuntimeException | LinkageError failure) {
            lastFailureReason.set("evidence-freeze-exception");
            return null;
        }
        if (frozen == null) {
            String reason = evidence.lastFailureReason();
            lastFailureReason.set(
                    reason == null || reason.isBlank()
                            ? "evidence-unavailable"
                            : reason
            );
            return null;
        }
        if (!consistent(input, frozen)) {
            lastFailureReason.set("evidence-inconsistent");
            return null;
        }
        return new SpawnerCaptureIntent(
                input.intentKey(),
                input.actorUuid(),
                input.worldKey(),
                input.sourceSlot(),
                input.sourceStack(),
                null,
                input.sourceRef(),
                input.sourceStore(),
                input.profileId(),
                input.sourceAlias(),
                null,
                frozen.target().ownerId(),
                frozen.target().ownerName(),
                input.roleId(),
                input.resolution(),
                input.publishedEffect(),
                frozen
        );
    }

    /** Returns the bounded failure diagnostic from the immediately preceding creation attempt. */
    @Nullable
    public String lastEvidenceFailureReason() {
        return lastFailureReason.get();
    }

    private boolean consistent(
            Input input,
            @Nullable SpawnerTameAndLinkIntentEvidence frozen
    ) {
        return frozen != null
                && frozen.target().ownerId().value().equals(
                input.actorUuid()
        )
                && frozen.target().ownerName().equals(
                input.actorName()
        )
                && frozen.command().familyKey().ownerId().equals(
                frozen.target().ownerId()
        )
                && frozen.command().familyKey().familyId().equals(
                frozen.target().commandAccess().commandFamilyId()
        );
    }

    /** Exact live and source context offered to the authoritative evidence source. */
    public record Input(
            @Nonnull String intentKey,
            @Nonnull UUID actorUuid,
            @Nonnull String actorName,
            @Nonnull String worldKey,
            int sourceSlot,
            @Nonnull ItemStack sourceStack,
            @Nullable Ref<EntityStore> sourceRef,
            @Nullable Store<EntityStore> sourceStore,
            @Nonnull ProfileId profileId,
            @Nonnull NpcAlias sourceAlias,
            @Nullable OwnerId liveOwnerId,
            @Nonnull String roleId,
            @Nonnull CaptureAttemptResolution resolution,
            @Nullable SpawnerPublishedEffect publishedEffect
    ) {
        public Input {
            intentKey = text(intentKey, "Tame/link intent key");
            actorName = text(actorName, "Tame/link actor name");
            worldKey = text(worldKey, "Tame/link world");
            roleId = text(roleId, "Tame/link source role");
            if (actorUuid == null || sourceSlot < 0
                    || sourceStack == null || profileId == null
                    || sourceAlias == null || resolution == null) {
                throw new IllegalArgumentException(
                        "Complete tame/link intent input is required"
                );
            }
            if (!intentKey.equals(resolution.attemptId().toString())
                    || !roleId.equals(resolution.targetRoleId())) {
                throw new IllegalArgumentException(
                        "Tame/link intent must match its resolved attempt"
                );
            }
        }
    }

    private static String text(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
    }
}
