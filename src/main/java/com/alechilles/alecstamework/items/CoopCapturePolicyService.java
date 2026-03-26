package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.config.TameworkMetadataKeys;
import com.alechilles.alecstamework.config.assets.TwCoopConfig;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Evaluates coop intake policy checks against captured-item metadata.
 */
final class CoopCapturePolicyService {

    @Nonnull
    Decision evaluate(@Nullable Player player,
                      @Nullable ItemStack itemStack,
                      @Nonnull TwCoopConfig.CapturePolicySettings policy) {
        UUID ownerUuid = readOwnerUuid(itemStack);
        Boolean tamed = readTamed(itemStack);

        if (policy.isRequireTamed() && !Boolean.TRUE.equals(tamed)) {
            return Decision.denied(DenyReason.REQUIRE_TAMED, ownerUuid);
        }
        if (policy.isRequireOwner() && ownerUuid == null) {
            return Decision.denied(DenyReason.REQUIRE_OWNER, null);
        }
        if (policy.isOwnerRestricted()) {
            UUID playerUuid = player != null ? player.getUuid() : null;
            if (ownerUuid == null || playerUuid == null || !ownerUuid.equals(playerUuid)) {
                return Decision.denied(DenyReason.OWNER_RESTRICTED, ownerUuid);
            }
        }
        return Decision.allowed(ownerUuid);
    }

    @Nullable
    private UUID readOwnerUuid(@Nullable ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }
        UUID ownerUuid = itemStack.getFromMetadataOrNull(TameworkMetadataKeys.OWNER_UUID, Codec.UUID_STRING);
        if (ownerUuid != null) {
            return ownerUuid;
        }
        return itemStack.getFromMetadataOrNull(TameworkMetadataKeys.CAPTURE_SOURCE_OWNER_UUID, Codec.UUID_STRING);
    }

    @Nullable
    private Boolean readTamed(@Nullable ItemStack itemStack) {
        if (itemStack == null) {
            return null;
        }
        return itemStack.getFromMetadataOrNull(TameworkMetadataKeys.TAMED, Codec.BOOLEAN);
    }

    enum DenyReason {
        REQUIRE_TAMED,
        REQUIRE_OWNER,
        OWNER_RESTRICTED
    }

    static final class Decision {
        private final boolean allowed;
        private final DenyReason denyReason;
        private final UUID ownerUuid;

        private Decision(boolean allowed, @Nullable DenyReason denyReason, @Nullable UUID ownerUuid) {
            this.allowed = allowed;
            this.denyReason = denyReason;
            this.ownerUuid = ownerUuid;
        }

        static Decision allowed(@Nullable UUID ownerUuid) {
            return new Decision(true, null, ownerUuid);
        }

        static Decision denied(@Nonnull DenyReason denyReason, @Nullable UUID ownerUuid) {
            return new Decision(false, denyReason, ownerUuid);
        }

        boolean isAllowed() {
            return allowed;
        }

        @Nullable
        DenyReason getDenyReason() {
            return denyReason;
        }

        @Nullable
        UUID getOwnerUuid() {
            return ownerUuid;
        }
    }
}
