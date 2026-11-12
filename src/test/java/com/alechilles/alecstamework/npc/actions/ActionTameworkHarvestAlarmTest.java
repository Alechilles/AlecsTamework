package com.alechilles.alecstamework.npc.actions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.npc.asset.builder.Builder;
import com.hypixel.hytale.server.npc.asset.builder.BuilderManager;
import com.hypixel.hytale.server.npc.asset.builder.BuilderModifier;
import com.hypixel.hytale.server.npc.asset.builder.BuilderParameters;
import com.hypixel.hytale.server.npc.asset.builder.StateMappingHelper;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.builders.BuilderRole;
import com.hypixel.hytale.server.npc.role.builders.BuilderRoleVariant;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Field;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionTameworkHarvestAlarmTest {
    private static final int TEMPLATE_INDEX = 42;

    @Test
    void scalesHarvestCooldownSecondsWithTalentMultiplier() {
        assertEquals(45.0, ActionTameworkHarvestAlarm.scaleHarvestCooldownSeconds(60.0, 0.75), 0.000001);
        assertEquals(60.0, ActionTameworkHarvestAlarm.scaleHarvestCooldownSeconds(60.0, 0.0), 0.000001);
        assertEquals(60.0, ActionTameworkHarvestAlarm.scaleHarvestCooldownSeconds(60.0, Double.NaN), 0.000001);
        assertEquals(0.0, ActionTameworkHarvestAlarm.scaleHarvestCooldownSeconds(-10.0, 0.75), 0.000001);
    }

    @Test
    void boundsLegacyAndNewRecoverySpeedTogether() {
        assertEquals(30.0, ActionTameworkHarvestAlarm.scaleHarvestCooldownSeconds(60.0, 0.5, 1.0), 0.000001);
    }

    @Test
    void harvestCooldownUsesWorldTimeBasisEvenWhenEpochIsEarly() {
        Instant worldTime = Instant.parse("0001-01-01T00:00:00Z");

        Instant until = HarvestAlarmTimeBasis.resolveCooldownUntil(worldTime, 90.0);

        assertEquals(Instant.parse("0001-01-01T00:01:30Z"), until);
    }

    @Test
    void harvestTimeoutSupportsVanillaTemporalRangeStrings() {
        double seconds = HarvestAlarmTimeBasis.resolveTemporalRangeSeconds(new String[] {"P1D", "P1D"}, () -> 0.0);

        assertEquals(86400.0, seconds, 0.000001);
    }

    /**
     * A Variant role (Rune_Husbandry's Tamed_Cow) sets HarvestTimeout in Modify and declares no
     * parameters of its own. Reading only the variant's declared parameters found no timeout, so
     * `tw debug set harvestready --mode=false` could not restart the cooldown.
     */
    @Test
    void harvestTimeoutOfAVariantIsItsModifyValueOverTheTemplateDefault() throws Exception {
        BuilderRole template = (BuilderRole) unsafe().allocateInstance(BuilderRole.class);
        setField(template, "builderParameters", parameters(timeoutParameter("P1D", "P1D")));
        BuilderParameters variantParameters = parameters(new JsonObject());
        BuilderRoleVariant variant = new BuilderRoleVariant();
        setField(variant, "referenceIndex", TEMPLATE_INDEX);
        setField(variant, "builderParameters", variantParameters);
        setField(variant, "modifier", timeoutModifier(variantParameters, "PT11H", "PT14H"));
        setField(variant, "builderManager", new TemplateManager(template));

        assertEquals(11 * 3600.0,
                ActionTameworkHarvestAlarm.resolveHarvestTimeoutSeconds(variant, () -> 0.0), 0.000001);
    }

    private static JsonObject timeoutParameter(String min, String max) {
        JsonObject value = new JsonObject();
        value.add("Value", range(min, max));
        JsonObject entries = new JsonObject();
        entries.add("HarvestTimeout", value);
        return entries;
    }

    private static BuilderParameters parameters(JsonObject entries) throws Exception {
        var constructor = BuilderParameters.class.getDeclaredConstructor(StdScope.class, String.class, String.class);
        constructor.setAccessible(true);
        BuilderParameters parameters = constructor.newInstance(new StdScope(null), "test", null);
        JsonObject root = new JsonObject();
        root.add("Parameters", entries);
        parameters.readJSON(root, new StateMappingHelper());
        parameters.addParametersToScope();
        return parameters;
    }

    private static BuilderModifier timeoutModifier(BuilderParameters parameters, String min, String max) {
        JsonObject modify = new JsonObject();
        modify.add("HarvestTimeout", range(min, max));
        JsonObject root = new JsonObject();
        root.add("Modify", modify);
        return BuilderModifier.fromJSON(root, parameters, new StateMappingHelper(), new ExtraInfo());
    }

    private static JsonArray range(String min, String max) {
        JsonArray range = new JsonArray();
        range.add(min);
        range.add(max);
        return range;
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (sun.misc.Unsafe) field.get(null);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // Look in the superclass.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class TemplateManager extends BuilderManager {
        private final BuilderRole template;

        private TemplateManager(BuilderRole template) {
            this.template = template;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Builder<T> getCachedBuilder(int index, Class<?> category) {
            return index == TEMPLATE_INDEX && category == Role.class ? (Builder<T>) template : null;
        }
    }
}
