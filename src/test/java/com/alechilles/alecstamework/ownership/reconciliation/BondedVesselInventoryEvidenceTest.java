package com.alechilles.alecstamework.ownership.reconciliation;

import com.alechilles.alecstamework.api.BondedVesselState;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.vessels.runtime.BondedVesselItemFingerprintCodec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.lang.reflect.Field;
import java.util.UUID;
import org.bson.BsonDocument;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BondedVesselInventoryEvidenceTest {
    @Test
    void exactVesselMetadataJoinsTheExistingBoundedInventoryScan() throws Exception {
        UUID binding = UUID.randomUUID();
        ItemStack stack = vessel(binding, 4L, BondedVesselState.ACTIVE);

        CompanionPopulationEvidence evidence = new BondedVesselInventoryEvidence()
                .read(stack, "player-save/" + binding + "/hotbar/slot-2", "player-saves:stored")
                .orElseThrow();
        CompanionPopulationEvidenceSet set =
                new CompanionPopulationEvidenceSet(java.util.List.of(evidence));

        assertEquals(CompanionPopulationEvidence.Kind.CAPTURED_ITEM, evidence.kind());
        assertEquals(1, set.bondedVesselItemObservations(binding).size());
        assertEquals(4L, set.bondedVesselItemObservations(binding).getFirst().generation());
        assertEquals(0, set.evidence().size(),
                "vessel markers must not enter ordinary companion lifecycle repair");
    }

    @Test
    void partialVesselMetadataFailsTheWholeBoundedScanClosed() throws Exception {
        UUID binding = UUID.randomUUID();
        BsonDocument metadata = new BsonDocument()
                .append(TameworkMetadataKeys.VESSEL_BINDING_ID,
                        new BsonString(binding.toString()));

        assertThrows(IllegalStateException.class, () -> new BondedVesselInventoryEvidence()
                .read(item("active-stone", metadata), "inventory/slot-0", "test"));
    }

    private static ItemStack vessel(UUID binding, long generation, BondedVesselState state)
            throws Exception {
        BsonDocument metadata = new BsonDocument()
                .append(TameworkMetadataKeys.VESSEL_BINDING_ID,
                        new BsonString(binding.toString()))
                .append(TameworkMetadataKeys.VESSEL_PROFILE_ID,
                        new BsonString("profile-a"))
                .append(TameworkMetadataKeys.VESSEL_GENERATION,
                        new BsonInt64(generation))
                .append(TameworkMetadataKeys.VESSEL_CONFIG_ID,
                        new BsonString("dragon-stone"))
                .append(TameworkMetadataKeys.VESSEL_STATE,
                        new BsonString(state.name()));
        return item("active-stone", metadata);
    }

    private static ItemStack item(String itemId, BsonDocument metadata) throws Exception {
        ItemStack stack = (ItemStack) unsafe().allocateInstance(ItemStack.class);
        setField(ItemStack.class, stack, "itemId", itemId);
        setField(ItemStack.class, stack, "quantity", 1);
        setField(ItemStack.class, stack, "metadata", metadata);
        return stack;
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
