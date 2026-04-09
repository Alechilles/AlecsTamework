package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ItemsInHandRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.NpcHealthPercentRequirement;
import com.alechilles.alecstamework.config.assets.TwInteractionConfig.ParamRequirement;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for Interaction behavior. */
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
                "TameworkInteract_Cooldown"
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

    @Test
    void paramRequirementFallsBackToGlobalScopeWhenRoleScopeMissing() throws Exception {
        StdScope globalScope = new StdScope(null);
        globalScope.addConst("IsPettable", true);

        InteractionParamResolver resolver = new InteractionParamResolver(globalScope, null, null);
        InteractionParamAccess paramAccess = new InteractionParamAccess(
                resolver,
                false,
                null,
                null,
                null,
                "LovedItems",
                "IsHarvestable",
                "IsMountable"
        );
        InteractionParamMatcher matcher = new InteractionParamMatcher(paramAccess);

        ParamRequirement requirement = new ParamRequirement();
        setField(requirement, "name", "IsPettable");
        setField(requirement, "operator", TwInteractionConfig.ParamOperator.Equals);
        setField(requirement, "match", TwInteractionConfig.MatchType.Any);
        setField(requirement, "values", new String[] { "true" });

        assertTrue(matcher.matchesParamRequirement(requirement, null));
    }

    @Test
    void playerHandEmptyRequirementTreatsMissingHeldItemAsEmpty() throws Exception {
        ActionTameworkInteract interact = newInteract();
        InteractionContextSnapshot ctx = InteractionContextSnapshot.from(null, new StdScope[0]);

        assertTrue(interact.isPlayerHandEmpty(ctx));
    }

    @Test
    void playerHandEmptyRequirementFailsWhenHeldItemIdPresent() throws Exception {
        ActionTameworkInteract interact = newInteract();
        InteractionContextSnapshot ctx = newContextSnapshotWithHeldItemId("Item_Test");

        assertFalse(interact.isPlayerHandEmpty(ctx));
    }

    @Test
    void itemsInHandNoneOfOperatorRejectsMatchingHeldItem() throws Exception {
        InteractionItemRequirementResolver resolver =
                new InteractionItemRequirementResolver(new InteractionParamResolver(null, null, null));
        ItemsInHandRequirement requirement = new ItemsInHandRequirement();
        setField(requirement, "items", new String[] { "Item_Carrot" });
        setField(requirement, "operator", TwInteractionConfig.ItemsMatchOperator.NoneOf);

        InteractionContextSnapshot ctx = newContextSnapshotWithHeldItemId("Item_Carrot");
        assertFalse(resolver.matchesItemsInHand(requirement, null, ctx));
    }

    @Test
    void itemsInHandNoneOfOperatorAcceptsDifferentHeldItem() throws Exception {
        InteractionItemRequirementResolver resolver =
                new InteractionItemRequirementResolver(new InteractionParamResolver(null, null, null));
        ItemsInHandRequirement requirement = new ItemsInHandRequirement();
        setField(requirement, "items", new String[] { "Item_Carrot" });
        setField(requirement, "operator", TwInteractionConfig.ItemsMatchOperator.NoneOf);

        InteractionContextSnapshot ctx = newContextSnapshotWithHeldItemId("Item_Lettuce");
        assertTrue(resolver.matchesItemsInHand(requirement, null, ctx));
    }

    @Test
    void npcHealthPercentRequirementSupportsLessThanOrEqual() throws Exception {
        NpcHealthPercentRequirement requirement = new NpcHealthPercentRequirement();
        setField(requirement, "operator", TwInteractionConfig.ParamOperator.LessThanOrEqual);
        setField(requirement, "value", 40.0);

        assertTrue(InteractionMatchHelpers.matchesNpcHealthPercentValue(35.0, requirement));
        assertTrue(InteractionMatchHelpers.matchesNpcHealthPercentValue(40.0, requirement));
        assertFalse(InteractionMatchHelpers.matchesNpcHealthPercentValue(45.0, requirement));
    }

    @Test
    void npcHealthPercentRequirementSupportsGreaterThan() throws Exception {
        NpcHealthPercentRequirement requirement = new NpcHealthPercentRequirement();
        setField(requirement, "operator", TwInteractionConfig.ParamOperator.GreaterThan);
        setField(requirement, "value", 60.0);

        assertTrue(InteractionMatchHelpers.matchesNpcHealthPercentValue(75.0, requirement));
        assertFalse(InteractionMatchHelpers.matchesNpcHealthPercentValue(60.0, requirement));
    }

    @Test
    void interactionRequireOwnerResolutionUsesGlobalToggle() {
        assertTrue(TameworkInteractRequirements.resolveInteractionRequireOwner(null, true));
        assertTrue(TameworkInteractRequirements.resolveInteractionRequireOwner(Boolean.FALSE, true));
        assertFalse(TameworkInteractRequirements.resolveInteractionRequireOwner(null, false));
        assertFalse(TameworkInteractRequirements.resolveInteractionRequireOwner(Boolean.TRUE, false));
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
                "LovedItems",
                "IsHarvestable",
                "IsMountable"
        );
        InteractionConfigResolver configResolver = new InteractionConfigResolver(
                null,
                paramAccess,
                "InteractionConfigId"
        );
        InteractionResolution resolution = new InteractionResolution(paramAccess, configResolver);
        InteractionFeedHelper feedHelper = new InteractionFeedHelper(paramAccess);
        InteractionAlarmHelper alarmHelper = new InteractionAlarmHelper(interact);
        InteractionItemRequirementResolver itemRequirements = new InteractionItemRequirementResolver(resolver);
        InteractionMatchHelpers matchHelpers = new InteractionMatchHelpers(interact, paramAccess, alarmHelper);
        InteractionParamMatcher paramMatcher = new InteractionParamMatcher(paramAccess);
        InteractionOwnershipHelper ownershipHelper = new InteractionOwnershipHelper(interact);
        InteractionCooldowns cooldowns = new InteractionCooldowns(interact, "TameworkInteract_Cooldown");
        TameworkInteractRequirements requirements =
                new TameworkInteractRequirements(interact, feedHelper, alarmHelper, "Harvest_Ready", true, null);
        InteractionSelector selector =
                new InteractionSelector(interact, requirements, cooldowns, alarmHelper, "Harvest_Ready");
        InteractionDiagnostics diagnostics =
                new InteractionDiagnostics(interact, alarmHelper, "Harvest_Ready");
        InteractionSelection selection = new InteractionSelection(
                itemRequirements,
                matchHelpers,
                paramMatcher,
                ownershipHelper,
                requirements,
                selector,
                diagnostics
        );
        InteractionExecutor executor = new InteractionExecutor(new TameworkInteractEffects(interact, null), feedHelper);
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

    private static InteractionContextSnapshot newContextSnapshotWithHeldItemId(String heldItemId) throws Exception {
        return newContextSnapshot(null, heldItemId);
    }

    private static InteractionContextSnapshot newContextSnapshot(ItemStack activeItem, String heldItemId) throws Exception {
        Constructor<InteractionContextSnapshot> constructor = InteractionContextSnapshot.class.getDeclaredConstructor(
                com.hypixel.hytale.server.core.entity.entities.Player.class,
                com.hypixel.hytale.server.core.inventory.Inventory.class,
                com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer.class,
                ItemStack.class,
                String.class,
                UUID.class,
                StdScope[].class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
                null,
                null,
                null,
                activeItem,
                heldItemId,
                null,
                new StdScope[0]
        );
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
