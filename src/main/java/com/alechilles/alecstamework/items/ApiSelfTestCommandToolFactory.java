package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkIds;
import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.hypixel.hytale.codec.Codec;
import org.joml.Vector3d;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Builds real command-whistle items used by the in-game API self-test fixtures.
 */
public final class ApiSelfTestCommandToolFactory {
    private final CommandLinkedNpcRecordStore recordStore = new CommandLinkedNpcRecordStore();

    @Nonnull
    public ItemStack createExampleCommandTool(@Nonnull String fixtureSetId,
                                              @Nonnull UUID ownerPlayerUuid,
                                              @Nonnull String worldName,
                                              @Nonnull String toolId,
                                              @Nonnull List<LinkedNpcSpec> linkedNpcs) {
        ItemStack stack = new ItemStack(TameworkIds.ITEM_COMMAND_WHISTLE_EXAMPLE, 1);
        stack = stack.withMetadata(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING, toolId);
        stack = stack.withMetadata(TameworkMetadataKeys.API_SELF_TEST_FIXTURE_SET_ID, Codec.STRING, fixtureSetId);
        stack = stack.withMetadata(TameworkMetadataKeys.API_SELF_TEST_OWNER_UUID, Codec.UUID_STRING, ownerPlayerUuid);
        stack = stack.withMetadata(TameworkMetadataKeys.API_SELF_TEST_WORLD_NAME, Codec.STRING, worldName);

        ArrayList<LinkedNpcRecord> records = new ArrayList<>(linkedNpcs.size());
        for (LinkedNpcSpec spec : linkedNpcs) {
            if (spec == null || spec.npcUuid() == null) {
                continue;
            }
            records.add(new LinkedNpcRecord(
                    spec.npcUuid(),
                    spec.lastKnownPosition(),
                    spec.homePosition(),
                    spec.displayName(),
                    spec.nameKey(),
                    spec.roleId()
            ));
        }
        return recordStore.write(stack, records);
    }

    @Nullable
    public String getFixtureSetId(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.getFromMetadataOrNull(TameworkMetadataKeys.API_SELF_TEST_FIXTURE_SET_ID, Codec.STRING);
    }

    @Nullable
    public String getToolId(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.getFromMetadataOrNull(TameworkMetadataKeys.COMMAND_TOOL_ID, Codec.STRING);
    }

    public boolean isSelfTestTool(@Nullable ItemStack stack,
                                  @Nullable UUID ownerPlayerUuid,
                                  @Nullable String worldName) {
        if (stack == null || stack.isEmpty() || ownerPlayerUuid == null || worldName == null || worldName.isBlank()) {
            return false;
        }
        UUID stackOwnerUuid = stack.getFromMetadataOrNull(TameworkMetadataKeys.API_SELF_TEST_OWNER_UUID, Codec.UUID_STRING);
        String stackWorldName = stack.getFromMetadataOrNull(TameworkMetadataKeys.API_SELF_TEST_WORLD_NAME, Codec.STRING);
        String fixtureSetId = getFixtureSetId(stack);
        return fixtureSetId != null
                && !fixtureSetId.isBlank()
                && ownerPlayerUuid.equals(stackOwnerUuid)
                && worldName.equalsIgnoreCase(stackWorldName);
    }

    @Nonnull
    public List<LinkedNpcSpec> readLinkedNpcSpecs(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return List.of();
        }
        List<LinkedNpcRecord> records = recordStore.read(stack);
        if (records.isEmpty()) {
            return List.of();
        }
        ArrayList<LinkedNpcSpec> out = new ArrayList<>(records.size());
        for (LinkedNpcRecord record : records) {
            if (record == null || record.npcUuid == null) {
                continue;
            }
            out.add(new LinkedNpcSpec(
                    record.npcUuid,
                    record.lastKnownPosition,
                    record.homePosition,
                    record.cachedDisplayName,
                    record.cachedNameKey,
                    record.cachedRoleId
            ));
        }
        return out;
    }

    public record LinkedNpcSpec(@Nonnull UUID npcUuid,
                                @Nullable Vector3d lastKnownPosition,
                                @Nullable Vector3d homePosition,
                                @Nullable String displayName,
                                @Nullable String nameKey,
                                @Nullable String roleId) {
    }
}
