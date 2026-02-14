package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwGlobalConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamRequirement;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionBehaviorTest {

    @Test
    void roleParamResolutionUsesFirstScopeWithValue() throws Exception {
        ActionTameworkInteract interact = newInteract();
        StdScope primary = new StdScope(null);
        StdScope secondary = new StdScope(null);
        primary.addConst("TestParam", "Primary");
        secondary.addConst("TestParam", "Secondary");

        InteractionContextSnapshot ctx = InteractionContextSnapshot.from(null, new StdScope[] { primary, secondary });
        String result = interact.getRoleStringParam(null, ctx, "TestParam");
        assertEquals("Primary", result);
    }

    @Test
    void roleParamResolutionFallsBackToLaterScopes() throws Exception {
        ActionTameworkInteract interact = newInteract();
        StdScope primary = new StdScope(null);
        StdScope secondary = new StdScope(null);
        secondary.addConst("TestParam", "Secondary");

        InteractionContextSnapshot ctx = InteractionContextSnapshot.from(null, new StdScope[] { primary, secondary });
        String result = interact.getRoleStringParam(null, ctx, "TestParam");
        assertEquals("Secondary", result);
    }

    @Test
    void cooldownResolutionPrefersEntryOverride() throws Exception {
        ActionTameworkInteract interact = newInteract();
        TwInteractionConfig config = newInteractionConfig();
        TwInteractionConfig.Cooldowns cooldowns = new TwInteractionConfig.Cooldowns();

        Field cooldownsField = TwInteractionConfig.class.getDeclaredField("cooldowns");
        cooldownsField.setAccessible(true);
        cooldownsField.set(config, cooldowns);

        Field interactionSeconds = TwInteractionConfig.Cooldowns.class.getDeclaredField("interactionSeconds");
        interactionSeconds.setAccessible(true);
        interactionSeconds.set(cooldowns, 10);

        TwInteractionConfig.CustomInteraction entry = new TwInteractionConfig.CustomInteraction();
        Field entryCooldown = TwInteractionConfig.InteractionEntry.class.getDeclaredField("cooldownSeconds");
        entryCooldown.setAccessible(true);
        entryCooldown.set(entry, 3);

        InteractionCooldowns cooldownsHelper = new InteractionCooldowns(
                interact,
                TwGlobalConfig.DEFAULT_COOLDOWN_ALARM_PREFIX
        );
        int result = cooldownsHelper.resolveCooldownSeconds(config, entry);
        assertEquals(3, result);

        entryCooldown.set(entry, null);
        int fallback = cooldownsHelper.resolveCooldownSeconds(config, entry);
        assertEquals(10, fallback);
    }

    @Test
    void paramMatchTypeAnyVsAll() throws Exception {
        ActionTameworkInteract interact = newInteract();
        StdScope scope = new StdScope(null);
        scope.addConst("Mood", "Happy");
        Role role = newRoleWithScope(scope);

        ParamRequirement any = new ParamRequirement();
        setField(any, "name", "Mood");
        setField(any, "match", TwInteractionConfig.MatchType.Any);
        setField(any, "values", new String[] { "Sad", "Happy" });

        ParamRequirement all = new ParamRequirement();
        setField(all, "name", "Mood");
        setField(all, "match", TwInteractionConfig.MatchType.All);
        setField(all, "values", new String[] { "Sad", "Happy" });

        assertTrue(interact.matchesParamRequirement(any, role));
        assertFalse(interact.matchesParamRequirement(all, role));
    }

    private static ActionTameworkInteract newInteract() throws Exception {
        Unsafe unsafe = getUnsafe();
        ActionTameworkInteract interact = (ActionTameworkInteract) unsafe.allocateInstance(ActionTameworkInteract.class);
        InteractionParamResolver resolver = new InteractionParamResolver(null, null, null);
        InteractionParamAccess paramAccess = new InteractionParamAccess(
                resolver,
                false,
                null,
                null,
                null,
                TwGlobalConfig.DEFAULT_LOVED_ITEMS_PARAM,
                TwGlobalConfig.DEFAULT_IS_HARVESTABLE_PARAM,
                TwGlobalConfig.DEFAULT_IS_MOUNTABLE_PARAM
        );
        InteractionConfigResolver configResolver = new InteractionConfigResolver(
                null,
                paramAccess,
                TwGlobalConfig.DEFAULT_CONFIG_PARAM
        );
        InteractionResolution resolution = new InteractionResolution(paramAccess, configResolver);
        InteractionFeedHelper feedHelper = new InteractionFeedHelper(paramAccess);
        InteractionAlarmHelper alarmHelper = new InteractionAlarmHelper(interact);
        InteractionItemRequirementResolver itemRequirements = new InteractionItemRequirementResolver(resolver);
        InteractionMatchHelpers matchHelpers = new InteractionMatchHelpers(interact, paramAccess, alarmHelper);
        InteractionParamMatcher paramMatcher = new InteractionParamMatcher(paramAccess);
        InteractionOwnershipHelper ownershipHelper = new InteractionOwnershipHelper(interact);
        InteractionCooldowns cooldowns = new InteractionCooldowns(interact, TwGlobalConfig.DEFAULT_COOLDOWN_ALARM_PREFIX);
        TameworkInteractRequirements requirements =
                new TameworkInteractRequirements(interact, feedHelper, alarmHelper, TwGlobalConfig.DEFAULT_HARVEST_ALARM);
        InteractionSelector selector =
                new InteractionSelector(interact, requirements, cooldowns, alarmHelper, TwGlobalConfig.DEFAULT_HARVEST_ALARM);
        InteractionDiagnostics diagnostics =
                new InteractionDiagnostics(interact, alarmHelper, TwGlobalConfig.DEFAULT_HARVEST_ALARM);
        InteractionSelection selection = new InteractionSelection(
                itemRequirements,
                matchHelpers,
                paramMatcher,
                ownershipHelper,
                requirements,
                selector,
                diagnostics
        );
        InteractionExecutor executor = new InteractionExecutor(new TameworkInteractEffects(interact), feedHelper);
        InteractionExecution execution = new InteractionExecution(executor, cooldowns);

        Field resolutionField = ActionTameworkInteract.class.getDeclaredField("resolution");
        resolutionField.setAccessible(true);
        resolutionField.set(interact, resolution);
        Field selectionField = ActionTameworkInteract.class.getDeclaredField("selection");
        selectionField.setAccessible(true);
        selectionField.set(interact, selection);
        Field executionField = ActionTameworkInteract.class.getDeclaredField("execution");
        executionField.setAccessible(true);
        executionField.set(interact, execution);
        return interact;
    }

    private static TwInteractionConfig newInteractionConfig() throws Exception {
        var ctor = TwInteractionConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static Role newRoleWithScope(StdScope scope) throws Exception {
        Unsafe unsafe = getUnsafe();
        Role role = (Role) unsafe.allocateInstance(Role.class);
        EntitySupport entitySupport = (EntitySupport) unsafe.allocateInstance(EntitySupport.class);

        Field sensorScopeField = EntitySupport.class.getDeclaredField("sensorScope");
        sensorScopeField.setAccessible(true);
        sensorScopeField.set(entitySupport, scope);

        Field entitySupportField = Role.class.getDeclaredField("entitySupport");
        entitySupportField.setAccessible(true);
        entitySupportField.set(role, entitySupport);

        return role;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe getUnsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
