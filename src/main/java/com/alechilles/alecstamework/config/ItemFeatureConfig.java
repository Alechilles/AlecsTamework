package com.alechilles.alecstamework.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ItemFeatureConfig {
    private final boolean spawnerEnabled;
    private final boolean whistleEnabled;
    private final boolean captureClearsOwner;
    private final boolean spawnAssignsOwner;
    private final boolean ownerRestricted;
    private final boolean spawnerAllowUncaptured;
    private final int whistleRadius;
    private final String spawnerRoleId;
    private final String spawnerFilledItemId;
    private final String spawnerIconDefault;
    private final List<SpawnerIconOverride> spawnerIconOverrides;

    private ItemFeatureConfig(Builder builder) {
        this.spawnerEnabled = builder.spawnerEnabled;
        this.whistleEnabled = builder.whistleEnabled;
        this.captureClearsOwner = builder.captureClearsOwner;
        this.spawnAssignsOwner = builder.spawnAssignsOwner;
        this.ownerRestricted = builder.ownerRestricted;
        this.spawnerAllowUncaptured = builder.spawnerAllowUncaptured;
        this.whistleRadius = builder.whistleRadius;
        this.spawnerRoleId = builder.spawnerRoleId;
        this.spawnerFilledItemId = builder.spawnerFilledItemId;
        this.spawnerIconDefault = builder.spawnerIconDefault;
        this.spawnerIconOverrides = builder.spawnerIconOverrides;
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

    public boolean isSpawnerAllowUncaptured() {
        return spawnerAllowUncaptured;
    }

    public int getWhistleRadius() {
        return whistleRadius;
    }

    public String getSpawnerRoleId() {
        return spawnerRoleId;
    }

    public String getSpawnerFilledItemId() {
        return spawnerFilledItemId;
    }

    public String getSpawnerIconDefault() {
        return spawnerIconDefault;
    }

    public List<SpawnerIconOverride> getSpawnerIconOverrides() {
        return spawnerIconOverrides;
    }

    public static final class SpawnerIconOverride {
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
        private boolean spawnerAllowUncaptured;
        private int whistleRadius = 64;
        private String spawnerRoleId;
        private String spawnerFilledItemId;
        private String spawnerIconDefault;
        private List<SpawnerIconOverride> spawnerIconOverrides = Collections.emptyList();

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

        public Builder spawnerFilledItemId(String spawnerFilledItemId) {
            this.spawnerFilledItemId = spawnerFilledItemId;
            return this;
        }

        public Builder spawnerIconDefault(String spawnerIconDefault) {
            this.spawnerIconDefault = spawnerIconDefault;
            return this;
        }

        public Builder spawnerIconOverrides(List<SpawnerIconOverride> spawnerIconOverrides) {
            if (spawnerIconOverrides == null || spawnerIconOverrides.isEmpty()) {
                this.spawnerIconOverrides = Collections.emptyList();
            } else {
                this.spawnerIconOverrides = List.copyOf(spawnerIconOverrides);
            }
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
                && spawnerAllowUncaptured == other.spawnerAllowUncaptured
                && whistleRadius == other.whistleRadius
                && Objects.equals(spawnerRoleId, other.spawnerRoleId)
                && Objects.equals(spawnerFilledItemId, other.spawnerFilledItemId)
                && Objects.equals(spawnerIconDefault, other.spawnerIconDefault)
                && Objects.equals(spawnerIconOverrides, other.spawnerIconOverrides);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                spawnerEnabled,
                whistleEnabled,
                captureClearsOwner,
                spawnAssignsOwner,
                ownerRestricted,
                spawnerAllowUncaptured,
                whistleRadius,
                spawnerRoleId,
                spawnerFilledItemId,
                spawnerIconDefault,
                spawnerIconOverrides
        );
    }
}
