package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamRequirement;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

        Method resolveCooldown = ActionTameworkInteract.class.getDeclaredMethod(
                "resolveCooldownSeconds",
                TwInteractionConfig.class,
                TwInteractionConfig.InteractionEntry.class
        );
        resolveCooldown.setAccessible(true);
        int result = (int) resolveCooldown.invoke(interact, config, entry);
        assertEquals(3, result);

        entryCooldown.set(entry, null);
        int fallback = (int) resolveCooldown.invoke(interact, config, entry);
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
        Field paramResolverField = ActionTameworkInteract.class.getDeclaredField("paramResolver");
        paramResolverField.setAccessible(true);
        paramResolverField.set(interact, resolver);
        Field itemRequirementsField = ActionTameworkInteract.class.getDeclaredField("itemRequirements");
        itemRequirementsField.setAccessible(true);
        itemRequirementsField.set(interact, new InteractionItemRequirementResolver(resolver));
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
