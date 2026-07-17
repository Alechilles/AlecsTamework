package com.alechilles.alecstamework.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-item configuration for spawn/capture behaviors.
 */
public final class ItemFeatureConfig {
    public enum RoleListMode {
        ANY,
        ALLOW,
        DENY;

        public static RoleListMode fromString(String value) {
            if (value == null) {
                return ANY;
            }
            switch (value.trim().toLowerCase()) {
                case "allow":
                    return ALLOW;
                case "deny":
                    return DENY;
                case "any":
                default:
                    return ANY;
            }
        }
    }

    public enum SpawnerTooltipMode {
        ADDITIVE,
        REPLACE;

        public static SpawnerTooltipMode fromString(String value) {
            if (value == null) {
                return ADDITIVE;
            }
            switch (value.trim().toLowerCase()) {
                case "replace":
                    return REPLACE;
                case "additive":
                default:
                    return ADDITIVE;
            }
        }
    }

    private final boolean spawnerEnabled;
    private final boolean whistleEnabled;
    private final boolean captureClearsOwner;
    private final boolean captureRequireTamed;
    private final boolean captureTamesTarget;
    private final boolean captureOwnerRestricted;
    private final boolean spawnAssignsOwner;
    private final boolean spawnOwnerRestricted;
    private final int whistleRadius;
    private final List<String> spawnerRoleAllowlist;
    private final List<String> spawnerRoleDenylist;
    private final RoleListMode spawnerRoleListMode;
    private final Boolean captureRequireOwnerOverride;
    private final Boolean spawnRequireOwnerOverride;
    private final String captureParticleSystem;
    private final String spawnParticleSystem;
    private final String captureSoundEvent;
    private final String captureRequiredEffectId;
    private final String captureChannelAuraEffectId;
    private final Double captureMaxHealthPercent;
    private final Map<String, String> captureTamedRoleOverrides;
    private final String spawnSoundEvent;
    private final int captureCooldownMs;
    private final int spawnCooldownMs;
    private final double captureMaxDistance;
    private final double spawnMaxDistance;
    private final String spawnerFilledItemId;
    private final String spawnerIconDefault;
    private final List<SpawnerIconOverride> spawnerIconOverrides;
    private final Map<String, List<SpawnerIconOverride>> spawnerIconOverridesByRole;
    private final List<SpawnerIconOverrideGroup> spawnerIconOverrideGroups;
    private final SpawnerTooltipMode spawnerTooltipMode;

