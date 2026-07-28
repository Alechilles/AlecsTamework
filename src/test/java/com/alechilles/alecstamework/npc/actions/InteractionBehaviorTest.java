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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for Interaction behavior. */
class InteractionBehaviorTest {
    private static final Path INTERACTION_EXECUTOR = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "npc", "actions", "InteractionExecutor.java"
    );
    private static final Path INTERACTION_HARVEST_EFFECTS = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "npc", "actions", "InteractionHarvestEffects.java"
    );
    private static final Path INTERACTION_EFFECTS = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "npc", "actions", "TameworkInteractEffects.java"
    );
    private static final Path INTERACTION_STATE_EFFECTS = Paths.get(
            "src", "main", "java",
            "com", "alechilles", "alecstamework", "npc", "actions", "InteractionStateEffects.java"
    );

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
    void setRoleEffectForwardsVisualChangeFlagWhileLegacyRoleSwapsRemainFalse() throws Exception {
        String interactionEffects = Files.readString(INTERACTION_EFFECTS, StandardCharsets.UTF_8);
        String stateEffects = Files.readString(INTERACTION_STATE_EFFECTS, StandardCharsets.UTF_8);

        assertTrue(interactionEffects.contains(
                "stateEffects.applySetRole(roleId, effect.getChangeAppearance(), npcRef, role, store)"),
                "SetRole must forward its opt-in visual-change flag."
        );
        assertTrue(interactionEffects.contains(
                "stateEffects.applySetRole(roleId, false, npcRef, role, store)"),
                "Existing tame role swaps must retain the false visual-change default."
        );
        assertTrue(stateEffects.contains(
                "boolean changeAppearance, Ref<EntityStore> npcRef, Role role, Store<EntityStore> store)"),
                "Role changes must accept the interaction visual-change flag."
        );
        assertTrue(stateEffects.contains(
                "roleIndex,\n                changeAppearance,\n                store"),
                "RoleChangeSystem must receive the requested visual-change flag."
        );
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

    @Test
    void optimizedHarvestChecksCooldownBeforeRewardsAndEnsuresAfterState() throws Exception {
        String content = Files.readString(INTERACTION_EXECUTOR, StandardCharsets.UTF_8);

        int startHarvest = content.indexOf("effects.applyStartHarvest");
        int checkCooldown = content.indexOf("effects.isHarvestCooldownReady(npcRef, role, store, ctx)");
        int containerTransform = content.indexOf("effects.applyHarvestContainerTransform");
        int ensureCooldown = content.indexOf("effects.ensureHarvestCooldownAfterState(npcRef, role, store, ctx)");
        int customEffects = content.indexOf("effects.applyCustomEffects", startHarvest);

        assertTrue(startHarvest >= 0, "Optimized harvest should start the harvest state.");
        assertTrue(checkCooldown >= 0, "Optimized harvest must check Harvest_Ready before rewards.");
        assertTrue(containerTransform > checkCooldown, "Container rewards should only run after cooldown readiness passes.");
        assertTrue(startHarvest > containerTransform, "Optimized harvest should only start the harvest state after rewards are applied.");
        assertTrue(ensureCooldown > startHarvest, "Optimized harvest should repair/confirm the cooldown after state transition.");
        assertTrue(customEffects > ensureCooldown, "Harvest cooldown and state should be handled before later custom effects run.");
    }

    @Test
    void optimizedHarvestFailsClosedWhenCooldownCannotBeApplied() throws Exception {
        String content = Files.readString(INTERACTION_EXECUTOR, StandardCharsets.UTF_8);

        int checkCooldown = content.indexOf("if (!effects.isHarvestCooldownReady(npcRef, role, store, ctx))");
        int returnFalse = content.indexOf("return false;", checkCooldown);
        int containerTransform = content.indexOf("effects.applyHarvestContainerTransform", checkCooldown);

        assertTrue(checkCooldown >= 0, "Optimized harvest must check whether the harvest cooldown is ready.");
        assertTrue(returnFalse > checkCooldown, "Cooldown failure should stop the optimized harvest.");
        assertTrue(containerTransform > returnFalse, "Container rewards should not run before cooldown failure is handled.");
    }

    @Test
    void optimizedHarvestLogsEachExecutionStage() throws Exception {
        String content = Files.readString(INTERACTION_EXECUTOR, StandardCharsets.UTF_8);

        assertTrue(content.contains("effects.logHarvestExecution(\"selected\""),
                "Optimized harvest should log when the harvest path is selected.");
        assertTrue(content.contains("effects.logHarvestExecution(\"cooldown-blocked\""),
                "Optimized harvest should log when active cooldown blocks rewards.");
        assertTrue(content.contains("\"cooldown-ensured\""),
                "Optimized harvest should log after post-state cooldown confirmation.");
        assertTrue(content.contains("effects.logHarvestExecution(\"state-applied\""),
                "Optimized harvest should log after the harvest state starts.");
    }

    @Test
    void optimizedHarvestResolvesCooldownDurationFromInteractionContext() throws Exception {
        String content = Files.readString(INTERACTION_HARVEST_EFFECTS, StandardCharsets.UTF_8);

        int stringArrayLookup = content.indexOf(
                "owner.getRoleStringArrayParam(role, context, HARVEST_TIMEOUT_PARAMETER)"
        );
        int cooldownReadyCheck = content.indexOf(
                "ActionTameworkHarvestAlarm.isHarvestCooldownReady(npcRef, role, store, baseSeconds)"
        );
        int cooldownEnsure = content.indexOf(
                "ActionTameworkHarvestAlarm.ensureHarvestCooldownActive("
        );
        int cooldownEnsureArgs = content.indexOf("npcRef, role, store, baseSeconds", cooldownEnsure);

        assertTrue(stringArrayLookup >= 0, "Optimized harvest should resolve HarvestTimeout from interaction params.");
        assertTrue(cooldownReadyCheck >= 0, "Resolved HarvestTimeout should be passed into the readiness check.");
        assertTrue(cooldownEnsure >= 0, "Resolved HarvestTimeout should be passed into the post-state cooldown check.");
        assertTrue(cooldownEnsureArgs > cooldownEnsure,
                "Post-state cooldown confirmation should receive the resolved timeout.");
    }

    @Test
    void containerHarvestBonusModeUsesInteractionContextParams() throws Exception {
        String content = Files.readString(INTERACTION_HARVEST_EFFECTS, StandardCharsets.UTF_8);

        int modeResolution = content.indexOf("String bonusMode = owner.getRoleStringParam(");
        int modeParam = content.indexOf(
                "CompanionHarvestBonusService.HARVEST_BONUS_MODE_PARAM",
                modeResolution
        );
        int preserveCheck = content.indexOf(
                "CompanionHarvestBonusService.shouldPreserveCooldown(",
                modeParam
        );

        assertTrue(modeResolution >= 0, "Container harvest should resolve HarvestBonusMode from interaction params.");
        assertTrue(modeParam > modeResolution, "Container harvest should request the HarvestBonusMode param.");
        assertTrue(preserveCheck > modeParam,
                "Cooldown preserve roll should use the resolved HarvestBonusMode, not raw role-scope fallback.");
    }

    @Test
    void harvestReadinessUsesTameworkAlarmWithoutBaseAlarmFallback() throws Exception {
        String content = Files.readString(Paths.get(
                "src", "main", "java",
                "com", "alechilles", "alecstamework", "npc", "actions", "ActionTameworkInteract.java"
        ), StandardCharsets.UTF_8);

        int durableReady = content.indexOf("TameworkAlarmService.isReady(npcRef, store, harvestAlarmName)");
        int alarmReady = content.indexOf("alarmHelper.isAlarmReady(npcRef, store, harvestAlarmName, ctx)");

        assertTrue(durableReady >= 0, "Harvest readiness should consult named Tamework alarm state.");
        assertEquals(-1, alarmReady, "Harvest readiness should not require base-game alarm state.");
    }

    @Test
    void harvestSelectionDoesNotBypassDurableCooldownForExecution() throws Exception {
        String selector = Files.readString(Paths.get(
                "src", "main", "java",
                "com", "alechilles", "alecstamework", "npc", "actions", "InteractionSelector.java"
        ), StandardCharsets.UTF_8);
        String requirements = Files.readString(Paths.get(
                "src", "main", "java",
                "com", "alechilles", "alecstamework", "npc", "actions", "TameworkInteractRequirements.java"
        ), StandardCharsets.UTF_8);

        assertTrue(selector.contains(": !owner.isHarvestAlarmReady(npcRef, store, ctx);"),
                "Selection should use the component-aware harvest readiness helper.");
        assertFalse(requirements.contains("? owner.isHarvestAlarmReady(npcRef, store, ctx)")
                        || requirements.contains(": alarmHelper.isAlarmReady(npcRef, store, harvestAlarmName, ctx)"),
                "Harvest requirements should not bypass durable cooldown state on execution.");
    }

    @Test
    void optimizedHarvestLogsContainerAndCooldownDiagnostics() throws Exception {
        String content = Files.readString(INTERACTION_HARVEST_EFFECTS, StandardCharsets.UTF_8);

        assertTrue(content.contains("TameworkHarvestDebug: cooldown-ready-request"),
                "Optimized harvest should log the resolved cooldown duration before applying it.");
        assertTrue(content.contains("TameworkHarvestDebug: container"),
                "Optimized harvest should log bucket/container transform decisions.");
        assertTrue(content.contains("TameworkHarvestDebug: cooldown-ensure-request"),
                "Optimized harvest should log the post-state cooldown confirmation.");
        assertTrue(content.contains("preserveCooldown="),
                "Optimized harvest should log whether a cooldown-preserve bonus was applied.");
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
