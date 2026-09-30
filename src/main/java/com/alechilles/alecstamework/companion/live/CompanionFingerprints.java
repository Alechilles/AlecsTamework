package com.alechilles.alecstamework.companion.live;

import com.alechilles.alecstamework.npc.components.TameworkAlarmComponent;
import com.alechilles.alecstamework.npc.components.TameworkAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkBreedingComponent;
import com.alechilles.alecstamework.npc.components.TameworkCommandLinksComponent;
import com.alechilles.alecstamework.npc.components.TameworkDynamicAttachmentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkHappinessComponent;
import com.alechilles.alecstamework.npc.components.TameworkLevelingComponent;
import com.alechilles.alecstamework.npc.components.TameworkLifeStageComponent;
import com.alechilles.alecstamework.npc.components.TameworkNeedsComponent;
import com.alechilles.alecstamework.npc.components.TameworkNpcNameComponent;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import com.alechilles.alecstamework.npc.components.TameworkTraitsComponent;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Fingerprints of the Tamework components on one companion body (spec 6.4). */
public final class CompanionFingerprints {
    /** One codec-encoded component. */
    public record Encoded<T extends Component<EntityStore>>(@Nonnull ComponentType<EntityStore, T> type,
                                                            @Nonnull BuilderCodec<T> codec) {
        int hash(ArchetypeChunk<EntityStore> chunk, int index) {
            T component = chunk.getComponent(index, type);
            return component == null ? 0 : codec.encode(component, ExtraInfo.THREAD_LOCAL.get()).hashCode();
        }
    }

    private final List<Encoded<?>> discrete;
    private final List<Encoded<?>> drift;
    @Nullable private final ComponentType<EntityStore, TameworkLevelingComponent> leveling;
    @Nullable private final ComponentType<EntityStore, TameworkLifeStageComponent> lifeStage;
    @Nullable private final ComponentType<EntityStore, TameworkBreedingComponent> breeding;
    @Nullable private final ComponentType<EntityStore, TameworkNeedsComponent> needs;
    @Nullable private final ComponentType<EntityStore, TameworkHappinessComponent> happiness;

    public CompanionFingerprints(@Nonnull List<Encoded<?>> discrete, @Nonnull List<Encoded<?>> drift,
                                 @Nullable ComponentType<EntityStore, TameworkLevelingComponent> leveling,
                                 @Nullable ComponentType<EntityStore, TameworkLifeStageComponent> lifeStage,
                                 @Nullable ComponentType<EntityStore, TameworkBreedingComponent> breeding,
                                 @Nullable ComponentType<EntityStore, TameworkNeedsComponent> needs,
                                 @Nullable ComponentType<EntityStore, TameworkHappinessComponent> happiness) {
        this.discrete = List.copyOf(discrete);
        this.drift = List.copyOf(drift);
        this.leveling = leveling;
        this.lifeStage = lifeStage;
        this.breeding = breeding;
        this.needs = needs;
        this.happiness = happiness;
    }

    /** Production set, built after TameworkComponentRegistrar has registered every type. */
    @Nonnull
    public static CompanionFingerprints production() {
        return new CompanionFingerprints(
                List.of(
                        new Encoded<>(TameworkOwnerComponent.getComponentType(), TameworkOwnerComponent.CODEC),
                        new Encoded<>(TameworkTamedComponent.getComponentType(), TameworkTamedComponent.CODEC),
                        new Encoded<>(TameworkNpcNameComponent.getComponentType(), TameworkNpcNameComponent.CODEC),
                        new Encoded<>(TameworkCommandLinksComponent.getComponentType(), TameworkCommandLinksComponent.CODEC),
                        new Encoded<>(TameworkTraitsComponent.getComponentType(), TameworkTraitsComponent.CODEC),
                        new Encoded<>(TameworkTalentsComponent.getComponentType(), TameworkTalentsComponent.CODEC),
                        new Encoded<>(TameworkAttachmentsComponent.getComponentType(), TameworkAttachmentsComponent.CODEC),
                        new Encoded<>(TameworkDynamicAttachmentsComponent.getComponentType(), TameworkDynamicAttachmentsComponent.CODEC),
                        new Encoded<>(TameworkAlarmComponent.getComponentType(), TameworkAlarmComponent.CODEC)),
                List.of(
                        new Encoded<>(TameworkNeedsComponent.getComponentType(), TameworkNeedsComponent.CODEC),
                        new Encoded<>(TameworkHappinessComponent.getComponentType(), TameworkHappinessComponent.CODEC),
                        new Encoded<>(TameworkLifeStageComponent.getComponentType(), TameworkLifeStageComponent.CODEC),
                        new Encoded<>(TameworkLevelingComponent.getComponentType(), TameworkLevelingComponent.CODEC),
                        new Encoded<>(TameworkBreedingComponent.getComponentType(), TameworkBreedingComponent.CODEC)),
                TameworkLevelingComponent.getComponentType(),
                TameworkLifeStageComponent.getComponentType(),
                TameworkBreedingComponent.getComponentType(),
                TameworkNeedsComponent.getComponentType(),
                TameworkHappinessComponent.getComponentType());
    }

    public int discrete(@Nonnull ArchetypeChunk<EntityStore> chunk, int index) {
        int h = 1;
        for (Encoded<?> e : discrete) {
            h = 31 * h + e.hash(chunk, index);
        }
        TameworkLevelingComponent lv = leveling == null ? null : chunk.getComponent(index, leveling);
        if (lv != null) {
            h = 31 * h + Objects.hash(lv.getConfigId(), lv.getLevel());
        }
        TameworkLifeStageComponent ls = lifeStage == null ? null : chunk.getComponent(index, lifeStage);
        if (ls != null) {
            h = 31 * h + Objects.hashCode(ls.getStage());
        }
        TameworkBreedingComponent br = breeding == null ? null : chunk.getComponent(index, breeding);
        if (br != null) {
            h = 31 * h + Objects.hash(br.getConfigId(), br.isEnabled(), br.getCooldownUntilMs(),
                    br.getManualBreedingPlayerUuid(), br.getManualBreedingUntilMs());
        }
        TameworkNeedsComponent nd = needs == null ? null : chunk.getComponent(index, needs);
        if (nd != null) {
            h = 31 * h + Objects.hashCode(nd.getConfigId());
        }
        TameworkHappinessComponent hp = happiness == null ? null : chunk.getComponent(index, happiness);
        if (hp != null) {
            h = 31 * h + Objects.hashCode(hp.getConfigId());
        }
        return h;
    }

    public int drift(@Nonnull ArchetypeChunk<EntityStore> chunk, int index) {
        int h = 1;
        for (Encoded<?> e : drift) {
            h = 31 * h + e.hash(chunk, index);
        }
        return h;
    }
}
