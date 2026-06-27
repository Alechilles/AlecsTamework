package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TameworkTameFoodDisplayResolverTest {
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

    private static TwInteractionConfig configWith(TwInteractionConfig.TameInteraction tame) throws Exception {
        TwInteractionConfig config = (TwInteractionConfig) getUnsafe().allocateInstance(TwInteractionConfig.class);
        setField(TwInteractionConfig.class, config, "enabled", true);
        setField(TwInteractionConfig.class, config, "interactions", new TwInteractionConfig.InteractionEntry[] { tame });
        return config;
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
}
