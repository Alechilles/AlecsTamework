package com.alechilles.alecstamework.persistence.authoring.runtime;

import com.alechilles.alecstamework.api.CommandFamilyRosterMutationRequest;
import com.alechilles.alecstamework.companion.command.CommandRosterSlotId;
import com.alechilles.alecstamework.companion.revival.RevivalCostItem;
import com.alechilles.alecstamework.companion.revival.RevivalInventoryReservation;
import com.alechilles.alecstamework.companion.revival.runtime
        .HytalePaidRevivalStackFingerprint;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.ItemFeatureRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import com.alechilles.alecstamework.items.CommandCompanionPlacementService;
import com.alechilles.alecstamework.localization.LocalizedText;
import com.alechilles.alecstamework.persistence.authoring
        .ReplacementFeatureLiveEvidenceSource;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Reads exact command access and paid-revival inventory evidence without mutation. */
final class HytaleFeatureInventoryEvidenceReader {
    private static final List<SectionDefinition> SECTIONS = List.of(
            new SectionDefinition(
                    "backpack", InventoryComponent.BACKPACK_SECTION_ID
            ),
            new SectionDefinition(
                    "storage", InventoryComponent.STORAGE_SECTION_ID
            ),
            new SectionDefinition(
                    "hotbar", InventoryComponent.HOTBAR_SECTION_ID
            )
    );

    private final CommandItemRegistry commandItems;
    private final CommandCompanionPlacementService placements =
            new CommandCompanionPlacementService();

    HytaleFeatureInventoryEvidenceReader(
            @Nonnull CommandItemRegistry commandItems
    ) {
        this.commandItems = Objects.requireNonNull(
                commandItems, "commandItems"
        );
    }

    @Nullable
    ReplacementFeatureLiveEvidenceSource.RosterAccess freezeRoster(
            HytaleOwnerWorldAccess access,
            ReplacementFeatureLiveEvidenceSource.RosterAccessIntent intent,
            long observedAtMs
    ) {
        CommandFamilyRosterMutationRequest request = intent.publicRequest();
        if (!access.ownerUuid().equals(request.ownerUuid())
                || commandItems.validateOwnerFamilyAccess(
                request.commandFamilyId(),
                request.requiredCommandConfigId(),
                request.accessItemId(),
                intent.expectedRoleId()
        ) != null) {
            return null;
        }
        String configId = request.requiredCommandConfigId();
        String accessItemId = request.accessItemId();
        TwCommandItemConfig config = commandItems.getByConfigId(configId);
        if (config == null
                || accessItemId != null
                && !possessesAccessItem(access, accessItemId, config)) {
            return null;
        }
        return new ReplacementFeatureLiveEvidenceSource.RosterAccess(
                access.ownerUuid(),
                request.commandFamilyId(),
                configId(config, configId),
                accessItemId,
                stableSlot(request),
                observedAtMs
        );
    }

    @Nullable
    ReplacementFeatureLiveEvidenceSource.PaidInventoryEvidence freezePaid(
            HytaleOwnerWorldAccess access,
            ReplacementFeatureLiveEvidenceSource.PaidInventoryIntent intent,
            long observedAtMs
    ) {
        if (!access.ownerUuid().equals(intent.ownerUuid())
                || !uniqueCosts(intent.exactCost())) {
            return null;
        }
        List<Section> sections = sections(access);
        if (sections == null) {
            return null;
        }
        ArrayList<ReplacementFeatureLiveEvidenceSource.PaidCostAvailability>
                availability = new ArrayList<>();
        ArrayList<RevivalInventoryReservation> reservations =
                new ArrayList<>();
        boolean sufficient = true;
        for (int costOrdinal = 0;
             costOrdinal < intent.exactCost().size();
             costOrdinal++) {
            RevivalCostItem cost = intent.exactCost().get(costOrdinal);
            Integer owned = owned(sections, cost.itemId());
            if (owned == null) {
                return null;
            }
            availability.add(availability(access, cost.itemId(), owned));
            if (owned < cost.quantity()) {
                sufficient = false;
            }
            if (!intent.quoteOnly()) {
                reserve(
                        sections,
                        cost,
                        costOrdinal,
                        reservations
                );
            }
        }
        if (!intent.quoteOnly() && (!sufficient
                || reservedQuantity(reservations)
                != requiredQuantity(intent.exactCost()))) {
            reservations.clear();
        }
        var placement = intent.quoteOnly()
                ? null : freezePlacement(access, intent);
        if (!intent.quoteOnly() && placement == null) {
            return null;
        }
        return new ReplacementFeatureLiveEvidenceSource
                .PaidInventoryEvidence(
                access.ownerUuid(),
                availability,
                reservations,
                placement,
                observedAtMs
        );
    }

