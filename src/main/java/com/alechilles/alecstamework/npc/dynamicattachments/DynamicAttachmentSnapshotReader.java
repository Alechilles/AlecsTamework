package com.alechilles.alecstamework.npc.dynamicattachments;

import com.alechilles.alecstamework.npc.NpcDisplayNameComponentService;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Read-only service that captures NPC state for dynamic attachment condition evaluation. */
public final class DynamicAttachmentSnapshotReader {
    private final ComponentType<EntityStore, TameworkOwnerComponent> ownerComponentType;
    private final ComponentType<EntityStore, TameworkTamedComponent> tamedComponentType;
    private final ComponentType<EntityStore, TameworkLifeStageComponent> lifeStageComponentType;
    private final ComponentType<EntityStore, TameworkHappinessComponent> happinessComponentType;
    private final ComponentType<EntityStore, TameworkNeedsComponent> needsComponentType;
    private final ComponentType<EntityStore, TameworkTraitsComponent> traitsComponentType;
    private final ComponentType<EntityStore, TameworkCommandLinksComponent> commandLinksComponentType;

    public DynamicAttachmentSnapshotReader() {
        this(
                TameworkOwnerComponent.getComponentType(),
                TameworkTamedComponent.getComponentType(),
                TameworkLifeStageComponent.getComponentType(),
                TameworkHappinessComponent.getComponentType(),
                TameworkNeedsComponent.getComponentType(),
                TameworkTraitsComponent.getComponentType(),
                TameworkCommandLinksComponent.getComponentType()
        );
    }

    public DynamicAttachmentSnapshotReader(
            @Nullable ComponentType<EntityStore, TameworkOwnerComponent> ownerComponentType,
            @Nullable ComponentType<EntityStore, TameworkTamedComponent> tamedComponentType,
            @Nullable ComponentType<EntityStore, TameworkLifeStageComponent> lifeStageComponentType,
            @Nullable ComponentType<EntityStore, TameworkHappinessComponent> happinessComponentType,
            @Nullable ComponentType<EntityStore, TameworkNeedsComponent> needsComponentType,
            @Nullable ComponentType<EntityStore, TameworkTraitsComponent> traitsComponentType,
            @Nullable ComponentType<EntityStore, TameworkCommandLinksComponent> commandLinksComponentType) {
        this.ownerComponentType = ownerComponentType;
        this.tamedComponentType = tamedComponentType;
        this.lifeStageComponentType = lifeStageComponentType;
        this.happinessComponentType = happinessComponentType;
        this.needsComponentType = needsComponentType;
        this.traitsComponentType = traitsComponentType;
        this.commandLinksComponentType = commandLinksComponentType;
    }

    @Nonnull
    public DynamicAttachmentNpcSnapshot read(@Nullable Ref<EntityStore> reference,
                                             @Nullable Store<EntityStore> store) {
        if (reference == null || store == null || !reference.isValid()) {
            return DynamicAttachmentNpcSnapshot.builder().build();
        }

        DynamicAttachmentNpcSnapshot.Builder builder = DynamicAttachmentNpcSnapshot.builder()
                .roleId(CompanionRoleIdResolver.resolveRoleId(reference, store))
                .displayName(NpcDisplayNameComponentService.resolvePersistentOrRuntimeName(reference, store));

        TameworkOwnerComponent owner = getComponent(reference, store, ownerComponentType);
        if (owner != null) {
            builder.owner(owner.getOwnerId(), owner.getOwnerName());
        }

        TameworkTamedComponent tamed = getComponent(reference, store, tamedComponentType);
        if (tamed != null) {
            builder.tamed(tamed.isTamed());
        }

        TameworkLifeStageComponent lifeStage = getComponent(reference, store, lifeStageComponentType);
        if (lifeStage != null) {
            builder.gender(lifeStage.getGender())
                    .lifeStage(lifeStage.getStage());
        }

        TameworkHappinessComponent happiness = getComponent(reference, store, happinessComponentType);
        if (happiness != null) {
            builder.happiness(happiness.getValue());
        }

        TameworkNeedsComponent needs = getComponent(reference, store, needsComponentType);
        if (needs != null) {
            builder.needs(readNeeds(needs));
        }

        TameworkTraitsComponent traits = getComponent(reference, store, traitsComponentType);
        if (traits != null) {
            builder.traits(readTraits(traits));
        }

        TameworkCommandLinksComponent commandLinks = getComponent(reference, store, commandLinksComponentType);
        if (commandLinks != null) {
            builder.commandStates(readCommandStates(commandLinks));
        }

        return builder.build();
    }

    @Nullable
    private static <T extends Component<EntityStore>> T getComponent(
            @Nonnull Ref<EntityStore> reference,
            @Nonnull Store<EntityStore> store,
            @Nullable ComponentType<EntityStore, T> componentType) {
        return componentType != null ? store.getComponent(reference, componentType) : null;
    }

    @Nonnull
    static Map<String, Double> readNeeds(@Nullable TameworkNeedsComponent needs) {
        if (needs == null) {
            return Map.of();
        }
        return Map.of(
                "hunger", needs.getHunger(),
                "thirst", needs.getThirst()
        );
    }

    @Nonnull
    static Map<String, Double> readTraits(@Nullable TameworkTraitsComponent traits) {
        if (traits == null) {
            return Map.of();
        }
        TameworkTraitsComponent.TraitValue[] values = traits.getTraitValues();
        if (values.length == 0) {
            return Map.of();
        }
        Map<String, Double> snapshot = new HashMap<>();
        for (TameworkTraitsComponent.TraitValue value : values) {
            if (value == null || value.getId() == null || value.getId().isBlank()) {
                continue;
            }
            snapshot.put(value.getId(), value.getValue());
        }
        return snapshot.isEmpty() ? Map.of() : Map.copyOf(snapshot);
    }

    @Nonnull
    static Map<String, String> readCommandStates(@Nullable TameworkCommandLinksComponent commandLinks) {
        if (commandLinks == null) {
            return Map.of();
        }
        String[] toolIds = commandLinks.getToolIds();
        int toolCount = toolIds != null ? toolIds.length : 0;
        return Map.of(
                "has_home", Boolean.toString(commandLinks.hasHome()),
                "linked_tool_count", Integer.toString(toolCount)
        );
    }
}
