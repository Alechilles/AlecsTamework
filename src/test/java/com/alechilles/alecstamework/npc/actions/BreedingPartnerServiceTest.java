package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwBreedingConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Regression coverage for partner-specific gender config resolution. */
class BreedingPartnerServiceTest {

    @Test
    void candidateConfigDoesNotFallBackToSourceConfigForUnrelatedRole() throws Exception {
        TwBreedingConfig sourceConfig = configWithRoles("SourceRole");

        assertNull(BreedingPartnerService.resolveCandidateConfig("OtherRole", sourceConfig));
    }

    @Test
    void candidateConfigCanUseSourceConfigWhenSourceDeclaresCandidateRole() throws Exception {
        TwBreedingConfig sourceConfig = configWithRoles("SourceRole", "OtherRole");

        assertSame(sourceConfig, BreedingPartnerService.resolveCandidateConfig("mods:OtherRole", sourceConfig));
    }

    @Test
    void candidateConfigCanUseSourceConfigWhenLifecycleDeclaresCandidateRole() throws Exception {
        TwBreedingConfig sourceConfig = configWithRoles("SourceRole");
        TwBreedingConfig.RoleFamily family = new TwBreedingConfig.RoleFamily();
        setField(family, "adultRoleId", "OtherRole");
        setField(sourceConfig.getOffspringLifecycle(), "families", new TwBreedingConfig.RoleFamily[] { family });

        assertSame(sourceConfig, BreedingPartnerService.resolveCandidateConfig("mods:OtherRole", sourceConfig));
    }

    private static TwBreedingConfig configWithRoles(String... roleIds) throws Exception {
        Constructor<TwBreedingConfig> constructor = TwBreedingConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwBreedingConfig config = constructor.newInstance();
        setField(config, "roleIds", roleIds);
        return config;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
