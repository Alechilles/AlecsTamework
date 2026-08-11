package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.alechilles.alecstamework.npc.compat.NpcSupportTestFixture;
import com.alechilles.alecstamework.npc.sensors.SensorTameworkEffectActive;
import com.hypixel.hytale.server.npc.instructions.ExecutionSupport;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.util.ComponentInfo;
import com.hypixel.hytale.server.npc.util.IAnnotatedComponent;
import com.hypixel.hytale.server.npc.util.IAnnotatedComponentCollection;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TameworkTameFoodDisplayResolverTest {
    @AfterEach
    void clearNpcSupport() {
        NpcSupportTestFixture.clear();
    }

    @Test
    void tameItemsParamBeatsExplicitAndLovedItems() throws Exception {
        StdScope scope = new StdScope(null);
        scope.addConst("TameFoodParam", new String[] { "Item_Apple" });
        scope.addConst("AttractiveItemSet", new String[] { "Item_Wheat" });
        Role role = newRoleWithScope(scope);
        TwInteractionConfig.TameInteraction tame = new TwInteractionConfig.TameInteraction();
        setField(TwInteractionConfig.TameInteraction.class, tame, "itemsParam", "TameFoodParam");
        setField(TwInteractionConfig.TameInteraction.class, tame, "itemsInHand", new String[] { "Item_Carrot" });

        String[] resolved = new TameworkTameFoodDisplayResolver("AttractiveItemSet")
                .resolveTamingFoodItemIds(configWith(tame), role);

        assertArrayEquals(new String[] { "Item_Apple" }, resolved);
    }

    @Test
    void explicitTameItemsBeatLovedItems() throws Exception {
        StdScope scope = new StdScope(null);
        scope.addConst("AttractiveItemSet", new String[] { "Item_Wheat" });
        Role role = newRoleWithScope(scope);
        TwInteractionConfig.TameInteraction tame = new TwInteractionConfig.TameInteraction();
        setField(TwInteractionConfig.TameInteraction.class, tame, "itemsInHand", new String[] { "Item_Carrot" });

        String[] resolved = new TameworkTameFoodDisplayResolver("AttractiveItemSet")
                .resolveTamingFoodItemIds(configWith(tame), role);

        assertArrayEquals(new String[] { "Item_Carrot" }, resolved);
    }

    @Test
    void lovedItemsArePreferredTameFoodFallback() throws Exception {
        StdScope scope = new StdScope(null);
        scope.addConst("AttractiveItemSet", new String[] { "Item_Wheat" });
        Role role = newRoleWithScope(scope);

        String[] resolved = new TameworkTameFoodDisplayResolver("AttractiveItemSet")
                .resolveTamingFoodItemIds(configWith(new TwInteractionConfig.TameInteraction()), role);

        assertArrayEquals(new String[] { "Item_Wheat" }, resolved);
    }

    @Test
    void disabledLovedItemsDoNotFallbackToFeedItems() throws Exception {
        StdScope scope = new StdScope(null);
        scope.addConst("AttractiveItemSet", new String[] { "Item_Wheat" });
        scope.addConst("FoodItemIDs", new String[] { "Tw_Feed_Herbivore" });
        Role role = newRoleWithScope(scope);
        TwInteractionConfig.TameInteraction tame = new TwInteractionConfig.TameInteraction();
        setField(TwInteractionConfig.TameInteraction.class, tame, "useLovedItems", false);

        String[] resolved = new TameworkTameFoodDisplayResolver("AttractiveItemSet")
                .resolveTamingFoodItemIds(configWith(tame), role);

        assertArrayEquals(new String[0], resolved);
    }

    @Test
    void foodDisplayKeepsPreferredFoodSeparateFromCompatibleFeedItems() throws Exception {
        StdScope scope = new StdScope(null);
        scope.addConst("AttractiveItemSet", new String[] { "Tool_Feedbag", "Plant_Crop_Lettuce_Item" });
        Role role = newRoleWithScope(scope);
        TwInteractionConfig.TameInteraction tame = new TwInteractionConfig.TameInteraction();
        setField(TwInteractionConfig.TameInteraction.class, tame, "itemsParam", "AttractiveItemSet");
        TwInteractionConfig.FeedInteraction feed = new TwInteractionConfig.FeedInteraction();
        setField(TwInteractionConfig.FeedInteraction.class, feed, "itemsParam", "AttractiveItemSet");
        setField(TwInteractionConfig.FeedInteraction.class, feed, "itemsInHand", new TwInteractionConfig.FeedItem[] {
                new TwInteractionConfig.FeedItem("Tw_Feed_Herbivore", null)
        });

        TameworkTameFoodDisplayResolver.FoodDisplay display =
                new TameworkTameFoodDisplayResolver("AttractiveItemSet")
                        .resolveFoodDisplayItemIds(configWith(tame, feed), role, true);

        assertArrayEquals(new String[] { "Plant_Crop_Lettuce_Item" }, display.favoriteItemIds());
        assertArrayEquals(new String[] { "Tool_Feedbag", "Tw_Feed_Herbivore" }, display.compatibleItemIds());
    }

    @Test
    void foodDisplayUsesRolePreferredFoodBeforeTameFeedFallback() throws Exception {
        StdScope scope = new StdScope(null);
        scope.addConst("AttractiveItemSet", new String[] { "Plant_Crop_Lettuce_Item" });
        Role role = newRoleWithScope(scope);
        TwInteractionConfig.TameInteraction tame = new TwInteractionConfig.TameInteraction();
        setField(TwInteractionConfig.TameInteraction.class, tame, "itemsInHand", new String[] { "Tw_Feed_Herbivore" });

        TameworkTameFoodDisplayResolver.FoodDisplay display =
                new TameworkTameFoodDisplayResolver("AttractiveItemSet")
                        .resolveFoodDisplayItemIds(configWith(tame), role, true);

        assertArrayEquals(new String[] { "Plant_Crop_Lettuce_Item" }, display.favoriteItemIds());
        assertArrayEquals(new String[] { "Tw_Feed_Herbivore" }, display.compatibleItemIds());
    }

    @Test
    void foodDisplayUsesExplicitFoodFavoriteBeforeAttractiveItems() throws Exception {
        StdScope scope = new StdScope(null);
        scope.addConst("FoodFavorite", new String[] { "Plant_Fruit_Apple" });
        scope.addConst("AttractiveItemSet", new String[] { "Tool_Feedbag", "Plant_Crop_Lettuce_Item" });
        Role role = newRoleWithScope(scope);
        TwInteractionConfig.FeedInteraction feed = new TwInteractionConfig.FeedInteraction();
        setField(TwInteractionConfig.FeedInteraction.class, feed, "itemsParam", "AttractiveItemSet");

        TameworkTameFoodDisplayResolver.FoodDisplay display =
                new TameworkTameFoodDisplayResolver("AttractiveItemSet")
                        .resolveFoodDisplayItemIds(configWith(feed), role, true);

        assertArrayEquals(new String[] { "Plant_Fruit_Apple" }, display.favoriteItemIds());
        assertArrayEquals(new String[] { "Tool_Feedbag", "Plant_Crop_Lettuce_Item" }, display.compatibleItemIds());
    }

    @Test
    void foodDisplayPromotesFirstCompatibleFoodWhenFavoriteSourcesAreEmpty() throws Exception {
        StdScope scope = new StdScope(null);
        Role role = newRoleWithScope(scope);
        TwInteractionConfig.FeedInteraction feed = new TwInteractionConfig.FeedInteraction();
        setField(TwInteractionConfig.FeedInteraction.class, feed, "itemsInHand", new TwInteractionConfig.FeedItem[] {
                new TwInteractionConfig.FeedItem("Plant_Crop_Lettuce_Item", null),
                new TwInteractionConfig.FeedItem("Tw_Feed_Herbivore", null)
        });

        TameworkTameFoodDisplayResolver.FoodDisplay display =
                new TameworkTameFoodDisplayResolver("AttractiveItemSet")
                        .resolveFoodDisplayItemIds(configWith(feed), role, true);

        assertArrayEquals(new String[] { "Plant_Crop_Lettuce_Item" }, display.favoriteItemIds());
        assertArrayEquals(new String[] { "Tw_Feed_Herbivore" }, display.compatibleItemIds());
    }

    @Test
    void tranquilizerRequirementCanResolveRoleThresholdParam() throws Exception {
        StdScope scope = new StdScope(null);
        scope.addConst("TranquilizerSleepThresholdSeconds", 80.0);
        Role role = newRoleWithScope(scope);

        double seconds = new TameworkTameFoodDisplayResolver("AttractiveItemSet")
                .resolveRequiredTranquilizerSeconds(role, configWith(new TwInteractionConfig.TameInteraction()));

        Assertions.assertEquals(80.0, seconds);
    }

    @Test
    void tranquilizerRequirementCanResolveRoleThresholdWithoutConfig() throws Exception {
        StdScope scope = new StdScope(null);
        scope.addConst("TranquilizerSleepThresholdSeconds", 80.0);
        Role role = newRoleWithScope(scope);

        double seconds = new TameworkTameFoodDisplayResolver("AttractiveItemSet")
                .resolveRequiredTranquilizerSeconds(role, null);

        Assertions.assertEquals(80.0, seconds);
    }

    @Test
    void tranquilizedSleepStateRequirementFallsBackToOneStack() throws Exception {
        TwInteractionConfig.StringRequirement state = new TwInteractionConfig.StringRequirement();
        setField(TwInteractionConfig.StringRequirement.class, state, "state", "Sleep");
        setField(TwInteractionConfig.StringRequirement.class, state, "subState", "Tranquilized");
        TwInteractionConfig.RequirementBucket bucket = new TwInteractionConfig.RequirementBucket();
        setField(TwInteractionConfig.RequirementBucket.class, bucket, "npcState", new TwInteractionConfig.StringRequirement[] { state });
        TwInteractionConfig.RequirementGroup group = new TwInteractionConfig.RequirementGroup();
        setField(TwInteractionConfig.RequirementGroup.class, group, "all", bucket);
        TwInteractionConfig.TameInteraction tame = new TwInteractionConfig.TameInteraction();
        setField(TwInteractionConfig.InteractionEntry.class, tame, "requires", group);

        double seconds = new TameworkTameFoodDisplayResolver("AttractiveItemSet")
                .resolveRequiredTranquilizerSeconds(null, configWith(tame));

        Assertions.assertEquals(30.0, seconds);
    }

    @Test
    void roleThresholdBeatsStateRequirementFallback() throws Exception {
        StdScope scope = new StdScope(null);
        scope.addConst("TranquilizerSleepThresholdSeconds", 80.0);
        Role role = newRoleWithScope(scope);
        TwInteractionConfig.StringRequirement state = new TwInteractionConfig.StringRequirement();
        setField(TwInteractionConfig.StringRequirement.class, state, "state", "Sleep");
        setField(TwInteractionConfig.StringRequirement.class, state, "subState", "Tranquilized");
        TwInteractionConfig.RequirementBucket bucket = new TwInteractionConfig.RequirementBucket();
        setField(TwInteractionConfig.RequirementBucket.class, bucket, "npcState", new TwInteractionConfig.StringRequirement[] { state });
        TwInteractionConfig.RequirementGroup group = new TwInteractionConfig.RequirementGroup();
        setField(TwInteractionConfig.RequirementGroup.class, group, "all", bucket);
        TwInteractionConfig.TameInteraction tame = new TwInteractionConfig.TameInteraction();
        setField(TwInteractionConfig.InteractionEntry.class, tame, "requires", group);

        double seconds = new TameworkTameFoodDisplayResolver("AttractiveItemSet")
                .resolveRequiredTranquilizerSeconds(role, configWith(tame));

        Assertions.assertEquals(80.0, seconds);
    }

    @Test
    void roleParameterThresholdBeatsStateRequirementFallbackWhenLiveRoleScopeMissing() throws Exception {
        StdScope liveScope = new StdScope(null);
        StdScope roleParameterScope = new StdScope(null);
        roleParameterScope.addConst("TranquilizerSleepThresholdSeconds", 110.0);
        Role role = newRoleWithScope(liveScope);
        TwInteractionConfig.StringRequirement state = new TwInteractionConfig.StringRequirement();
        setField(TwInteractionConfig.StringRequirement.class, state, "state", "Sleep");
        setField(TwInteractionConfig.StringRequirement.class, state, "subState", "Tranquilized");
        TwInteractionConfig.RequirementBucket bucket = new TwInteractionConfig.RequirementBucket();
        setField(TwInteractionConfig.RequirementBucket.class, bucket, "npcState", new TwInteractionConfig.StringRequirement[] { state });
        TwInteractionConfig.RequirementGroup group = new TwInteractionConfig.RequirementGroup();
        setField(TwInteractionConfig.RequirementGroup.class, group, "all", bucket);
        TwInteractionConfig.TameInteraction tame = new TwInteractionConfig.TameInteraction();
        setField(TwInteractionConfig.InteractionEntry.class, tame, "requires", group);

        double seconds = new TameworkTameFoodDisplayResolver("AttractiveItemSet")
                .resolveRequiredTranquilizerSeconds(role, configWith(tame), roleParameterScope);

        Assertions.assertEquals(110.0, seconds);
    }

    @Test
    void componentTreeCanResolveBuiltTranquilizerSensorThreshold() throws Exception {
        IAnnotatedComponentCollection tree = new TestComponentCollection(
                newTranquilizerSensorWithThreshold(110.0)
        );

        double seconds = TameworkTameFoodDisplayResolver
                .resolveTranquilizerEffectThresholdFromComponentTreeForTests(tree);

        Assertions.assertEquals(110.0, seconds);
    }

    @Test
    void tranquilizerSensorTreeIgnoresUnrelatedEffects() throws Exception {
        IAnnotatedComponentCollection tree = new TestComponentCollection(
                newEffectSensorWithThreshold("Other_Status", 110.0)
        );

        double seconds = TameworkTameFoodDisplayResolver
                .resolveTranquilizerEffectThresholdFromComponentTreeForTests(tree);

        Assertions.assertEquals(0.0, seconds);
    }

    private static TwInteractionConfig configWith(TwInteractionConfig.InteractionEntry... interactions) throws Exception {
        TwInteractionConfig config = (TwInteractionConfig) getUnsafe().allocateInstance(TwInteractionConfig.class);
        setField(TwInteractionConfig.class, config, "enabled", true);
        setField(TwInteractionConfig.class, config, "interactions", interactions);
        return config;
    }

    private static Role newRoleWithScope(StdScope scope) throws Exception {
        return NpcSupportTestFixture.bindRoleWithSensorScope(scope);
    }

    private static SensorTameworkEffectActive newTranquilizerSensorWithThreshold(double thresholdSeconds)
            throws Exception {
        return newEffectSensorWithThreshold("Tw_Status_Tranquilized", thresholdSeconds);
    }

    private static SensorTameworkEffectActive newEffectSensorWithThreshold(String effectId, double thresholdSeconds)
            throws Exception {
        Unsafe unsafe = getUnsafe();
        SensorTameworkEffectActive sensor = (SensorTameworkEffectActive)
                unsafe.allocateInstance(SensorTameworkEffectActive.class);
        setField(SensorTameworkEffectActive.class, sensor, "effectId", effectId);
        setField(SensorTameworkEffectActive.class, sensor, "minRemainingSeconds", thresholdSeconds);
        return sensor;
    }

    private static void setField(Class<?> owner, Object target, String fieldName, Object value) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe getUnsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private record TestComponentCollection(IAnnotatedComponent... children)
            implements IAnnotatedComponentCollection {
        @Override
        public int componentCount() {
            return children.length;
        }

        @Override
        public IAnnotatedComponent getComponent(int index) {
            return children[index];
        }

        @Override
        public void getInfo(ExecutionSupport support, ComponentInfo componentInfo) {
        }

        @Override
        public void setContext(IAnnotatedComponent parent, int index) {
        }

        @Override
        public IAnnotatedComponent getParent() {
            return null;
        }

        @Override
        public int getIndex() {
            return 0;
        }
    }
}
