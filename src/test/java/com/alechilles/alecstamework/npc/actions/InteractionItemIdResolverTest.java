package com.alechilles.alecstamework.npc.actions;

import com.alechilles.alecstamework.config.assets.TwInteractionConfig;
import com.hypixel.hytale.server.npc.role.Role;
import com.hypixel.hytale.server.npc.role.support.EntitySupport;
import com.hypixel.hytale.server.npc.util.expression.StdScope;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionItemIdResolverTest {

    @Test
    void resolvesFeedRequirementItemsByCombiningParamAndExplicitItems() throws Exception {
        StdScope scope = new StdScope(null);
        scope.addConst("FeedItemsParam", new String[] { "Item_Apple" });
        Role role = newRoleWithScope(scope);
        InteractionContextSnapshot ctx = InteractionContextSnapshot.from(null, new StdScope[] { scope });
        InteractionItemIdResolver resolver = new InteractionItemIdResolver(new InteractionParamResolver(null, null, null));

        TwInteractionConfig.FeedInteraction interaction = new TwInteractionConfig.FeedInteraction();
        setField(TwInteractionConfig.FeedInteraction.class, interaction, "itemsParam", "FeedItemsParam");
        setField(
                TwInteractionConfig.FeedInteraction.class,
                interaction,
                "itemsInHand",
                new TwInteractionConfig.FeedItem[] { new TwInteractionConfig.FeedItem("Item_Carrot", null) }
        );

        InteractionRequiredItems resolved = resolver.resolveFeedRequirementItems(
                interaction,
                role,
                ctx,
                new String[] { "Item_Wheat" }
        );

        assertTrue(resolved.requiresItems());
        assertArrayEquals(new String[] { "Item_Apple", "Item_Carrot" }, resolved.getItems());
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
