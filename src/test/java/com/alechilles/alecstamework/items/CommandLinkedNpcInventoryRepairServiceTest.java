package com.alechilles.alecstamework.items;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandLinkedNpcInventoryRepairServiceTest {
    private final CommandLinkedNpcInventoryRepairService service =
            new CommandLinkedNpcInventoryRepairService(null);
    private final FakeStackAdapter stackAdapter = new FakeStackAdapter();

    @Test
    void repairsEveryEnabledToolCopyAcrossCombinedInventoryOrder() {
        UUID staleA = uuid(1);
        UUID staleB = uuid(2);
        UUID current = uuid(3);
        FakeStack disabled = stack("disabled", "tool-disabled", record(staleA, "profile-a", "Disabled"));
        FakeContainer inventory = new FakeContainer(List.of(
                stack("enabled", "tool-b", record(staleA, "profile-a", "Hotbar")),
                disabled,
                stack("enabled", "tool-a", record(staleB, null, "Storage")),
                FakeStack.empty(),
                stack("enabled", "tool-b", record(staleB, null, "Backpack")),
                stack("enabled", "tool-other", record(uuid(9), "profile-z", "Other"))
        ));

        CommandLinkedNpcInventoryRepairService.RepairResult result = service.repair(
                inventory,
                stackAdapter,
                "enabled"::equals,
                request("profile-a", current, Set.of(staleA, staleB))
        );

        assertEquals(6, result.scannedSlots());
        assertEquals(4, result.enabledCommandStacks());
        assertEquals(3, result.matchedStacks());
        assertEquals(3, result.updatedStacks());
        assertEquals(3, result.matchedRecords());
        assertEquals(0, result.deduplicatedRecords());
        assertEquals(0, result.invalidStacks());
        assertEquals(List.of("tool-a", "tool-b"), result.affectedToolIds());
        assertEquals(3, inventory.writeCount);
        assertRepaired(inventory.get(0).records.getFirst(), current);
        assertSame(disabled, inventory.get(1));
        assertRepaired(inventory.get(2).records.getFirst(), current);
        assertRepaired(inventory.get(4).records.getFirst(), current);
        assertEquals("profile-z", inventory.get(5).records.getFirst().profileId);
    }

    @Test
    void deduplicatesTargetProfileWithoutTouchingForeignCollisionOrUnrelatedLegacyData() {
        UUID staleA = uuid(1);
        UUID staleB = uuid(2);
        UUID current = uuid(3);
        LinkedNpcRecord foreignCollision = detailedRecord(current, "profile-b", "Foreign", false, true, "foreign");
        LinkedNpcRecord unresolvedAlias = detailedRecord(staleB, null, "Legacy", false, true, "legacy");
        LinkedNpcRecord resolvedTarget = detailedRecord(staleA, "profile-a", null, true, false, "target");
        LinkedNpcRecord unrelatedLegacy = detailedRecord(uuid(9), null, "Unrelated", false, false, "unrelated");
        FakeContainer inventory = new FakeContainer(List.of(new FakeStack(
                "enabled",
                "tool-a",
                List.of(foreignCollision, unresolvedAlias, resolvedTarget, unrelatedLegacy),
                false,
                true
        )));
        CommandLinkedNpcInventoryRepairService.RepairRequest request = new CommandLinkedNpcInventoryRepairService.RepairRequest(
                "profile-a",
                current,
                Set.of(staleA, staleB),
                new Vector3d(50, 60, 70),
                null,
                null,
                "Fresh Name",
                null,
                null,
                null
        );

        CommandLinkedNpcInventoryRepairService.RepairResult result =
                service.repair(inventory, stackAdapter, ignored -> true, request);

        assertEquals(1, result.updatedStacks());
        assertEquals(2, result.matchedRecords());
        assertEquals(1, result.deduplicatedRecords());
        List<LinkedNpcRecord> repaired = inventory.get(0).records;
        assertEquals(3, repaired.size());
        assertSame(foreignCollision, repaired.get(0));
        assertSame(unrelatedLegacy, repaired.get(2));
        LinkedNpcRecord target = repaired.get(1);
        assertRepaired(target, current);
        assertEquals(new Vector3d(50, 60, 70), target.lastKnownPosition);
        assertEquals("Fresh Name", target.cachedDisplayName);
        assertEquals("name.key", target.cachedNameKey);
        assertEquals("Mob_Test", target.cachedRoleId);
        assertEquals("Follow", target.cachedCommandState);
        assertTrue(target.active);
        assertFalse(target.breedingEnabled);
        assertEquals("target", target.groupId);
        assertEquals("profile-b", repaired.getFirst().profileId);
        assertEquals(current, repaired.getFirst().npcUuid);
    }

    @Test
    void noFreshValuesPreserveCachedAndUserFieldsWithoutRedundantWrite() {
        UUID current = uuid(3);
        LinkedNpcRecord existing = detailedRecord(current, "profile-a", "Current", false, true, "group-a");
        FakeStack original = new FakeStack("enabled", "tool-a", List.of(existing), false, true);
        FakeContainer inventory = new FakeContainer(List.of(original));

        CommandLinkedNpcInventoryRepairService.RepairResult result = service.repair(
                inventory,
                stackAdapter,
                ignored -> true,
                request("profile-a", current, Set.of(current))
        );

        assertEquals(1, result.matchedStacks());
        assertEquals(0, result.updatedStacks());
        assertTrue(result.affectedToolIds().isEmpty());
        assertEquals(0, inventory.writeCount);
        assertSame(original, inventory.get(0));
        assertSame(existing, inventory.get(0).records.getFirst());
    }

    @Test
    void invalidStackFailsClosedAndRequestDefensivelyCopiesEvidence() {
        UUID stale = uuid(1);
        UUID current = uuid(2);
        HashSet<UUID> aliases = new HashSet<>(Set.of(stale));
        Vector3d position = new Vector3d(1, 2, 3);
        CommandLinkedNpcInventoryRepairService.RepairRequest request =
                new CommandLinkedNpcInventoryRepairService.RepairRequest(
                        "profile-a", current, aliases, position, null, null, null, null, null, null);
        aliases.add(uuid(99));
        position.set(9, 9, 9);
        Vector3d returnedPosition = request.position();
        returnedPosition.set(8, 8, 8);
        FakeContainer inventory = new FakeContainer(List.of(
                new FakeStack("enabled", "tool-a", List.of(), false, false)
        ));

        CommandLinkedNpcInventoryRepairService.RepairResult result =
                service.repair(inventory, stackAdapter, ignored -> true, request);

        assertEquals(1, result.invalidStacks());
        assertEquals(0, result.updatedStacks());
        assertEquals(Set.of(stale, current), request.aliases());
        assertEquals(new Vector3d(1, 2, 3), request.position());
        assertEquals(0, inventory.writeCount);
    }

    private CommandLinkedNpcInventoryRepairService.RepairRequest request(String profileId,
                                                                         UUID current,
                                                                         Set<UUID> aliases) {
        return new CommandLinkedNpcInventoryRepairService.RepairRequest(
                profileId, current, aliases, null, null, null, null, null, null, null);
    }

    private void assertRepaired(LinkedNpcRecord record, UUID current) {
        assertEquals("profile-a", record.profileId);
        assertEquals(current, record.npcUuid);
    }

    private LinkedNpcRecord record(UUID npcUuid, String profileId, String displayName) {
        return new LinkedNpcRecord(
                npcUuid, profileId, null, null, null, displayName, null, null, null, true, false, null);
    }

    private LinkedNpcRecord detailedRecord(UUID npcUuid,
                                           String profileId,
                                           String displayName,
                                           boolean active,
                                           boolean breedingEnabled,
                                           String groupId) {
        return new LinkedNpcRecord(
                npcUuid,
                profileId,
                new Vector3d(1, 2, 3),
                "world-a",
                new Vector3d(4, 5, 6),
                displayName,
                "name.key",
                "Mob_Test",
                "Follow",
                active,
                breedingEnabled,
                groupId
        );
    }

    private FakeStack stack(String itemId, String toolId, LinkedNpcRecord record) {
        return new FakeStack(itemId, toolId, List.of(record), false, true);
    }

    private static UUID uuid(long value) {
        return new UUID(0L, value);
    }

    private static final class FakeContainer
            implements CommandLinkedNpcInventoryRepairService.ContainerAdapter<FakeStack> {
        private final ArrayList<FakeStack> slots;
        private int writeCount;

        private FakeContainer(List<FakeStack> slots) {
            this.slots = new ArrayList<>(slots);
        }

        @Override
        public int capacity() {
            return slots.size();
        }

        @Override
        public FakeStack get(int slot) {
            return slots.get(slot);
        }

        @Override
        public void set(int slot, FakeStack stack) {
            slots.set(slot, stack);
            writeCount++;
        }
    }

    private static final class FakeStackAdapter
            implements CommandLinkedNpcInventoryRepairService.StackAdapter<FakeStack> {
        @Override
        public boolean isEmpty(FakeStack stack) {
            return stack.empty;
        }

        @Override
        public String itemId(FakeStack stack) {
            return stack.itemId;
        }

        @Override
        public String toolId(FakeStack stack) {
            return stack.toolId;
        }

        @Override
        public CommandLinkedNpcInventoryRepairService.StackRecords readRecords(FakeStack stack) {
            return stack.valid
                    ? CommandLinkedNpcInventoryRepairService.StackRecords.valid(stack.records)
                    : CommandLinkedNpcInventoryRepairService.StackRecords.invalid();
        }

        @Override
        public FakeStack writeRecords(FakeStack stack, List<LinkedNpcRecord> records) {
            return new FakeStack(stack.itemId, stack.toolId, records, stack.empty, stack.valid);
        }
    }

    private static final class FakeStack {
        private final String itemId;
        private final String toolId;
        private final List<LinkedNpcRecord> records;
        private final boolean empty;
        private final boolean valid;

        private FakeStack(String itemId,
                          String toolId,
                          List<LinkedNpcRecord> records,
                          boolean empty,
                          boolean valid) {
            this.itemId = itemId;
            this.toolId = toolId;
            this.records = List.copyOf(records);
            this.empty = empty;
            this.valid = valid;
        }

        private static FakeStack empty() {
            return new FakeStack(null, null, List.of(), true, true);
        }
    }
}
