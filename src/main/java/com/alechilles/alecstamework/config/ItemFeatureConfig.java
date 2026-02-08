package com.alechilles.alecstamework.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-item configuration for spawn/capture behaviors.
 */
public final class ItemFeatureConfig {
    private final boolean spawnerEnabled;
    private final boolean whistleEnabled;
    private final boolean captureClearsOwner;
    private final boolean spawnAssignsOwner;
    private final boolean ownerRestricted;
    private final boolean requireTamed;
    private final boolean spawnerAllowUncaptured;
    private final int whistleRadius;
    private final String spawnerRoleId;
    private final List<String> spawnerRoleAllowlist;
    private final List<String> spawnerRoleDenylist;
    private final String spawnerFilledItemId;
    private final String spawnerIconDefault;
    private final String spawnerParticleSystem;
    private final String spawnerSoundEvent;
    private final List<SpawnerIconOverride> spawnerIconOverrides;
    private final Map<String, List<SpawnerIconOverride>> spawnerIconOverridesByRole;

    private ItemFeatureConfig(Builder builder) {
        this.spawnerEnabled = builder.spawnerEnabled;
        this.whistleEnabled = builder.whistleEnabled;
        this.captureClearsOwner = builder.captureClearsOwner;
        this.spawnAssignsOwner = builder.spawnAssignsOwner;
        this.ownerRestricted = builder.ownerRestricted;
        this.requireTamed = builder.requireTamed;
        this.spawnerAllowUncaptured = builder.spawnerAllowUncaptured;
        this.whistleRadius = builder.whistleRadius;
        this.spawnerRoleId = builder.spawnerRoleId;
        this.spawnerRoleAllowlist = builder.spawnerRoleAllowlist;
        this.spawnerRoleDenylist = builder.spawnerRoleDenylist;
        this.spawnerFilledItemId = builder.spawnerFilledItemId;
        this.spawnerIconDefault = builder.spawnerIconDefault;
        this.spawnerParticleSystem = builder.spawnerParticleSystem;
        this.spawnerSoundEvent = builder.spawnerSoundEvent;
        this.spawnerIconOverrides = builder.spawnerIconOverrides;
        this.spawnerIconOverridesByRole = builder.spawnerIconOverridesByRole;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isSpawnerEnabled() {
        return spawnerEnabled;
    }

    public boolean isWhistleEnabled() {
        return whistleEnabled;
    }

    public boolean isCaptureClearsOwner() {
        return captureClearsOwner;
    }

    public boolean isSpawnAssignsOwner() {
        return spawnAssignsOwner;
    }

    public boolean isOwnerRestricted() {
        return ownerRestricted;
    }

    public boolean isRequireTamed() {
        return requireTamed;
    }

    public boolean isSpawnerAllowUncaptured() {
        return spawnerAllowUncaptured;
    }

    public int getWhistleRadius() {
        return whistleRadius;
    }

    public String getSpawnerRoleId() {
        return spawnerRoleId;
    }

    public List<String> getSpawnerRoleAllowlist() {
        return spawnerRoleAllowlist;
    }

    public List<String> getSpawnerRoleDenylist() {
        return spawnerRoleDenylist;
    }

    public String getSpawnerFilledItemId() {
        return spawnerFilledItemId;
    }

    public String getSpawnerIconDefault() {
        return spawnerIconDefault;
    }

    public String getSpawnerParticleSystem() {
        return spawnerParticleSystem;
    }

    public String getSpawnerSoundEvent() {
        return spawnerSoundEvent;
    }

    public List<SpawnerIconOverride> getSpawnerIconOverrides() {
        return spawnerIconOverrides;
    }

    public Map<String, List<SpawnerIconOverride>> getSpawnerIconOverridesByRole() {
        return spawnerIconOverridesByRole;
    }

    public static final class SpawnerIconOverride {
        // Attachment keys must match the NPC attachment map for a given capture.

        private final Map<String, String> attachments;
        private final String icon;

        public SpawnerIconOverride(Map<String, String> attachments, String icon) {
            this.attachments = attachments == null ? Collections.emptyMap() : Collections.unmodifiableMap(attachments);
            this.icon = icon;
        }

        public Map<String, String> getAttachments() {
            return attachments;
        }

        public String getIcon() {
            return icon;
        }
    }

    public static final class Builder {
        private boolean spawnerEnabled;
        private boolean whistleEnabled;
        private boolean captureClearsOwner;
        private boolean spawnAssignsOwner;
        private boolean ownerRestricted;
        private boolean requireTamed = true;
        private boolean spawnerAllowUncaptured;
        private int whistleRadius = 64;
        private String spawnerRoleId;
        private List<String> spawnerRoleAllowlist = Collections.emptyList();
        private List<String> spawnerRoleDenylist = Collections.emptyList();
        private String spawnerFilledItemId;
        private String spawnerIconDefault;
        private String spawnerParticleSystem;
        private String spawnerSoundEvent;
        private List<SpawnerIconOverride> spawnerIconOverrides = Collections.emptyList();
        private Map<String, List<SpawnerIconOverride>> spawnerIconOverridesByRole = Collections.emptyMap();

        private Builder() {
        }

        public Builder spawnerEnabled(boolean spawnerEnabled) {
            this.spawnerEnabled = spawnerEnabled;
            return this;
        }

        public Builder whistleEnabled(boolean whistleEnabled) {
            this.whistleEnabled = whistleEnabled;
            return this;
        }

        public Builder captureClearsOwner(boolean captureClearsOwner) {
            this.captureClearsOwner = captureClearsOwner;
            return this;
        }

        public Builder spawnAssignsOwner(boolean spawnAssignsOwner) {
            this.spawnAssignsOwner = spawnAssignsOwner;
            return this;
        }

        public Builder ownerRestricted(boolean ownerRestricted) {
            this.ownerRestricted = ownerRestricted;
            return this;
        }

        public Builder requireTamed(boolean requireTamed) {
            this.requireTamed = requireTamed;
            return this;
        }

        public Builder spawnerAllowUncaptured(boolean spawnerAllowUncaptured) {
            this.spawnerAllowUncaptured = spawnerAllowUncaptured;
            return this;
        }

        public Builder whistleRadius(int whistleRadius) {
            this.whistleRadius = whistleRadius;
            return this;
        }

        public Builder spawnerRoleId(String spawnerRoleId) {
            this.spawnerRoleId = spawnerRoleId;
            return this;
        }

        public Builder spawnerRoleAllowlist(List<String> spawnerRoleAllowlist) {
            if (spawnerRoleAllowlist == null || spawnerRoleAllowlist.isEmpty()) {
                this.spawnerRoleAllowlist = Collections.emptyList();
            } else {
                this.spawnerRoleAllowlist = List.copyOf(spawnerRoleAllowlist);
            }
            return this;
        }

        public Builder spawnerRoleDenylist(List<String> spawnerRoleDenylist) {
            if (spawnerRoleDenylist == null || spawnerRoleDenylist.isEmpty()) {
                this.spawnerRoleDenylist = Collections.emptyList();
            } else {
                this.spawnerRoleDenylist = List.copyOf(spawnerRoleDenylist);
            }
            return this;
        }

        public Builder spawnerFilledItemId(String spawnerFilledItemId) {
            this.spawnerFilledItemId = spawnerFilledItemId;
            return this;
        }

        public Builder spawnerIconDefault(String spawnerIconDefault) {
            this.spawnerIconDefault = spawnerIconDefault;
            return this;
        }

        public Builder spawnerParticleSystem(String spawnerParticleSystem) {
            this.spawnerParticleSystem = spawnerParticleSystem;
            return this;
        }

        public Builder spawnerSoundEvent(String spawnerSoundEvent) {
            this.spawnerSoundEvent = spawnerSoundEvent;
            return this;
        }

        // Overrides are matched by attachment key/value pairs.
        public Builder spawnerIconOverrides(List<SpawnerIconOverride> spawnerIconOverrides) {
            if (spawnerIconOverrides == null || spawnerIconOverrides.isEmpty()) {
                this.spawnerIconOverrides = Collections.emptyList();
            } else {
                this.spawnerIconOverrides = List.copyOf(spawnerIconOverrides);
            }
            return this;
        }

        public Builder spawnerIconOverridesByRole(Map<String, List<SpawnerIconOverride>> spawnerIconOverridesByRole) {
            if (spawnerIconOverridesByRole == null || spawnerIconOverridesByRole.isEmpty()) {
                this.spawnerIconOverridesByRole = Collections.emptyMap();
                return this;
            }
            Map<String, List<SpawnerIconOverride>> copy = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, List<SpawnerIconOverride>> entry : spawnerIconOverridesByRole.entrySet()) {
                if (entry == null) {
                    continue;
                }
                String roleId = entry.getKey();
                if (roleId == null || roleId.isBlank()) {
                    continue;
                }
                List<SpawnerIconOverride> overrides = entry.getValue();
                if (overrides == null || overrides.isEmpty()) {
                    continue;
                }
                copy.put(roleId, List.copyOf(overrides));
            }
            this.spawnerIconOverridesByRole = copy.isEmpty() ? Collections.emptyMap() : Collections.unmodifiableMap(copy);
            return this;
        }

        public ItemFeatureConfig build() {
            return new ItemFeatureConfig(this);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ItemFeatureConfig other = (ItemFeatureConfig) obj;
        return spawnerEnabled == other.spawnerEnabled
                && whistleEnabled == other.whistleEnabled
                && captureClearsOwner == other.captureClearsOwner
                && spawnAssignsOwner == other.spawnAssignsOwner
                && ownerRestricted == other.ownerRestricted
                && requireTamed == other.requireTamed
                && spawnerAllowUncaptured == other.spawnerAllowUncaptured
                && whistleRadius == other.whistleRadius
                && Objects.equals(spawnerRoleId, other.spawnerRoleId)
                && Objects.equals(spawnerRoleAllowlist, other.spawnerRoleAllowlist)
                && Objects.equals(spawnerRoleDenylist, other.spawnerRoleDenylist)
                && Objects.equals(spawnerFilledItemId, other.spawnerFilledItemId)
                && Objects.equals(spawnerIconDefault, other.spawnerIconDefault)
                && Objects.equals(spawnerParticleSystem, other.spawnerParticleSystem)
                && Objects.equals(spawnerSoundEvent, other.spawnerSoundEvent)
                && Objects.equals(spawnerIconOverrides, other.spawnerIconOverrides)
                && Objects.equals(spawnerIconOverridesByRole, other.spawnerIconOverridesByRole);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                spawnerEnabled,
                whistleEnabled,
                captureClearsOwner,
                spawnAssignsOwner,
                ownerRestricted,
                requireTamed,
                spawnerAllowUncaptured,
                whistleRadius,
                spawnerRoleId,
                spawnerRoleAllowlist,
                spawnerRoleDenylist,
                spawnerFilledItemId,
                spawnerIconDefault,
                spawnerParticleSystem,
                spawnerSoundEvent,
                spawnerIconOverrides,
                spawnerIconOverridesByRole
        );
    }
}





