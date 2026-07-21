package com.alechilles.alecstamework.config;

/**
 * Centralized metadata keys stored on mod items.
 */
public final class TameworkMetadataKeys {
    public static final String CAPTURED = "Tamework.Captured";
    /** Canonical durable companion identity; legacy items may only carry {@link #TARGET_UUID}. */
    public static final String COMPANION_PROFILE_ID = "Tamework.CompanionProfileId";
    public static final String TARGET_UUID = "Tamework.TargetUuid";
    public static final String TARGET_ENTITY_ID = "Tamework.TargetEntityId";
    public static final String CAPTURE_ROLE_ID = "Tamework.CaptureRoleId";
    public static final String CAPTURE_NAME_KEY = "Tamework.CaptureNameKey";
    public static final String CAPTURE_MODEL_ID = "Tamework.CaptureModelId";
    public static final String ATTACHMENTS = "Tamework.Attachments";
    public static final String OWNER_UUID = "Tamework.OwnerUuid";
    public static final String CAPTURE_SOURCE_OWNER_UUID = "Tamework.CaptureSourceOwnerUuid";
    /** Immutable capture outcome; unlike the source owner this remains valid before configs load. */
    public static final String CAPTURE_OWNER_CLEARED = "Tamework.CaptureOwnerCleared";
    public static final String TAMED = "Tamework.Tamed";
    public static final String HAPPINESS_CONFIG_ID = "Tamework.Happiness.ConfigId";
    public static final String HAPPINESS_VALUE = "Tamework.Happiness.Value";
    public static final String HAPPINESS_LAST_UPDATE_MS = "Tamework.Happiness.LastUpdateMs";
    public static final String HEALTH_PERCENT = "Tamework.Health.Percent";
    public static final String NEEDS_CONFIG_ID = "Tamework.Needs.ConfigId";
    public static final String NEEDS_HUNGER = "Tamework.Needs.Hunger";
    public static final String NEEDS_THIRST = "Tamework.Needs.Thirst";
    public static final String NEEDS_APPLIED_HAPPINESS_PENALTY = "Tamework.Needs.AppliedHappinessPenalty";
    public static final String NEEDS_LAST_UPDATE_MS = "Tamework.Needs.LastUpdateMs";
    public static final String NEEDS_LAST_PASSIVE_SWEEP_MS = "Tamework.Needs.LastPassiveSweepMs";
    public static final String BREEDING_CONFIG_ID = "Tamework.Breeding.ConfigId";
    public static final String BREEDING_HAPPINESS = "Tamework.Breeding.Happiness";
    public static final String BREEDING_ENABLED = "Tamework.Breeding.Enabled";
    public static final String BREEDING_COOLDOWN_UNTIL = "Tamework.Breeding.CooldownUntil";
    public static final String BREEDING_LAST_PARTNER_UUID = "Tamework.Breeding.LastPartnerUuid";
    public static final String LEVELING_CONFIG_ID = "Tamework.Leveling.ConfigId";
    public static final String LEVELING_LEVEL = "Tamework.Leveling.Level";
    public static final String LEVELING_TOTAL_XP = "Tamework.Leveling.TotalXp";
    public static final String TALENTS_CONFIG_ID = "Tamework.Talents.ConfigId";
    public static final String TALENTS_SPENT_POINTS = "Tamework.Talents.SpentPoints";
    public static final String TALENTS_PURCHASED_IDS = "Tamework.Talents.PurchasedIds";
    public static final String TRAITS_CONFIG_ID = "Tamework.Traits.ConfigId";
    public static final String TRAITS_ROLL_SEED = "Tamework.Traits.RollSeed";
    public static final String TRAITS_VALUES = "Tamework.Traits.Values";
    public static final String LIFE_STAGE = "Tamework.LifeStage.Stage";
    public static final String LIFE_STAGE_BORN_AT_MS = "Tamework.LifeStage.BornAtMs";
    public static final String LIFE_STAGE_ADOLESCENT_AT_MS = "Tamework.LifeStage.AdolescentAtMs";
    public static final String LIFE_STAGE_ADULT_AT_MS = "Tamework.LifeStage.AdultAtMs";
    public static final String LIFE_STAGE_FULLY_GROWN_AT_MS = "Tamework.LifeStage.FullyGrownAtMs";
    public static final String LIFE_STAGE_BABY_SCALE = "Tamework.LifeStage.BabyScale";
    public static final String LIFE_STAGE_ADOLESCENT_SCALE = "Tamework.LifeStage.AdolescentScale";
    public static final String LIFE_STAGE_ADOLESCENT_SWITCH_SCALE = "Tamework.LifeStage.AdolescentSwitchScale";
    public static final String LIFE_STAGE_ADULT_START_SCALE = "Tamework.LifeStage.AdultStartScale";
    public static final String LIFE_STAGE_ADULT_SWITCH_SCALE = "Tamework.LifeStage.AdultSwitchScale";
    public static final String LIFE_STAGE_ADULT_SCALE = "Tamework.LifeStage.AdultScale";
    public static final String LIFE_STAGE_GROWTH_SCALING_ENABLED = "Tamework.LifeStage.GrowthScalingEnabled";
    public static final String LIFE_STAGE_GENDER = "Tamework.LifeStage.Gender";
    public static final String LIFE_STAGE_ADULT_ROLE_ID = "Tamework.LifeStage.AdultRoleId";
    public static final String LIFE_STAGE_BABY_ROLE_ID = "Tamework.LifeStage.BabyRoleId";
    public static final String LIFE_STAGE_ADOLESCENT_ROLE_ID = "Tamework.LifeStage.AdolescentRoleId";
    public static final String NPC_NAME = "Tamework.NpcName";
    public static final String NPC_NAME_OWNER_UUID = "Tamework.NpcNameOwnerUuid";
    public static final String NPC_NAME_UPDATED_MS = "Tamework.NpcNameUpdatedMs";
    public static final String NPC_NAME_SOURCE = "Tamework.NpcNameSource";
    public static final String CAPTURE_TOOLTIP_DISPLAY_NAME = "Tamework.CaptureTooltipDisplayName";
    public static final String CAPTURE_COOLDOWN_UNTIL = "Tamework.CaptureCooldownUntil";
    public static final String SPAWN_COOLDOWN_UNTIL = "Tamework.SpawnCooldownUntil";
    public static final String NAME_COOLDOWN_UNTIL = "Tamework.NameCooldownUntil";
    public static final String COMMAND_TOOL_ID = "Tamework.Command.ToolId";
    public static final String COMMAND_SELECTED_ID = "Tamework.Command.SelectedId";
    public static final String COMMAND_COOLDOWN_UNTIL = "Tamework.Command.CooldownUntil";
    public static final String COMMAND_LINKED_NPCS = "Tamework.Command.LinkedNpcs";
    public static final String COMMAND_PANEL_SCHEMA_VERSION = "Tamework.Command.PanelSchemaVersion";
    public static final String COMMAND_PANEL_MODE = "Tamework.Command.PanelMode";
    public static final String COMMAND_PANEL_AUTO_LINK = "Tamework.Command.PanelAutoLink";
    public static final String COMMAND_PANEL_RADIUS = "Tamework.Command.PanelRadius";
    public static final String COMMAND_PANEL_SORT = "Tamework.Command.PanelSort";
    public static final String COMMAND_PANEL_FILTER_MODE = "Tamework.Command.PanelFilterMode";
    public static final String COMMAND_PANEL_FILTER_NAME = "Tamework.Command.PanelFilterName";
    public static final String COMMAND_PANEL_FILTER_SPECIES = "Tamework.Command.PanelFilterSpecies";
    public static final String COMMAND_PANEL_FILTER_GROUP = "Tamework.Command.PanelFilterGroup";
    public static final String COMMAND_GROUPS = "Tamework.Command.Groups";
    public static final String COMMAND_HOME_X = "Tamework.Command.HomeX";
    public static final String COMMAND_HOME_Y = "Tamework.Command.HomeY";
    public static final String COMMAND_HOME_Z = "Tamework.Command.HomeZ";
    public static final String API_SELF_TEST_FIXTURE_SET_ID = "Tamework.ApiSelfTest.FixtureSetId";
    public static final String API_SELF_TEST_OWNER_UUID = "Tamework.ApiSelfTest.OwnerUuid";
    public static final String API_SELF_TEST_WORLD_NAME = "Tamework.ApiSelfTest.WorldName";
    /** Durable identity of the canonical bonded-vessel binding projected by this item. */
    public static final String VESSEL_BINDING_ID = "Tamework.Vessel.BindingId";
    /** Durable companion profile referenced by the bonded-vessel binding. */
    public static final String VESSEL_PROFILE_ID = "Tamework.Vessel.ProfileId";
    /** Monotonic binding generation; also fences exact held-slot evidence across restarts. */
    public static final String VESSEL_GENERATION = "Tamework.Vessel.Generation";
    /** Immutable vessel policy/configuration identifier for this generation. */
    public static final String VESSEL_CONFIG_ID = "Tamework.Vessel.ConfigId";
    /** Canonical {@code BondedVesselState} name for the item projection. */
    public static final String VESSEL_STATE = "Tamework.Vessel.State";

    private TameworkMetadataKeys() {
    }
}
