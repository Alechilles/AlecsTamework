package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandHotswapHudGroupStatusResolverTest {
    private static final List<CommandGroupService.GroupRecord> GROUPS = List.of(
            new CommandGroupService.GroupRecord("blue", "Blue Squad", "#112233", 0),
            new CommandGroupService.GroupRecord("red", "Red Squad", "#AA5500", 1)
    );

    private final CommandHotswapHudGroupStatusResolver resolver =
            new CommandHotswapHudGroupStatusResolver(null, null, null);

    @Test
    void namedGroupUsesItsNameAndConfiguredColor() {
        var status = resolver.resolve(records(true, false, false), GROUPS);

        assertEquals("Blue Squad", status.label());
        assertEquals("#112233", status.colorHex());
    }

    @Test
    void customAndNoActiveUseDedicatedLabelsAndColors() {
        var custom = resolver.resolve(records(true, true, false), GROUPS);
        var none = resolver.resolve(records(false, false, false), GROUPS);

        assertEquals("Custom Selection", custom.label());
        assertEquals("#c9a653", custom.colorHex());
        assertEquals("No Active Companions", none.label());
        assertEquals("#6e7c8b", none.colorHex());
    }

    @Test
    void unchangedItemStackSkipsRepeatedMetadataReads() {
        UUID npcUuid = UUID.randomUUID();
        ItemStack active = metadataStack(npcUuid + "|ac=1");
        AtomicInteger linkedReads = new AtomicInteger();
        AtomicInteger groupReads = new AtomicInteger();
        CommandHotswapHudGroupStatusResolver cachingResolver =
                CommandHotswapHudGroupStatusResolver.forReaders(
                        ignored -> {
                            linkedReads.incrementAndGet();
                            return records(true, false, false);
                        },
                        ignored -> {
                            groupReads.incrementAndGet();
                            return GROUPS;
                        }
                );

        var first = cachingResolver.resolve(npcUuid, active);
        var second = cachingResolver.resolve(npcUuid, active);
        ItemStack inactive = active.withMetadata(
                TameworkMetadataKeys.COMMAND_LINKED_NPCS,
                Codec.STRING,
                npcUuid + "|ac=0"
        );
        cachingResolver.resolve(npcUuid, inactive);

        assertEquals("Blue Squad", first.label());
        assertEquals(first, second);
        assertEquals(2, linkedReads.get(), "Only a changed item stack may decode linked records again.");
        assertEquals(2, groupReads.get(), "Only a changed item stack may decode groups again.");
    }

    private List<LinkedNpcRecord> records(boolean blueActive,
                                           boolean redActive,
                                           boolean ungroupedActive) {
        return List.of(
                record("blue", blueActive),
                record("red", redActive),
                record(null, ungroupedActive)
        );
    }

    private LinkedNpcRecord record(String groupId, boolean active) {
        UUID uuid = UUID.randomUUID();
        return new LinkedNpcRecord(
                uuid, null, null, uuid.toString(), null, "test_role", null,
                active, false, groupId
        );
    }

    private ItemStack metadataStack(String linkedRecords) {
        return new MetadataItemStack("Tamework:CommandFlute", null).withMetadata(
                TameworkMetadataKeys.COMMAND_LINKED_NPCS,
                Codec.STRING,
                linkedRecords
        );
    }

    /** Asset-store-free stack that keeps real BSON metadata semantics. */
    private static final class MetadataItemStack extends ItemStack {
        private MetadataItemStack(String itemId, BsonDocument metadata) {
            super();
            this.itemId = itemId;
            this.quantity = 1;
            this.metadata = metadata;
        }

        @Override
        public <T> ItemStack withMetadata(String key, Codec<T> codec, T value) {
            BsonDocument next = metadata == null ? new BsonDocument() : metadata.clone();
            if (value == null) {
                next.remove(key);
            } else {
                next.put(key, codec.encode(value));
            }
            return new MetadataItemStack(itemId, next.isEmpty() ? null : next);
        }
    }
}
