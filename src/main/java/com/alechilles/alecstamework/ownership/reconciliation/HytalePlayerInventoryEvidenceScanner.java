package com.alechilles.alecstamework.ownership.reconciliation;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Scans every persisted 0.5.6 player inventory section, including the Tool section omitted by
 * {@link InventoryComponent#EVERYTHING}.
 */
public final class HytalePlayerInventoryEvidenceScanner {
    private final RecursiveItemContainerEvidenceScanner containers;

    public HytalePlayerInventoryEvidenceScanner(
            @Nonnull RecursiveItemContainerEvidenceScanner containers
    ) {
        this.containers = Objects.requireNonNull(containers, "containers");
    }

    @Nonnull
    public List<CompanionPopulationEvidence> scan(@Nonnull Holder<EntityStore> holder,
                                                   @Nonnull String evidencePrefix,
                                                   @Nonnull String source) {
        Objects.requireNonNull(holder, "holder");
        List<CompanionPopulationEvidence> result = new ArrayList<>();
        scanHolderSection(holder, InventoryComponent.Storage.getComponentType(), "storage", evidencePrefix, source, result);
        scanHolderSection(holder, InventoryComponent.Armor.getComponentType(), "armor", evidencePrefix, source, result);
        scanHolderSection(holder, InventoryComponent.Hotbar.getComponentType(), "hotbar", evidencePrefix, source, result);
        scanHolderSection(holder, InventoryComponent.Utility.getComponentType(), "utility", evidencePrefix, source, result);
        scanHolderSection(holder, InventoryComponent.Tool.getComponentType(), "tool", evidencePrefix, source, result);
        scanHolderSection(holder, InventoryComponent.Backpack.getComponentType(), "backpack", evidencePrefix, source, result);
        return List.copyOf(result);
    }

    @Nonnull
    public List<CompanionPopulationEvidence> scan(
            @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull String evidencePrefix,
            @Nonnull String source
    ) {
        Objects.requireNonNull(accessor, "accessor");
        Objects.requireNonNull(playerRef, "playerRef");
        List<CompanionPopulationEvidence> result = new ArrayList<>();
        scanLiveSection(accessor, playerRef, InventoryComponent.Storage.getComponentType(), "storage", evidencePrefix, source, result);
        scanLiveSection(accessor, playerRef, InventoryComponent.Armor.getComponentType(), "armor", evidencePrefix, source, result);
        scanLiveSection(accessor, playerRef, InventoryComponent.Hotbar.getComponentType(), "hotbar", evidencePrefix, source, result);
        scanLiveSection(accessor, playerRef, InventoryComponent.Utility.getComponentType(), "utility", evidencePrefix, source, result);
        scanLiveSection(accessor, playerRef, InventoryComponent.Tool.getComponentType(), "tool", evidencePrefix, source, result);
        scanLiveSection(accessor, playerRef, InventoryComponent.Backpack.getComponentType(), "backpack", evidencePrefix, source, result);
        return List.copyOf(result);
    }

    private <T extends InventoryComponent> void scanHolderSection(
            @Nonnull Holder<EntityStore> holder,
            @Nullable ComponentType<EntityStore, T> type,
            @Nonnull String section,
            @Nonnull String prefix,
            @Nonnull String source,
            @Nonnull List<CompanionPopulationEvidence> result
    ) {
        requireRegistered(type, section);
        T component = holder.getComponent(type);
        addSection(component, section, prefix, source, result);
    }

    private <T extends InventoryComponent> void scanLiveSection(
            @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> playerRef,
            @Nullable ComponentType<EntityStore, T> type,
            @Nonnull String section,
            @Nonnull String prefix,
            @Nonnull String source,
            @Nonnull List<CompanionPopulationEvidence> result
    ) {
        requireRegistered(type, section);
        T component = accessor.getComponent(playerRef, type);
        addSection(component, section, prefix, source, result);
    }

    private void addSection(@Nullable InventoryComponent component,
                            @Nonnull String section,
                            @Nonnull String prefix,
                            @Nonnull String source,
                            @Nonnull List<CompanionPopulationEvidence> result) {
        if (component == null || component.getInventory() == null) {
            return;
        }
        result.addAll(containers.scan(
                component.getInventory(),
                prefix + "/" + section,
                source
        ).evidence());
    }

    private static void requireRegistered(@Nullable ComponentType<?, ?> type, @Nonnull String section) {
        if (type == null) {
            throw new IllegalStateException("Player inventory component is not registered: " + section);
        }
    }
}
