package com.alechilles.alecstamework.items.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import java.lang.reflect.Field;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Regression coverage for operation-specific bonded revival payment evidence. */
class TameworkBondedReviveEscrowComponentTest {
    @Test
    void exactEscrowEvidenceSurvivesAComponentClone() throws Exception {
        TameworkBondedReviveEscrowComponent escrow =
                TameworkBondedReviveEscrowComponent.create(
                        (short) 4, "panel:revive-7",
                        "Ingredient_Life_Essence", 3, -5_000L);
        escrow.setPhase(TameworkBondedReviveEscrowComponent.Phase.RESERVED);
        escrow.getInventory().setItemStackForSlot(
                (short) 0, itemStack("Ingredient_Life_Essence", 3));

        TameworkBondedReviveEscrowComponent clone = escrow.clone();

        assertTrue(clone.matches("panel:revive-7",
                "Ingredient_Life_Essence", 3));
        assertEquals(TameworkBondedReviveEscrowComponent.Phase.RESERVED,
                clone.phase());
        assertEquals(3, clone.reservedQuantity());
        assertEquals(-5_000L, clone.createdAtMs());
    }

    @Test
    void codecRoundTripPreservesInventoryIdentityPhaseAndSignedTimestamp()
            throws Exception {
        TameworkBondedReviveEscrowComponent escrow =
                TameworkBondedReviveEscrowComponent.create(
                        (short) 4, "panel:revive-codec",
                        "Ingredient_Life_Essence", 3, -9_001L);
        escrow.setPhase(TameworkBondedReviveEscrowComponent.Phase.RESERVED);
        escrow.getInventory().setItemStackForSlot(
                (short) 1, itemStack("Ingredient_Life_Essence", 3));
        ensureSimpleContainerCodec();

        BsonDocument encoded = TameworkBondedReviveEscrowComponent.CODEC
                .encode(escrow, new ExtraInfo());
        TameworkBondedReviveEscrowComponent decoded =
                TameworkBondedReviveEscrowComponent.CODEC.decode(
                        encoded, new ExtraInfo());

        assertTrue(decoded.matches(
                "panel:revive-codec", "Ingredient_Life_Essence", 3));
        assertEquals(TameworkBondedReviveEscrowComponent.Phase.RESERVED,
                decoded.phase());
        assertEquals(3, decoded.reservedQuantity());
        assertEquals(-9_001L, decoded.createdAtMs());
    }

    private void ensureSimpleContainerCodec() {
        synchronized (ItemContainer.CODEC) {
            if (ItemContainer.CODEC.getIdFor(
                    SimpleItemContainer.class) == null) {
                ItemContainer.CODEC.register(
                        "TameworkTestSimple", SimpleItemContainer.class,
                        SimpleItemContainer.CODEC);
            }
        }
    }

    @Test
    void foreignOrContaminatedStacksNeverBecomeChargeEvidence()
            throws Exception {
        TameworkBondedReviveEscrowComponent escrow =
                TameworkBondedReviveEscrowComponent.create(
                        (short) 4, "panel:revive-7",
                        "Ingredient_Life_Essence", 3, 1L);
        escrow.getInventory().setItemStackForSlot(
                (short) 0, itemStack("Ingredient_Life_Essence", 2));
        escrow.getInventory().setItemStackForSlot(
                (short) 1, itemStack("Ingredient_Foreign", 1));

        assertFalse(escrow.hasExactReservedCharge());
    }

    private ItemStack itemStack(String itemId, int quantity) throws Exception {
        ItemStack stack = (ItemStack) unsafe().allocateInstance(ItemStack.class);
        set(stack, "itemId", itemId);
        set(stack, "quantity", quantity);
        set(stack, "durability", 0D);
        set(stack, "maxDurability", 0D);
        set(stack, "metadata", new BsonDocument());
        return stack;
    }

    private void set(ItemStack stack, String name, Object value)
            throws Exception {
        Field field = ItemStack.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(stack, value);
    }

    private Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
