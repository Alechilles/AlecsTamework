package com.alechilles.alecstamework.items.scarecrow;

import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.PropComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PreventItemMerging;
import com.hypixel.hytale.server.core.modules.entity.item.PreventPickup;
import java.lang.reflect.Field;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ScarecrowPlacementServiceTest {
    @Test
    void centersScarecrowAboveSurfaceAndFacesActor() {
        ScarecrowPlacementService.Placement placement = ScarecrowPlacementService.plan(
                10,
                20,
                30,
                new Vector3d(10.5, 24.0, 35.5)
        );

        assertEquals(new Vector3d(10.5, 21.01, 30.5), placement.position());
        assertEquals(0.0f, placement.rotation().pitch(), 0.0001f);
        assertEquals((float) Math.PI, placement.rotation().yaw(), 0.0001f);
        assertEquals(0.0f, placement.rotation().roll(), 0.0001f);
    }

    @Test
    void buildsPersistentCollectibleNativeSuppressorComponents() throws ReflectiveOperationException {
        ScarecrowPlacementService.Placement placement = ScarecrowPlacementService.plan(
                1,
                2,
                3,
                new Vector3d(1.5, 3.0, 8.5)
        );

        ScarecrowPlacementService.EntityComponents components =
                ScarecrowPlacementService.buildComponents(placement, itemStack(ScarecrowIds.ITEM_ID, 1));

        assertEquals(ScarecrowIds.ITEM_ID, components.blockEntity().getBlockTypeKey());
        assertEquals(placement.position(), components.transform().getPosition());
        assertEquals(2.0f, components.scale().getScale());
        assertEquals(ScarecrowIds.ITEM_ID, components.item().getItemStack().getItemId());
        assertEquals(1, components.item().getItemStack().getQuantity());
        assertSame(PreventPickup.INSTANCE, components.preventPickup());
        assertSame(PreventItemMerging.INSTANCE, components.preventMerging());
        assertSame(PropComponent.get(), components.prop());
        assertEquals(
                ScarecrowIds.COLLECT_ROOT_INTERACTION_ID,
                components.interactions().getInteractionId(com.hypixel.hytale.protocol.InteractionType.Use)
        );
        assertEquals(ScarecrowIds.SUPPRESSION_ID, components.suppression().getSpawnSuppression());
        assertNotNull(components.uuid().getUuid());
    }

    private static ItemStack itemStack(String itemId, int quantity) throws ReflectiveOperationException {
        ItemStack itemStack = (ItemStack) unsafe().allocateInstance(ItemStack.class);
        setField(ItemStack.class, itemStack, "itemId", itemId);
        setField(ItemStack.class, itemStack, "quantity", quantity);
        return itemStack;
    }

    private static void setField(Class<?> type, Object target, String fieldName, Object value)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Unsafe unsafe() throws ReflectiveOperationException {
        Field unsafe = Unsafe.class.getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        return (Unsafe) unsafe.get(null);
    }
}
