package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.components.TameworkTalentsComponent;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkHasTalent;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import javax.annotation.Nonnull;

/** Sensor that matches when this NPC has purchased a configured talent. */
public final class SensorTameworkHasTalent extends TameworkSensorBase {
    private final String talentId;
    private final ComponentType<EntityStore, TameworkTalentsComponent> talentsComponentType;

    public SensorTameworkHasTalent(@Nonnull BuilderSensorTameworkHasTalent builder,
                                   @Nonnull BuilderSupport support) {
        this(builder, support, TameworkTalentsComponent.getComponentType());
    }

    SensorTameworkHasTalent(@Nonnull BuilderSensorTameworkHasTalent builder,
                             @Nonnull BuilderSupport support,
                             ComponentType<EntityStore, TameworkTalentsComponent> talentsComponentType) {
        super(builder);
        String configured = builder.getTalentId(support);
        this.talentId = configured == null ? "" : configured.trim();
        this.talentsComponentType = talentsComponentType;
    }

    @Nonnull
    public String getTalentId() {
        return talentId;
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        if (!super.matches(ref, role, dt, store) || talentId.isBlank()) {
            return false;
        }
        if (talentsComponentType == null) {
            return false;
        }
        TameworkTalentsComponent talents = store.getComponent(ref, talentsComponentType);
        return matchesTalent(talents, talentId);
    }

    static boolean matchesTalent(TameworkTalentsComponent talents, String talentId) {
        return talents != null && talentId != null && !talentId.isBlank()
                && talents.hasPurchasedTalent(talentId);
    }

    @Override
    public InfoProvider getSensorInfo() {
        return null;
    }
}
