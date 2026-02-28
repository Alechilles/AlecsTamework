package com.alechilles.alecstamework.npc.sensors;

import com.alechilles.alecstamework.npc.progression.CompanionLifeStageService;
import com.alechilles.alecstamework.npc.progression.CompanionRoleIdResolver;
import com.alechilles.alecstamework.npc.sensors.builders.BuilderSensorTameworkLifeStage;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.asset.builder.BuilderSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.sensorinfo.InfoProvider;
import java.util.Locale;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Sensor that matches when the resolved Tamework companion lifecycle stage equals the configured stage.
 */
public final class SensorTameworkLifeStage extends TameworkSensorBase {
    private final String expectedStage;

    public SensorTameworkLifeStage(@Nonnull BuilderSensorTameworkLifeStage builder,
                                   @Nonnull BuilderSupport support) {
        super(builder);
        this.expectedStage = normalizeStage(builder.getStage(support));
    }

    @Override
    public boolean matches(@Nonnull Ref<EntityStore> ref,
                           @Nonnull Role role,
                           double dt,
                           @Nonnull Store<EntityStore> store) {
        if (!super.matches(ref, role, dt, store)) {
            return false;
        }
        String roleId = CompanionRoleIdResolver.resolveRoleId(ref, store);
        String currentStage = CompanionLifeStageService.resolveCurrentStage(ref, store, roleId);
        return expectedStage.equals(normalizeStage(currentStage));
    }

    @Override
    public InfoProvider getSensorInfo() {
        return null;
    }

    @Nonnull
    private static String normalizeStage(@Nullable String stage) {
        if (stage == null || stage.isBlank()) {
            return CompanionLifeStageService.STAGE_ADULT;
        }
        String normalized = stage.trim().toLowerCase(Locale.ROOT);
        if ("baby".equals(normalized)) {
            return CompanionLifeStageService.STAGE_BABY;
        }
        if ("adolescent".equals(normalized) || "juvenile".equals(normalized)) {
            return CompanionLifeStageService.STAGE_ADOLESCENT;
        }
        return CompanionLifeStageService.STAGE_ADULT;
    }
}
