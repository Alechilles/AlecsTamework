package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.ownership.CompanionLifecycleState;
import com.hypixel.hytale.component.RemoveReason;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nonnull;

/** Classifies non-unload removals from durable capture/coop/death/lost registries. */
public final class CompanionRemovalLifecycleClassifier {
    private final Predicate<UUID> captured;
    private final Predicate<UUID> cooped;
    private final Predicate<UUID> dead;
    private final Predicate<UUID> lost;
    private final Predicate<UUID> permanentlyReleased;

    public CompanionRemovalLifecycleClassifier(@Nonnull Predicate<UUID> captured,
                                               @Nonnull Predicate<UUID> cooped,
                                               @Nonnull Predicate<UUID> dead,
                                               @Nonnull Predicate<UUID> lost) {
        this(captured, cooped, dead, lost, ignored -> false);
    }

    public CompanionRemovalLifecycleClassifier(@Nonnull Predicate<UUID> captured,
                                               @Nonnull Predicate<UUID> cooped,
                                               @Nonnull Predicate<UUID> dead,
                                               @Nonnull Predicate<UUID> lost,
                                               @Nonnull Predicate<UUID> permanentlyReleased) {
        this.captured = Objects.requireNonNull(captured, "captured");
        this.cooped = Objects.requireNonNull(cooped, "cooped");
        this.dead = Objects.requireNonNull(dead, "dead");
        this.lost = Objects.requireNonNull(lost, "lost");
        this.permanentlyReleased = Objects.requireNonNull(
                permanentlyReleased, "permanentlyReleased"
        );
    }

    @Nonnull
    public CompanionLifecycleState classify(@Nonnull UUID npcUuid,
                                            @Nonnull RemoveReason reason,
                                            @Nonnull CompanionLifecycleState current) {
        return classify(npcUuid, reason, current, false);
    }

    /** Classifies an entity death with no durable revive representation as a permanent release. */
    @Nonnull
    public CompanionLifecycleState classify(@Nonnull UUID npcUuid,
                                            @Nonnull RemoveReason reason,
                                            @Nonnull CompanionLifecycleState current,
                                            boolean confirmedPermanentDeath) {
        Objects.requireNonNull(npcUuid, "npcUuid");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(current, "current");
        if (captured.test(npcUuid)) {
            return CompanionLifecycleState.CAPTURED;
        }
        if (cooped.test(npcUuid)) {
            return CompanionLifecycleState.COOP;
        }
        if (dead.test(npcUuid)) {
            return CompanionLifecycleState.DEAD_REVIVABLE;
        }
        if (lost.test(npcUuid)) {
            return CompanionLifecycleState.LOST;
        }
        if (confirmedPermanentDeath || permanentlyReleased.test(npcUuid)) {
            return CompanionLifecycleState.RELEASED;
        }
        if (reason == RemoveReason.UNLOAD) {
            if (current != CompanionLifecycleState.ACTIVE
                    && current != CompanionLifecycleState.UNLOADED
                    && current != CompanionLifecycleState.RESTORING) {
                return current;
            }
            return CompanionLifecycleState.UNLOADED;
        }
        if (current != CompanionLifecycleState.ACTIVE
                && current != CompanionLifecycleState.UNLOADED
                && current != CompanionLifecycleState.RESTORING) {
            return current;
        }
        return CompanionLifecycleState.UNKNOWN_DORMANT;
    }
}
