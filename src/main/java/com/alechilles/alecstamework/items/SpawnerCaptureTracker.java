package com.alechilles.alecstamework.items;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SpawnerCaptureTracker {
    private static final long EXPIRY_MS = 5000L;
    private final Map<UUID, PendingCapture> pendingCaptures = new ConcurrentHashMap<>();

    public void record(UUID playerUuid,
                       int hotbarSlot,
                       String itemId,
                       Integer targetEntityId,
                       UUID targetUuid,
                       String attachmentsJson,
                       Integer roleIndex,
                       String npcNameKey,
                       String iconPath,
                       String fullItemIcon,
                       java.util.UUID ownerUuid) {
        if (playerUuid == null || itemId == null) {
            return;
        }
        PendingCapture existing = pendingCaptures.get(playerUuid);
        Integer resolvedEntityId = targetEntityId != null ? targetEntityId
                : existing != null ? existing.targetEntityId : null;
        UUID resolvedUuid = targetUuid != null ? targetUuid
                : existing != null ? existing.targetUuid : null;
        String resolvedAttachments = attachmentsJson != null ? attachmentsJson
                : existing != null ? existing.attachmentsJson : null;
        Integer resolvedRoleIndex = roleIndex != null ? roleIndex
                : existing != null ? existing.roleIndex : null;
        String resolvedNpcNameKey = npcNameKey != null ? npcNameKey
                : existing != null ? existing.npcNameKey : null;
        String resolvedIconPath = iconPath != null ? iconPath
                : existing != null ? existing.iconPath : null;
        String resolvedFullItemIcon = fullItemIcon != null ? fullItemIcon
                : existing != null ? existing.fullItemIcon : null;
        UUID resolvedOwnerUuid = ownerUuid != null ? ownerUuid
                : existing != null ? existing.ownerUuid : null;
        pendingCaptures.put(
                playerUuid,
                new PendingCapture(
                        playerUuid,
                        hotbarSlot,
                        itemId,
                        resolvedEntityId,
                        resolvedUuid,
                        resolvedAttachments,
                        resolvedRoleIndex,
                        resolvedNpcNameKey,
                        resolvedIconPath,
                        resolvedFullItemIcon,
                        resolvedOwnerUuid
                )
        );
    }

    public PendingCapture get(UUID playerUuid) {
        return playerUuid == null ? null : pendingCaptures.get(playerUuid);
    }

    public PendingCapture consume(UUID playerUuid) {
        return playerUuid == null ? null : pendingCaptures.remove(playerUuid);
    }

    public void clear(UUID playerUuid) {
        if (playerUuid != null) {
            pendingCaptures.remove(playerUuid);
        }
    }

    public boolean isExpired(PendingCapture capture) {
        return capture == null || (System.currentTimeMillis() - capture.createdAtMs) > EXPIRY_MS;
    }

    public static final class PendingCapture {
        private final UUID playerUuid;
        private final int hotbarSlot;
        private final String itemId;
        private final Integer targetEntityId;
        private final UUID targetUuid;
        private final String attachmentsJson;
        private final Integer roleIndex;
        private final String npcNameKey;
        private final String iconPath;
        private final String fullItemIcon;
        private final UUID ownerUuid;
        private final long createdAtMs;

        private PendingCapture(UUID playerUuid,
                               int hotbarSlot,
                               String itemId,
                               Integer targetEntityId,
                               UUID targetUuid,
                               String attachmentsJson,
                               Integer roleIndex,
                               String npcNameKey,
                               String iconPath,
                               String fullItemIcon,
                               UUID ownerUuid) {
            this.playerUuid = playerUuid;
            this.hotbarSlot = hotbarSlot;
            this.itemId = itemId;
            this.targetEntityId = targetEntityId;
            this.targetUuid = targetUuid;
            this.attachmentsJson = attachmentsJson;
            this.roleIndex = roleIndex;
            this.npcNameKey = npcNameKey;
            this.iconPath = iconPath;
            this.fullItemIcon = fullItemIcon;
            this.ownerUuid = ownerUuid;
            this.createdAtMs = System.currentTimeMillis();
        }

        public UUID getPlayerUuid() {
            return playerUuid;
        }

        public int getHotbarSlot() {
            return hotbarSlot;
        }

        public String getItemId() {
            return itemId;
        }

        public Integer getTargetEntityId() {
            return targetEntityId;
        }

        public UUID getTargetUuid() {
            return targetUuid;
        }

        public String getAttachmentsJson() {
            return attachmentsJson;
        }

        public Integer getRoleIndex() {
            return roleIndex;
        }

        public String getNpcNameKey() {
            return npcNameKey;
        }

        public String getIconPath() {
            return iconPath;
        }

        public String getFullItemIcon() {
            return fullItemIcon;
        }

        public UUID getOwnerUuid() {
            return ownerUuid;
        }
    }
}
