package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import java.lang.reflect.Field;
import java.util.Map;
import org.joml.Vector3i;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Verifies that only exact, eligible Tamework coop configs establish managed authority. */
class ManagedCoopAuthorityResolverTest {

    @Test
    void resolvesExactNormalizedAuthorityFromCoopAsset() throws Exception {
        TwCoopConfig config = config("Coop_Config", "Coop_Chicken", true, false);
        ManagedCoopAuthorityResolver resolver = resolver(Map.of(), Map.of("coop_chicken", config));

        ManagedCoopContext result = resolver.resolve(
                "  World_A  ", "ignored", " COOP_CHICKEN ",
                new Vector3i(4, 5, 6), 3, null
        );

        assertEquals("world_a", result.worldName());
        assertEquals("coop_chicken", result.coopId());
        assertEquals(new Vector3i(4, 5, 6), result.block());
        assertEquals(3, result.blockRotationIndex());
        assertSame(config, result.config());
        assertEquals("world_a|4|5|6|coop=coop_chicken", result.coopKey());
    }

    @Test
    void normalizesBlockStateAndFallsBackToTrailingIdentifier() throws Exception {
        TwCoopConfig config = config("Coop_Config", "Coop_Chicken", true, false);
        ManagedCoopAuthorityResolver resolver = resolver(
                Map.of("coop_chicken", config),
                Map.of()
        );

        ManagedCoopContext result = resolver.resolve(
                "world", "*mod:Coop_Chicken_State_Definitions_Open[Facing=North]", null,
                new Vector3i(1, 2, 3), 0, null
        );

        assertSame(config, result.config());
        assertEquals("coop_chicken", result.coopId());
    }

    @Test
    void rejectsMissingEvidenceAndIneligibleConfigs() throws Exception {
        TwCoopConfig disabled = config("Disabled", "Coop_Chicken", false, false);
        TwCoopConfig preserveUuid = config("Invalid", "Coop_Chicken", true, true);

        assertNull(resolver(Map.of(), Map.of("coop_chicken", disabled)).resolve(
                "world", null, "coop_chicken", new Vector3i(), 0, null
        ));
        assertNull(resolver(Map.of(), Map.of("coop_chicken", preserveUuid)).resolve(
                "world", null, "coop_chicken", new Vector3i(), 0, null
        ));
        assertNull(resolver(Map.of(), Map.of()).resolve(
                " ", null, null, new Vector3i(), 0, null
        ));
        assertNull(resolver(Map.of(), Map.of()).resolve(
                "world", null, null, null, 0, null
        ));
    }

    private static ManagedCoopAuthorityResolver resolver(Map<String, TwCoopConfig> blockConfigs,
                                                         Map<String, TwCoopConfig> coopConfigs) {
        return new ManagedCoopAuthorityResolver(new ManagedCoopAuthorityResolver.ConfigLookup() {
            @Override
            public TwCoopConfig forBlockType(String blockTypeId) {
                return blockConfigs.get(blockTypeId);
            }

            @Override
            public TwCoopConfig forCoop(String coopId) {
                return coopConfigs.get(coopId);
            }
        });
    }

    private static TwCoopConfig config(String id,
                                       String coopId,
                                       boolean enabled,
                                       boolean preserveUuid) throws Exception {
        var constructor = TwCoopConfig.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        TwCoopConfig config = constructor.newInstance();
        set(config, "id", id);
        set(config, "coopId", coopId);
        set(config, "enabled", enabled);
        set(config.getIdentityRules(), "preserveUUID", preserveUuid);
        return config;
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
