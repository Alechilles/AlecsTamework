package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.alechilles.alecstamework.items.coop.CapturedItemCoopTarget;
import com.hypixel.hytale.builtin.adventure.farming.config.FarmingCoopAsset;
import com.hypixel.hytale.builtin.adventure.farming.states.CoopBlock;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * Resolves only an exact loaded block with an enabled {@link TwCoopConfig}.
 *
 * <p>The resolver neither creates a vanilla block entity nor reads or mutates vanilla resident
 * occupancy. It freezes configured physical slots for the canonical replacement projection,
 * which remains the sole managed-coop occupancy authority.</p>
 */
public final class HytaleManagedCoopItemTargetResolver {

    /** Returns null when the exact loaded block is not a configured managed coop. */
    @Nullable
    public CapturedItemCoopTarget resolve(
            @Nonnull World world,
            @Nonnull Vector3i targetBlock
    ) {
        if (world == null || targetBlock == null
                || world.getChunkStore() == null) {
            return null;
        }
        WorldChunk chunk = world.getChunkIfInMemory(
                ChunkUtil.indexChunkFromBlock(
                        targetBlock.x, targetBlock.z
                )
        );
        if (chunk == null) {
            return null;
        }
        BlockType blockType = chunk.getBlockType(
                targetBlock.x, targetBlock.y, targetBlock.z
        );
        String blockTypeId = normalizeBlockType(
                blockType == null ? null : blockType.getId()
        );
        Ref<ChunkStore> blockRef = chunk.getBlockComponentEntity(
                targetBlock.x, targetBlock.y, targetBlock.z
        );
        if (blockRef == null || !blockRef.isValid()) {
            return null;
        }
        ComponentType<ChunkStore, CoopBlock> coopType =
                CoopBlock.getComponentType();
        if (coopType == null) {
            return null;
        }
        CoopBlock coop = world.getChunkStore().getStore()
                .getComponent(blockRef, coopType);
        FarmingCoopAsset asset = coop == null
                ? null : coop.getCoopAsset();
        String coopAssetId = normalize(asset == null ? null : asset.getId());
        TwCoopConfig config = resolveConfig(coopAssetId, blockTypeId);
        if (config == null || !config.isEnabled()) {
            return null;
        }
        String coopId = first(
                normalize(config.getCoopId()),
                coopAssetId,
                blockTypeId
        );
        String worldKey = normalize(world.getName());
        if (coopId == null || worldKey == null) {
            return null;
        }
        TwCoopConfig.LifecycleRules lifecycle =
                config.getLifecycleRules();
        TwCoopConfig.CapturePolicySettings policy =
                config.getCapturePolicy();
        return new CapturedItemCoopTarget(
                worldKey,
                coopId,
                targetBlock.x,
                targetBlock.y,
                targetBlock.z,
                lifecycle.getMaxResidents(),
                normalizedRoles(lifecycle.getAcceptedRoleIds()),
                policy.isRequireTamed(),
                policy.isRequireOwner(),
                policy.isOwnerRestricted()
        );
    }

    @Nullable
    private TwCoopConfig resolveConfig(
            @Nullable String coopAssetId,
            @Nullable String blockTypeId
    ) {
        TwCoopConfig config = coopAssetId == null
                ? null : TwCoopConfig.resolveForCoop(coopAssetId);
        if (config == null && coopAssetId != null) {
            config = TwCoopConfig.resolveForBlockType(coopAssetId);
        }
        if (config == null && blockTypeId != null) {
            config = TwCoopConfig.resolveForBlockType(blockTypeId);
        }
        if (config == null && blockTypeId != null) {
            config = TwCoopConfig.resolveForCoop(blockTypeId);
        }
        return config;
    }

    private Set<String> normalizedRoles(@Nullable String[] roles) {
        if (roles == null || roles.length == 0) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String role : roles) {
            String value = normalize(role);
            if (value != null) {
                normalized.add(value);
            }
        }
        return Set.copyOf(normalized);
    }

    @Nullable
    private String normalizeBlockType(@Nullable String raw) {
        String value = normalize(raw);
        if (value == null) {
            return null;
        }
        while (value.startsWith("*")) {
            value = value.substring(1);
        }
        int state = value.indexOf("_state_definitions_");
        return state > 0 ? value.substring(0, state) : value;
    }

    @Nullable
    private String normalize(@Nullable String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim().toLowerCase(Locale.ROOT);
    }

    @Nullable
    private String first(@Nullable String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }
}