    private ItemFeatureConfig(Builder builder) {
        this.spawnerEnabled = builder.spawnerEnabled;
        this.whistleEnabled = builder.whistleEnabled;
        this.captureClearsOwner = builder.captureClearsOwner;
        this.captureRequireTamed = builder.captureRequireTamed;
        this.captureTamesTarget = builder.captureTamesTarget;
        this.captureOwnerRestricted = builder.captureOwnerRestricted;
        this.spawnAssignsOwner = builder.spawnAssignsOwner;
        this.spawnOwnerRestricted = builder.spawnOwnerRestricted;
        this.whistleRadius = builder.whistleRadius;
        this.spawnerRoleAllowlist = builder.spawnerRoleAllowlist;
        this.spawnerRoleDenylist = builder.spawnerRoleDenylist;
        this.spawnerRoleListMode = builder.spawnerRoleListMode;
        this.captureRequireOwnerOverride = builder.captureRequireOwnerOverride;
        this.spawnRequireOwnerOverride = builder.spawnRequireOwnerOverride;
        this.captureParticleSystem = builder.captureParticleSystem;
        this.spawnParticleSystem = builder.spawnParticleSystem;
        this.captureSoundEvent = builder.captureSoundEvent;
        this.captureRequiredEffectId = builder.captureRequiredEffectId;
        this.captureChannelAuraEffectId = builder.captureChannelAuraEffectId;
        this.captureMaxHealthPercent = builder.captureMaxHealthPercent;
        this.captureTamedRoleOverrides = builder.captureTamedRoleOverrides;
        this.spawnSoundEvent = builder.spawnSoundEvent;
        this.captureCooldownMs = builder.captureCooldownMs;
        this.spawnCooldownMs = builder.spawnCooldownMs;
        this.captureMaxDistance = builder.captureMaxDistance;
        this.spawnMaxDistance = builder.spawnMaxDistance;
        this.spawnerFilledItemId = builder.spawnerFilledItemId;
        this.spawnerIconDefault = builder.spawnerIconDefault;
        this.spawnerIconOverrides = builder.spawnerIconOverrides;
        this.spawnerIconOverridesByRole = builder.spawnerIconOverridesByRole;
        this.spawnerIconOverrideGroups = builder.spawnerIconOverrideGroups;
        this.spawnerTooltipMode = builder.spawnerTooltipMode;
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

    public boolean isCaptureRequireTamed() {
        return captureRequireTamed;
    }

    public boolean isCaptureTamesTarget() {
        return captureTamesTarget;
    }

    public boolean isCaptureOwnerRestricted() {
        return captureOwnerRestricted;
    }

    public boolean isSpawnAssignsOwner() {
        return spawnAssignsOwner;
    }

    public boolean isSpawnOwnerRestricted() {
        return spawnOwnerRestricted;
    }




    public int getWhistleRadius() {
        return whistleRadius;
    }

    public List<String> getSpawnerRoleAllowlist() {
        return spawnerRoleAllowlist;
    }

    public List<String> getSpawnerRoleDenylist() {
        return spawnerRoleDenylist;
    }

    public RoleListMode getSpawnerRoleListMode() {
        return spawnerRoleListMode;
    }



    public Boolean getCaptureRequireOwnerOverride() {
        return captureRequireOwnerOverride;
    }

    public Boolean getSpawnRequireOwnerOverride() {
        return spawnRequireOwnerOverride;
    }

    public String getCaptureParticleSystem() {
        return captureParticleSystem;
    }

    public String getSpawnParticleSystem() {
        return spawnParticleSystem;
    }

    public String getCaptureSoundEvent() {
        return captureSoundEvent;
    }

    public String getCaptureRequiredEffectId() {
        return captureRequiredEffectId;
    }

    public String getCaptureChannelAuraEffectId() {
        return captureChannelAuraEffectId;
    }

    public Double getCaptureMaxHealthPercent() {
        return captureMaxHealthPercent;
    }

    public Map<String, String> getCaptureTamedRoleOverrides() {
        return captureTamedRoleOverrides;
    }

    public String resolveCaptureTamedRole(String sourceRoleId) {
        if (sourceRoleId == null || sourceRoleId.isBlank()) {
            return null;
        }
        String mapped = captureTamedRoleOverrides.get(sourceRoleId);
        return mapped == null || mapped.isBlank() ? null : mapped;
    }

    public String getSpawnSoundEvent() {
        return spawnSoundEvent;
    }

    public int getCaptureCooldownMs() {
        return captureCooldownMs;
    }

    public int getSpawnCooldownMs() {
        return spawnCooldownMs;
    }

    public double getCaptureMaxDistance() {
        return captureMaxDistance;
    }

    public double getSpawnMaxDistance() {
        return spawnMaxDistance;
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

    public Map<String, List<SpawnerIconOverride>> getSpawnerIconOverridesByRole() {
        return spawnerIconOverridesByRole;
    }

    public List<SpawnerIconOverrideGroup> getSpawnerIconOverrideGroups() {
        return spawnerIconOverrideGroups;
    }

    public SpawnerTooltipMode getSpawnerTooltipMode() {
        return spawnerTooltipMode;
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

    public static final class SpawnerIconOverrideGroup {
        private final List<String> roles;
        private final List<SpawnerIconOverride> overrides;
        private final String iconDefault;

        public SpawnerIconOverrideGroup(List<String> roles, List<SpawnerIconOverride> overrides) {
            this(roles, overrides, null);
        }

        public SpawnerIconOverrideGroup(List<String> roles, List<SpawnerIconOverride> overrides, String iconDefault) {
            if (roles == null || roles.isEmpty()) {
                this.roles = Collections.emptyList();
            } else {
                List<String> roleCopy = new java.util.ArrayList<>(roles.size());
                for (String role : roles) {
                    if (role != null && !role.isBlank()) {
                        roleCopy.add(role);
                    }
                }
                this.roles = roleCopy.isEmpty() ? Collections.emptyList() : List.copyOf(roleCopy);
            }
            this.overrides = overrides == null || overrides.isEmpty()
                    ? Collections.emptyList()
                    : List.copyOf(overrides);
            this.iconDefault = iconDefault;
        }

        public List<String> getRoles() {
            return roles;
        }

        public List<SpawnerIconOverride> getOverrides() {
            return overrides;
        }

        public String getIconDefault() {
            return iconDefault;
        }
    }

    public static final class Builder {
        private boolean spawnerEnabled;
        private boolean whistleEnabled;
        private boolean captureClearsOwner = true;
        private boolean captureRequireTamed = true;
        private boolean captureTamesTarget;
        private boolean captureOwnerRestricted = true;
        private boolean spawnAssignsOwner = true;
        private boolean spawnOwnerRestricted = true;
        private int whistleRadius = 64;
        private List<String> spawnerRoleAllowlist = Collections.emptyList();
        private List<String> spawnerRoleDenylist = Collections.emptyList();
        private RoleListMode spawnerRoleListMode = RoleListMode.ANY;
        private Boolean captureRequireOwnerOverride;
        private Boolean spawnRequireOwnerOverride;
        private String captureParticleSystem;
        private String spawnParticleSystem;
        private String captureSoundEvent;
        private String captureRequiredEffectId;
        private String captureChannelAuraEffectId;
        private Double captureMaxHealthPercent;
        private Map<String, String> captureTamedRoleOverrides = Collections.emptyMap();
        private String spawnSoundEvent;
        private int captureCooldownMs;
        private int spawnCooldownMs;
        private double captureMaxDistance;
        private double spawnMaxDistance;
        private String spawnerFilledItemId;
        private String spawnerIconDefault;
        private List<SpawnerIconOverride> spawnerIconOverrides = Collections.emptyList();
        private Map<String, List<SpawnerIconOverride>> spawnerIconOverridesByRole = Collections.emptyMap();
        private List<SpawnerIconOverrideGroup> spawnerIconOverrideGroups = Collections.emptyList();
        private SpawnerTooltipMode spawnerTooltipMode = SpawnerTooltipMode.ADDITIVE;

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

        public Builder captureRequireTamed(boolean captureRequireTamed) {
            this.captureRequireTamed = captureRequireTamed;
            return this;
        }

        public Builder captureTamesTarget(boolean captureTamesTarget) {
            this.captureTamesTarget = captureTamesTarget;
            return this;
        }

        public Builder captureOwnerRestricted(boolean captureOwnerRestricted) {
            this.captureOwnerRestricted = captureOwnerRestricted;
            return this;
        }

        public Builder spawnAssignsOwner(boolean spawnAssignsOwner) {
            this.spawnAssignsOwner = spawnAssignsOwner;
            return this;
        }

        public Builder spawnOwnerRestricted(boolean spawnOwnerRestricted) {
            this.spawnOwnerRestricted = spawnOwnerRestricted;
            return this;
        }




        public Builder whistleRadius(int whistleRadius) {
            this.whistleRadius = whistleRadius;
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

        public Builder spawnerRoleListMode(RoleListMode spawnerRoleListMode) {
            this.spawnerRoleListMode = spawnerRoleListMode != null ? spawnerRoleListMode : RoleListMode.ANY;
            return this;
        }



        public Builder captureRequireOwnerOverride(Boolean captureRequireOwnerOverride) {
            this.captureRequireOwnerOverride = captureRequireOwnerOverride;
            return this;
        }

        public Builder spawnRequireOwnerOverride(Boolean spawnRequireOwnerOverride) {
            this.spawnRequireOwnerOverride = spawnRequireOwnerOverride;
            return this;
        }

        public Builder captureParticleSystem(String captureParticleSystem) {
            this.captureParticleSystem = captureParticleSystem;
            return this;
        }

        public Builder spawnParticleSystem(String spawnParticleSystem) {
            this.spawnParticleSystem = spawnParticleSystem;
            return this;
        }

        public Builder captureSoundEvent(String captureSoundEvent) {
            this.captureSoundEvent = captureSoundEvent;
            return this;
        }

        public Builder captureRequiredEffectId(String captureRequiredEffectId) {
            this.captureRequiredEffectId = captureRequiredEffectId;
            return this;
        }

        public Builder captureChannelAuraEffectId(String captureChannelAuraEffectId) {
            this.captureChannelAuraEffectId = captureChannelAuraEffectId;
            return this;
        }

        public Builder captureMaxHealthPercent(Double captureMaxHealthPercent) {
            this.captureMaxHealthPercent = captureMaxHealthPercent;
            return this;
        }

        public Builder captureTamedRoleOverrides(Map<String, String> captureTamedRoleOverrides) {
            if (captureTamedRoleOverrides == null || captureTamedRoleOverrides.isEmpty()) {
                this.captureTamedRoleOverrides = Collections.emptyMap();
            } else {
                this.captureTamedRoleOverrides = Collections.unmodifiableMap(
                        new java.util.LinkedHashMap<>(captureTamedRoleOverrides)
                );
            }
            return this;
        }

        public Builder spawnSoundEvent(String spawnSoundEvent) {
            this.spawnSoundEvent = spawnSoundEvent;
            return this;
        }

        public Builder captureCooldownMs(int captureCooldownMs) {
            this.captureCooldownMs = captureCooldownMs;
            return this;
        }

        public Builder spawnCooldownMs(int spawnCooldownMs) {
            this.spawnCooldownMs = spawnCooldownMs;
            return this;
        }

        public Builder captureMaxDistance(double captureMaxDistance) {
            this.captureMaxDistance = captureMaxDistance;
            return this;
        }

        public Builder spawnMaxDistance(double spawnMaxDistance) {
            this.spawnMaxDistance = spawnMaxDistance;
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

        public Builder spawnerIconOverrideGroups(List<SpawnerIconOverrideGroup> spawnerIconOverrideGroups) {
            if (spawnerIconOverrideGroups == null || spawnerIconOverrideGroups.isEmpty()) {
                this.spawnerIconOverrideGroups = Collections.emptyList();
                return this;
            }
            List<SpawnerIconOverrideGroup> copy = new java.util.ArrayList<>(spawnerIconOverrideGroups.size());
            for (SpawnerIconOverrideGroup group : spawnerIconOverrideGroups) {
                if (group == null || group.getRoles().isEmpty()) {
                    continue;
                }
                String groupDefault = group.getIconDefault();
                if (group.getOverrides().isEmpty() && (groupDefault == null || groupDefault.isBlank())) {
                    continue;
                }
                copy.add(group);
            }
            this.spawnerIconOverrideGroups = copy.isEmpty() ? Collections.emptyList() : List.copyOf(copy);
            return this;
        }

        public Builder spawnerTooltipMode(SpawnerTooltipMode spawnerTooltipMode) {
            this.spawnerTooltipMode = spawnerTooltipMode != null ? spawnerTooltipMode : SpawnerTooltipMode.ADDITIVE;
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
                && captureRequireTamed == other.captureRequireTamed
                && captureTamesTarget == other.captureTamesTarget
                && captureOwnerRestricted == other.captureOwnerRestricted
                && spawnAssignsOwner == other.spawnAssignsOwner
                && spawnOwnerRestricted == other.spawnOwnerRestricted
                && whistleRadius == other.whistleRadius
                && spawnerRoleListMode == other.spawnerRoleListMode
                && Objects.equals(captureRequireOwnerOverride, other.captureRequireOwnerOverride)
                && Objects.equals(spawnRequireOwnerOverride, other.spawnRequireOwnerOverride)
                && Objects.equals(captureParticleSystem, other.captureParticleSystem)
                && Objects.equals(spawnParticleSystem, other.spawnParticleSystem)
                && Objects.equals(captureSoundEvent, other.captureSoundEvent)
                && Objects.equals(captureRequiredEffectId, other.captureRequiredEffectId)
                && Objects.equals(captureChannelAuraEffectId, other.captureChannelAuraEffectId)
                && Objects.equals(captureMaxHealthPercent, other.captureMaxHealthPercent)
                && Objects.equals(captureTamedRoleOverrides, other.captureTamedRoleOverrides)
                && Objects.equals(spawnSoundEvent, other.spawnSoundEvent)
                && captureCooldownMs == other.captureCooldownMs
                && spawnCooldownMs == other.spawnCooldownMs
                && Double.compare(captureMaxDistance, other.captureMaxDistance) == 0
                && Double.compare(spawnMaxDistance, other.spawnMaxDistance) == 0
                && Objects.equals(spawnerRoleAllowlist, other.spawnerRoleAllowlist)
                && Objects.equals(spawnerRoleDenylist, other.spawnerRoleDenylist)
                && Objects.equals(spawnerFilledItemId, other.spawnerFilledItemId)
                && Objects.equals(spawnerIconDefault, other.spawnerIconDefault)
                && Objects.equals(spawnerIconOverrides, other.spawnerIconOverrides)
                && Objects.equals(spawnerIconOverridesByRole, other.spawnerIconOverridesByRole)
                && Objects.equals(spawnerIconOverrideGroups, other.spawnerIconOverrideGroups)
                && spawnerTooltipMode == other.spawnerTooltipMode;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                spawnerEnabled,
                whistleEnabled,
                captureClearsOwner,
                captureRequireTamed,
                captureTamesTarget,
                captureOwnerRestricted,
                spawnAssignsOwner,
                spawnOwnerRestricted,
                whistleRadius,
                spawnerRoleListMode,
                captureRequireOwnerOverride,
                spawnRequireOwnerOverride,
                captureParticleSystem,
                spawnParticleSystem,
                captureSoundEvent,
                captureRequiredEffectId,
                captureChannelAuraEffectId,
                captureMaxHealthPercent,
                captureTamedRoleOverrides,
                spawnSoundEvent,
                captureCooldownMs,
                spawnCooldownMs,
                captureMaxDistance,
                spawnMaxDistance,
                spawnerRoleAllowlist,
                spawnerRoleDenylist,
                spawnerFilledItemId,
                spawnerIconDefault,
                spawnerIconOverrides,
                spawnerIconOverridesByRole,
                spawnerIconOverrideGroups,
                spawnerTooltipMode
        );
    }
}



