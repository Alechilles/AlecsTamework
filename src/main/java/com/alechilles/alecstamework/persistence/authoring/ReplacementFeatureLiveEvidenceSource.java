package com.alechilles.alecstamework.persistence.authoring;

import com.alechilles.alecstamework.api.CommandFamilyRosterMutationRequest;
import com.alechilles.alecstamework.api.CommandTimedSummoningRequest;
import com.alechilles.alecstamework.api.PaidCommandRevivalQuoteRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionLocation;
import com.alechilles.alecstamework.companion.command.CommandRosterMembershipRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.command.timed.TimedSummonTransitionRequest;
import com.alechilles.alecstamework.companion.identity.NpcAlias;
import com.alechilles.alecstamework.companion.placement.CompanionSpawnPlacement;
import com.alechilles.alecstamework.companion.profile.CompanionProfileReadModel;
import com.alechilles.alecstamework.companion.provisioning.ProvisioningOrigin;
import com.alechilles.alecstamework.companion.revival.RevivalCostItem;
import com.alechilles.alecstamework.companion.revival.RevivalInventoryReservation;
import com.alechilles.alecstamework.companion.snapshot.CompanionSnapshot;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sole Hytale/world-thread input seam for restored public feature authors.
 *
 * <p>Implementations must resolve the current actor, world, store, placement,
 * inventory, and entity snapshot on that world's thread. They return copied
 * immutable evidence only and must not apply any gameplay effect.</p>
 */
public interface ReplacementFeatureLiveEvidenceSource {
    @Nonnull
    CompletionStage<RosterAccess> freezeRosterAccess(
            @Nonnull RosterAccessIntent intent
    );

    @Nonnull
    CompletionStage<TimedWorldEvidence> freezeTimedWorld(
            @Nonnull TimedWorldIntent intent
    );

    @Nonnull
    CompletionStage<ProvisioningWorldEvidence> freezeProvisioningWorld(
            @Nonnull ProvisioningWorldIntent intent
    );

    @Nonnull
    CompletionStage<PaidInventoryEvidence> freezePaidInventory(
            @Nonnull PaidInventoryIntent intent
    );

    /** Canonical role plus public intent offered to a roster access freeze. */
    record RosterAccessIntent(
            @Nonnull CommandFamilyRosterMutationRequest publicRequest,
            @Nonnull CommandRosterMembershipRequest.Action action,
            @Nonnull String expectedRoleId
    ) {
        public RosterAccessIntent {
            if (publicRequest == null || action == null) {
                throw new IllegalArgumentException(
                        "Complete roster access intent is required"
                );
            }
            expectedRoleId = text(
                    expectedRoleId, "Roster profile role"
            );
        }
    }

    /** Exact command-item/config authorization observed on the world thread. */
    record RosterAccess(
            @Nonnull UUID ownerUuid,
            @Nonnull String commandFamilyId,
            @Nullable String commandConfigId,
            @Nullable String accessItemId,
            @Nonnull CommandRosterSlotId slotId,
            long observedAtMs
    ) {
        public RosterAccess {
            if (ownerUuid == null || slotId == null) {
                throw new IllegalArgumentException(
                        "Complete roster access evidence is required"
                );
            }
            commandFamilyId = text(
                    commandFamilyId, "Roster command family"
            );
            commandConfigId = normalize(commandConfigId);
            accessItemId = normalize(accessItemId);
        }
    }

    /** Exact placement or live-source snapshot for one timed transition. */
    record TimedWorldEvidence(
            @Nonnull UUID ownerUuid,
            @Nonnull String worldKey,
            @Nonnull NpcAlias liveAlias,
            @Nullable CompanionSpawnPlacement placement,
            @Nonnull CompanionSnapshot snapshot,
            long observedAtMs
    ) {
        public TimedWorldEvidence {
            if (ownerUuid == null || liveAlias == null
                    || snapshot == null) {
                throw new IllegalArgumentException(
                        "Complete timed world evidence is required"
                );
            }
            worldKey = text(worldKey, "Timed world");
        }
    }

