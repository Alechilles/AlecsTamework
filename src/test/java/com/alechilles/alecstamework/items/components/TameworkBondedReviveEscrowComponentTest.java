package com.alechilles.alecstamework.items.components;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alechilles.alecstamework.api.BondedCompanionReviveCost;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import java.lang.reflect.Field;
import java.util.List;
import org.bson.BsonDocument;
import org.bson.BsonArray;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

/** Regression coverage for operation-specific bonded revival payment evidence. */
class TameworkBondedReviveEscrowComponentTest {
    @Test
    void frozenOrderedRecipeAcceptsExactMultiStackEvidence() throws Exception {
        List<BondedCompanionReviveCost> recipe = List.of(
                new BondedCompanionReviveCost("Ingredient_Life_Essence", 2),
                new BondedCompanionReviveCost("Ingredient_Dragon_Essence", 4));
        TameworkBondedReviveEscrowComponent escrow =
                TameworkBondedReviveEscrowComponent.create(
                        (short) 4, "panel:revive-multi", recipe, -5_000L);
        escrow.getInventory().setItemStackForSlot(
                (short) 0, itemStack("Ingredient_Life_Essence", 2));
        escrow.getInventory().setItemStackForSlot(
                (short) 1, itemStack("Ingredient_Dragon_Essence", 4));

        assertEquals(recipe, escrow.costs());
        assertTrue(escrow.matches("panel:revive-multi", recipe));
        assertTrue(escrow.hasExactReservedCharge());
    }

    @Test
    void multiLineRecipeCodecRoundTripRetainsOrderAndRejectsSingletonMatch()
            throws Exception {
        List<BondedCompanionReviveCost> costs = List.of(
                new BondedCompanionReviveCost("Ingredient_Life_Essence", 2),
                new BondedCompanionReviveCost("Ingredient_Dragon_Essence", 4));
        TameworkBondedReviveEscrowComponent escrow =
                TameworkBondedReviveEscrowComponent.create(
                        (short) 4, "panel:recipe-codec", costs, -3L);
        ensureSimpleContainerCodec();

        TameworkBondedReviveEscrowComponent decoded =
                TameworkBondedReviveEscrowComponent.CODEC.decode(
                        TameworkBondedReviveEscrowComponent.CODEC.encode(
                                escrow, new ExtraInfo()), new ExtraInfo());

        assertEquals(costs, decoded.costs());
        assertFalse(decoded.matches("panel:recipe-codec",
                "Ingredient_Life_Essence", 2));
    }

    @Test
    void explicitEmptyPersistedCostsAreInvalidRatherThanLegacyEvidence()
            throws Exception {
        TameworkBondedReviveEscrowComponent escrow =
                TameworkBondedReviveEscrowComponent.create(
                        (short) 2, "panel:empty-costs",
                        "Ingredient_Life_Essence", 2, 1L);
        ensureSimpleContainerCodec();
        BsonDocument encoded = TameworkBondedReviveEscrowComponent.CODEC
                .encode(escrow, new ExtraInfo());
        encoded.put("Costs", new BsonArray());

        TameworkBondedReviveEscrowComponent decoded =
                TameworkBondedReviveEscrowComponent.CODEC.decode(
                        encoded, new ExtraInfo());

        assertTrue(decoded.costs().isEmpty());
        assertEquals(TameworkBondedReviveEscrowComponent.ReservedState.INVALID,
                decoded.reservedState());
        assertFalse(decoded.matches("panel:empty-costs",
                "Ingredient_Life_Essence", 2));
    }

    @Test
    void missingPersistedCostsReconstructsLegacySingletonRecipe() throws Exception {
        TameworkBondedReviveEscrowComponent escrow =
                TameworkBondedReviveEscrowComponent.create(
                        (short) 2, "panel:legacy-costs",
                        "Ingredient_Life_Essence", 2, 1L);
        ensureSimpleContainerCodec();
        BsonDocument encoded = TameworkBondedReviveEscrowComponent.CODEC
                .encode(escrow, new ExtraInfo());
        encoded.remove("Costs");

        TameworkBondedReviveEscrowComponent decoded =
                TameworkBondedReviveEscrowComponent.CODEC.decode(
                        encoded, new ExtraInfo());

        assertEquals(List.of(new BondedCompanionReviveCost(
                "Ingredient_Life_Essence", 2)), decoded.costs());
        assertTrue(decoded.matches("panel:legacy-costs",
                "Ingredient_Life_Essence", 2));
    }

    @Test
    void duplicatePersistedCostsAndOverfilledStacksAreInvalidEvidence()
            throws Exception {
        List<BondedCompanionReviveCost> costs = List.of(
                new BondedCompanionReviveCost("Ingredient_Life_Essence", 2),
                new BondedCompanionReviveCost("Ingredient_Dragon_Essence", 4));
        TameworkBondedReviveEscrowComponent escrow =
                TameworkBondedReviveEscrowComponent.create(
                        (short) 4, "panel:duplicate-costs", costs, 1L);
        ensureSimpleContainerCodec();
        BsonDocument encoded = TameworkBondedReviveEscrowComponent.CODEC
                .encode(escrow, new ExtraInfo());
        encoded.getArray("Costs").add(encoded.getArray("Costs").getFirst());
        TameworkBondedReviveEscrowComponent duplicate =
                TameworkBondedReviveEscrowComponent.CODEC.decode(
                        encoded, new ExtraInfo());
        assertEquals(TameworkBondedReviveEscrowComponent.ReservedState.INVALID,
                duplicate.reservedState());

        escrow.getInventory().setItemStackForSlot((short) 0,
                itemStack("Ingredient_Life_Essence", 3));
        assertEquals(TameworkBondedReviveEscrowComponent.ReservedState.INVALID,
                escrow.reservedState());
        assertFalse(escrow.matches("panel:duplicate-costs", List.of(
                costs.get(1), costs.getFirst())));
    }

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
        escrow.setPhase(TameworkBondedReviveEscrowComponent.Phase.REFUNDING);
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
        assertEquals(TameworkBondedReviveEscrowComponent.Phase.REFUNDING,
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