    private boolean possessesAccessItem(
            HytaleOwnerWorldAccess access,
            String expectedItemId,
            TwCommandItemConfig config
    ) {
        List<Section> sections = sections(access);
        if (sections == null) {
            return false;
        }
        for (Section section : sections) {
            for (short slot = 0;
                 slot < section.container().getCapacity();
                 slot++) {
                ItemStack stack = section.container().getItemStack(slot);
                if (!ItemStack.isEmpty(stack)
                        && stack.getQuantity() > 0
                        && commandItems.get(stack.getItemId()) == config
                        && sameStateFamily(
                        stack.getItemId(), expectedItemId
                )) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean sameStateFamily(
            String actualItemId,
            String expectedItemId
    ) {
        return Objects.equals(
                ItemFeatureRegistry.normalizeStateItemId(actualItemId),
                ItemFeatureRegistry.normalizeStateItemId(expectedItemId)
        );
    }

    @Nullable
    private List<Section> sections(HytaleOwnerWorldAccess access) {
        ArrayList<Section> sections = new ArrayList<>(SECTIONS.size());
        for (SectionDefinition definition : SECTIONS) {
            ComponentType<EntityStore, ? extends InventoryComponent> type =
                    InventoryComponent.getComponentTypeById(
                            definition.sectionId()
                    );
            if (type == null) {
                return null;
            }
            InventoryComponent component = access.store().getComponent(
                    access.actorRef(), type
            );
            if (component != null && component.getInventory() != null) {
                sections.add(new Section(
                        definition.id(), component.getInventory()
                ));
            }
        }
        return sections.isEmpty() ? null : List.copyOf(sections);
    }

    @Nullable
    private Integer owned(List<Section> sections, String itemId) {
        int total = 0;
        try {
            for (Section section : sections) {
                ItemContainer container = section.container();
                for (short slot = 0; slot < container.getCapacity(); slot++) {
                    ItemStack stack = container.getItemStack(slot);
                    if (!ItemStack.isEmpty(stack)
                            && itemId.equals(stack.getItemId())
                            && stack.getQuantity() > 0) {
                        total = Math.addExact(
                                total, stack.getQuantity()
                        );
                    }
                }
            }
            return total;
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    private void reserve(
            List<Section> sections,
            RevivalCostItem cost,
            int costOrdinal,
            List<RevivalInventoryReservation> output
    ) {
        int remaining = cost.quantity();
        int stackOrdinal = 0;
        for (Section section : sections) {
            ItemContainer container = section.container();
            for (short slot = 0;
                 slot < container.getCapacity() && remaining > 0;
                 slot++) {
                ItemStack stack = container.getItemStack(slot);
                if (ItemStack.isEmpty(stack)
                        || !cost.itemId().equals(stack.getItemId())
                        || stack.getQuantity() <= 0) {
                    continue;
                }
                int quantity = Math.min(remaining, stack.getQuantity());
                output.add(new RevivalInventoryReservation(
                        costOrdinal,
                        stackOrdinal++,
                        section.id(),
                        slot,
                        quantity,
                        HytalePaidRevivalStackFingerprint.of(stack),
                        0L
                ));
                remaining -= quantity;
            }
        }
    }

    @Nullable
    private com.alechilles.alecstamework.companion.placement
            .CompanionSpawnPlacement freezePlacement(
            HytaleOwnerWorldAccess access,
            ReplacementFeatureLiveEvidenceSource.PaidInventoryIntent intent
    ) {
        Ref<EntityStore> existing = access.world().getEntityRef(
                intent.targetAlias().value()
        );
        if (existing != null && existing.isValid()) {
            return null;
        }
        return placements.computeRestorationPlacement(
                access.actorRef(),
                access.store(),
                5.0D,
                intent.profile().identity().roleId(),
                null
        );
    }

    private ReplacementFeatureLiveEvidenceSource.PaidCostAvailability
    availability(
            HytaleOwnerWorldAccess access,
            String itemId,
            int owned
    ) {
        String name = null;
        String icon = null;
        try {
            Item item = Item.getAssetMap().getAsset(itemId);
            if (item != null) {
                name = LocalizedText.resolve(
                        access.player(), item.getTranslationKey()
                );
                icon = normalize(item.getIcon());
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Presentation metadata is optional; the exact cost fact is not.
        }
        return new ReplacementFeatureLiveEvidenceSource
                .PaidCostAvailability(itemId, owned, normalize(name), icon);
    }

    private boolean uniqueCosts(List<RevivalCostItem> costs) {
        HashSet<String> ids = new HashSet<>();
        for (RevivalCostItem cost : costs) {
            if (cost == null || !ids.add(cost.itemId())) {
                return false;
            }
        }
        return true;
    }

    private int reservedQuantity(
            List<RevivalInventoryReservation> reservations
    ) {
        int total = 0;
        for (RevivalInventoryReservation reservation : reservations) {
            total = Math.addExact(total, reservation.quantity());
        }
        return total;
    }

    private int requiredQuantity(List<RevivalCostItem> costs) {
        int total = 0;
        for (RevivalCostItem cost : costs) {
            total = Math.addExact(total, cost.quantity());
        }
        return total;
    }

    private CommandRosterSlotId stableSlot(
            CommandFamilyRosterMutationRequest request
    ) {
        String material = "tamework:command-roster-slot:v1\u0000"
                + request.ownerUuid() + "\u0000"
                + request.commandFamilyId() + "\u0000"
                + request.profileId();
        return new CommandRosterSlotId(UUID.nameUUIDFromBytes(
                material.getBytes(StandardCharsets.UTF_8)
        ));
    }

    private String configId(
            TwCommandItemConfig config,
            String fallback
    ) {
        return config.getId() == null || config.getId().isBlank()
                ? fallback : config.getId().trim();
    }

    @Nullable
    private String normalize(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record SectionDefinition(String id, int sectionId) {
    }

    private record Section(String id, ItemContainer container) {
    }
}