    /** Canonical facts offered to a read-only timed world-thread capture. */
    record TimedWorldIntent(
            @Nonnull CommandTimedSummoningRequest publicRequest,
            @Nonnull TimedSummonTransitionRequest.Action action,
            @Nonnull CompanionProfileReadModel profile,
            @Nonnull NpcAlias expectedAlias,
            @Nullable CompanionSpawnPlacement requestedPlacement
    ) {
        public TimedWorldIntent {
            if (publicRequest == null || action == null || profile == null
                    || expectedAlias == null) {
                throw new IllegalArgumentException(
                        "Complete timed world intent is required"
                );
            }
        }
    }

    /** Exact owner identity and optional initial projection state. */
    record ProvisioningWorldEvidence(
            @Nonnull UUID ownerUuid,
            @Nullable String ownerName,
            @Nullable String metadataJson,
            @Nullable PopulationAdmissionLocation admittedLocation,
            @Nullable CompanionSpawnPlacement placement,
            @Nullable SnapshotCodecRegistry.EncodedSnapshot fullState,
            long observedAtMs
    ) {
        public ProvisioningWorldEvidence {
            if (ownerUuid == null) {
                throw new IllegalArgumentException(
                        "Provisioning owner evidence is required"
                );
            }
            ownerName = normalize(ownerName);
            metadataJson = normalize(metadataJson);
        }
    }

    /** Immutable request for owner/world validation and projection authoring. */
    record ProvisioningWorldIntent(
            @Nonnull ProvisioningOrigin origin,
            @Nonnull UUID ownerUuid,
            @Nonnull String ownerWorldKey,
            @Nonnull String roleId,
            @Nullable PopulationAdmissionLocation destination,
            @Nullable NpcAlias targetAlias,
            @Nullable String spawnReceiptKey
    ) {
        public ProvisioningWorldIntent {
            if (origin == null || ownerUuid == null) {
                throw new IllegalArgumentException(
                        "Complete provisioning world intent is required"
                );
            }
            ownerWorldKey = text(
                    ownerWorldKey, "Provisioning owner world"
            );
            roleId = text(roleId, "Provisioning role");
            spawnReceiptKey = normalize(spawnReceiptKey);
            if ((targetAlias == null) != (spawnReceiptKey == null)) {
                throw new IllegalArgumentException(
                        "Provisioning projection identity must be complete"
                );
            }
        }
    }

    /** Exact inventory and placement snapshot used for quote or commit. */
    record PaidInventoryEvidence(
            @Nonnull UUID ownerUuid,
            @Nonnull List<PaidCostAvailability> costs,
            @Nonnull List<RevivalInventoryReservation> reservations,
            @Nullable CompanionSpawnPlacement placement,
            long observedAtMs
    ) {
        public PaidInventoryEvidence {
            if (ownerUuid == null || costs == null
                    || reservations == null) {
                throw new IllegalArgumentException(
                        "Complete paid-revival inventory evidence is required"
                );
            }
            costs = List.copyOf(costs);
            reservations = List.copyOf(reservations);
        }
    }

    /** One exact cost total plus presentation metadata from current assets. */
    record PaidCostAvailability(
            @Nonnull String itemId,
            int ownedQuantity,
            @Nullable String localizedName,
            @Nullable String iconAssetId
    ) {
        public PaidCostAvailability {
            itemId = text(itemId, "Paid revival cost item");
            if (ownedQuantity < 0) {
                throw new IllegalArgumentException(
                        "Paid revival owned quantity cannot be negative"
                );
            }
            localizedName = normalize(localizedName);
            iconAssetId = normalize(iconAssetId);
        }
    }

    /** Canonical request offered to a read-only inventory/world snapshot. */
    record PaidInventoryIntent(
            @Nonnull UUID ownerUuid,
            @Nonnull CompanionProfileReadModel profile,
            @Nonnull List<RevivalCostItem> exactCost,
            @Nullable NpcAlias targetAlias,
            boolean quoteOnly
    ) {
        public PaidInventoryIntent {
            if (ownerUuid == null || profile == null || exactCost == null) {
                throw new IllegalArgumentException(
                        "Complete paid-revival inventory intent is required"
                );
            }
            exactCost = List.copyOf(exactCost);
            if (!quoteOnly && targetAlias == null) {
                throw new IllegalArgumentException(
                        "Paid revival commit requires a target alias"
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

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
