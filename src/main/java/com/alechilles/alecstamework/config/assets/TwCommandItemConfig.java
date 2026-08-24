package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorRequirement;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.assetstore.AssetStore;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.codec.lookup.StringCodecMapCodec;
import com.hypixel.hytale.common.util.ArrayUtil;
import java.util.ArrayList;
import org.joml.Vector3d;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Asset-backed configuration for command items.
 * Stored under Server/Tamework/Items/Commands.
 */
public class TwCommandItemConfig implements JsonAssetWithMap<String, DefaultAssetMap<String, TwCommandItemConfig>>,
        TwParentFallbackAsset<TwCommandItemConfig> {
    public enum MembershipMode {
        LinkedOnly,
        OwnerScope,
        MasterTarget,
        LinkedOrMasterTarget;

        public static MembershipMode fromString(String value) {
            if (value == null || value.isBlank()) {
                return LinkedOnly;
            }
            for (MembershipMode mode : values()) {
                if (mode.name().equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
            return LinkedOnly;
        }
    }

    /** Selects the canonical persistence authority for command membership. */
    public enum RosterStorage {
        ItemMetadata,
        OwnerCommandFamily,
        BondedCompanions;

        public static RosterStorage fromString(@Nullable String value) {
            if (value == null || value.isBlank()) {
                return ItemMetadata;
            }
            for (RosterStorage storage : values()) {
                if (storage.name().equalsIgnoreCase(value.trim())) {
                    return storage;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown RosterStorage: " + value.trim()
            );
        }
    }

    public enum RoleFilterMode {
        AllowAll,
        Allowlist,
        Denylist
    }

    public enum FailurePolicy {
        Continue,
        AbortCommandForNpc,
        AbortAll;

        public static FailurePolicy fromString(String value) {
            if (value == null || value.isBlank()) {
                return Continue;
            }
            for (FailurePolicy policy : values()) {
                if (policy.name().equalsIgnoreCase(value.trim())) {
                    return policy;
                }
            }
            return Continue;
        }
    }

    public enum TargetSource {
        CrosshairTarget,
        LastAttackTarget,
        OwnerPlayer,
        StoredTarget;

        public static TargetSource fromString(String value) {
            if (value == null || value.isBlank()) {
                return CrosshairTarget;
            }
            for (TargetSource source : values()) {
                if (source.name().equalsIgnoreCase(value.trim())) {
                    return source;
                }
            }
            return CrosshairTarget;
        }
    }

    public enum MoveSource {
        RaycastHit,
        OwnerPosition,
        StoredHome;

        public static MoveSource fromString(String value) {
            if (value == null || value.isBlank()) {
                return RaycastHit;
            }
            for (MoveSource source : values()) {
                if (source.name().equalsIgnoreCase(value.trim())) {
                    return source;
                }
            }
            return RaycastHit;
        }
    }

    public enum StoreSource {
        RaycastHit,
        OwnerPosition;

        public static StoreSource fromString(String value) {
            if (value == null || value.isBlank()) {
                return RaycastHit;
            }
            for (StoreSource source : values()) {
                if (source.name().equalsIgnoreCase(value.trim())) {
                    return source;
                }
            }
            return RaycastHit;
        }
    }

    static final CommandEntry[] EMPTY_COMMAND_LIST = new CommandEntry[0];
    static final CommandStep[] EMPTY_STEPS = new CommandStep[0];
    private static final CommandUiContributorRequirement[] EMPTY_UI_CONTRIBUTORS =
            new CommandUiContributorRequirement[0];

    public static final StringCodecMapCodec<
            AllowedRoles,
            BuilderCodec<? extends AllowedRoles>> ALLOWED_ROLES_CODEC =
            TwCommandItemCodecs.ALLOWED_ROLES_CODEC;
    public static final BuilderCodec<ModeMapping> MODE_MAPPING_CODEC =
            TwCommandItemCodecs.MODE_MAPPING_CODEC;
    public static final StringCodecMapCodec<
            CommandStep,
            BuilderCodec<? extends CommandStep>> COMMAND_STEP_CODEC =
            TwCommandItemCodecs.COMMAND_STEP_CODEC;
    public static final ArrayCodec<CommandStep> COMMAND_STEP_ARRAY_CODEC =
            TwCommandItemCodecs.COMMAND_STEP_ARRAY_CODEC;
    public static final BuilderCodec<CommandFeedback>
            COMMAND_FEEDBACK_CODEC =
            TwCommandItemCodecs.COMMAND_FEEDBACK_CODEC;
    public static final BuilderCodec<CommandEntry> COMMAND_ENTRY_CODEC =
            TwCommandItemCodecs.COMMAND_ENTRY_CODEC;
    public static final ArrayCodec<CommandEntry> COMMAND_ENTRY_ARRAY_CODEC =
            TwCommandItemCodecs.COMMAND_ENTRY_ARRAY_CODEC;
    public static final BuilderCodec<UiContributorSettings> UI_CONTRIBUTOR_CODEC =
            BuilderCodec.builder(UiContributorSettings.class, UiContributorSettings::new)
                    .<String>append(
                            new KeyedCodec<>("Id", Codec.STRING),
                            (settings, value) -> settings.id = value,
                            settings -> settings.id
                    )
                    .documentation("Namespaced contributor ID selected for this command UI.")
                    .add()
                    .<Boolean>append(
                            new KeyedCodec<>("Required", Codec.BOOLEAN),
                            (settings, value) -> settings.required = value != null && value,
                            settings -> settings.required
                    )
                    .documentation("When true, contributor failure falls back to the standard Tamework UI.")
                    .add()
                    .build();
    public static final ArrayCodec<UiContributorSettings> UI_CONTRIBUTOR_ARRAY_CODEC =
            new ArrayCodec<>(UI_CONTRIBUTOR_CODEC, UiContributorSettings[]::new);

    public static final AssetBuilderCodec<String, TwCommandItemConfig> CODEC =
        AssetBuilderCodec.builder(
                TwCommandItemConfig.class,
                TwCommandItemConfig::new,
                Codec.STRING,
                (asset, id) -> asset.id = id,
                asset -> asset.id,
                (asset, data) -> asset.data = data,
                asset -> asset.data
        )
        .documentation("Command item configuration for Alec's Tamework!")
        .<Boolean>append(
            new KeyedCodec<>("Enabled", Codec.BOOLEAN),
            (asset, value) -> asset.enabled = value == null || value,
            asset -> asset.enabled
        )
        .documentation("Turns this section on or off.")
        .add()
        .<String[]>append(
            new KeyedCodec<>("ItemIds", Codec.STRING_ARRAY),
            (asset, value) -> asset.itemIds = value == null ? ArrayUtil.EMPTY_STRING_ARRAY : value,
            asset -> asset.itemIds
        )
        .documentation("Command item IDs this config applies to. Inheritance: omitted value inherits from parent; "
                + "explicit array replaces parent value (no merge).")
        .add()
        .<Double>append(
            new KeyedCodec<>("Radius", Codec.DOUBLE),
            (asset, value) -> asset.radius = value == null ? -1.0 : value,
            asset -> asset.radius
        )
        .documentation("Search radius in blocks used by this system.")
        .add()
        .<String>append(
            new KeyedCodec<>("MembershipMode", Codec.STRING),
            (asset, value) -> asset.membershipMode = MembershipMode.fromString(value),
            asset -> asset.membershipMode.name()
        )
        .documentation("Controls how command membership is interpreted when selecting targets.")
        .add()
        .<String>append(
            new KeyedCodec<>("CommandFamilyId", Codec.STRING),
            (asset, value) -> asset.commandFamilyId = normalizeOptional(value),
            asset -> asset.commandFamilyId
        )
        .documentation("Stable owner-scoped command family shared by equivalent access items. "
                + "Required when RosterStorage is OwnerCommandFamily. Inheritance: omitted value inherits.")
        .add()
        .<String>append(
            new KeyedCodec<>("UiRendererId", Codec.STRING),
            (asset, value) -> asset.uiRendererId = normalizeUiRendererId(value),
            asset -> asset.uiRendererId
        )
        .documentation("Optional namespaced command-menu renderer. Blank selects the standard Tamework menu. "
                + "Renderer IDs are normalized to lowercase. Inheritance: an omitted child value inherits from its "
                + "parent; an explicit child value replaces it. If no effective renderer is present, the standard "
                + "Tamework menu is selected.")
        .add()
        .<UiContributorSettings[]>append(
            new KeyedCodec<>("UiContributors", UI_CONTRIBUTOR_ARRAY_CODEC),
            (asset, value) -> asset.uiContributors = normalizeUiContributors(value),
            asset -> toUiContributorSettings(asset.uiContributors)
        )
        .documentation("Ordered contributor requirements. An explicit list replaces the inherited list; an explicit "
                + "empty list clears inherited contributors.")
        .add()
        .<RosterStorage>append(
            new KeyedCodec<>(
                    "RosterStorage",
                    TwCommandItemCodecs.ROSTER_STORAGE_CODEC
            ),
            (asset, value) -> asset.rosterStorage = value == null
                    ? RosterStorage.ItemMetadata
                    : value,
            asset -> asset.rosterStorage
        )
        .documentation("Canonical membership authority: ItemMetadata (legacy/default), OwnerCommandFamily, "
                + "or the separate BondedCompanions authority. Inheritance: omitted value inherits.")
        .add()
        .<String>append(
            new KeyedCodec<>("BondedRosterId", Codec.STRING),
            (asset, value) -> asset.bondedRosterId = normalizeOptional(value),
            asset -> asset.bondedRosterId
        )
        .documentation("Required roster policy ID when RosterStorage is BondedCompanions. "
                + "Inheritance: omitted value inherits.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("ProjectRosterToItemMetadata", Codec.BOOLEAN),
            (asset, value) -> asset.projectRosterToItemMetadata = value,
            asset -> asset.projectRosterToItemMetadata
        )
        .documentation("Projects an owner-family roster to item metadata as a disposable cache. "
                + "Item metadata never becomes the authority. Omission defaults true for legacy/owner storage "
                + "and false for bonded storage; an inherited or explicit setting is invalid for bonded storage.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("LinkEnabled", Codec.BOOLEAN),
            (asset, value) -> asset.linkEnabled = value == null || value,
            asset -> asset.linkEnabled
        )
        .documentation("If true, this command can create and use persistent links.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("LinkUseTogglesMembership", Codec.BOOLEAN),
            (asset, value) -> asset.linkUseTogglesMembership = value == null || value,
            asset -> asset.linkUseTogglesMembership
        )
        .documentation("If true, using link mode toggles membership for selected targets.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireTamed", Codec.BOOLEAN),
            (asset, value) -> asset.requireTamed = value == null || value,
            asset -> asset.requireTamed
        )
        .documentation("Requires the target NPC to be tamed.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireOwner", Codec.BOOLEAN),
            (asset, value) -> asset.requireOwner = value,
            asset -> asset.requireOwner
        )
        .documentation("Optional owner requirement override. If omitted, global OwnershipRequirements.LinkingRequiresOwner applies.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("MaxTargets", Codec.INTEGER),
            (asset, value) -> asset.maxTargets = value == null ? 25 : Math.max(1, value),
            asset -> asset.maxTargets
        )
        .documentation("Maximum number of targets this command can affect at once.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("MaxActive", Codec.INTEGER),
            (asset, value) -> asset.maxActive = value == null ? 0 : Math.max(0, value),
            asset -> asset.maxActive
        )
        .documentation("Maximum number of active linked NPCs allowed for this command item.")
        .add()
        .<Integer>append(
            new KeyedCodec<>("CooldownSeconds", Codec.INTEGER),
            (asset, value) -> asset.cooldownSeconds = value == null ? 2 : Math.max(0, value),
            asset -> asset.cooldownSeconds
        )
        .documentation("Cooldown duration in seconds before this can be used again.")
        .add()
        .<Boolean>append(
            new KeyedCodec<>("RequireLineOfSight", Codec.BOOLEAN),
            (asset, value) -> asset.requireLineOfSight = value != null && value,
            asset -> asset.requireLineOfSight
        )
        .documentation("Requires line of sight to target before command can run.")
        .add()
        .<AllowedRoles>append(
            new KeyedCodec<>("AllowedRoles", ALLOWED_ROLES_CODEC),
            (asset, value) -> asset.allowedRoles = value == null ? new AllowAllRoles() : value,
            asset -> asset.allowedRoles
        )
        .documentation("Role restrictions for command targets. Inheritance: omitted section inherits from parent; when "
                + "present, only explicitly defined nested fields override parent.")
        .add()
        .<CommandEntry[]>append(
            new KeyedCodec<>("CommandList", COMMAND_ENTRY_ARRAY_CODEC),
            (asset, value) -> asset.commandList = value == null ? EMPTY_COMMAND_LIST : value,
            asset -> asset.commandList
        )
        .documentation("Available command entries. Inheritance: omitted value inherits from parent; explicit array "
                + "replaces parent value (no merge).")
        .add()
        .build();

    private static AssetStore<String, TwCommandItemConfig, DefaultAssetMap<String, TwCommandItemConfig>> ASSET_STORE;
    private static final Object INHERITANCE_CACHE_LOCK = new Object();
    private static volatile boolean INHERITANCE_CACHE_DIRTY = true;

    private AssetExtraInfo.Data data;
    private String id;
    private boolean enabled = true;
    private String[] itemIds = ArrayUtil.EMPTY_STRING_ARRAY;
    private double radius = -1.0;
    private MembershipMode membershipMode = MembershipMode.LinkedOnly;
    private String commandFamilyId;
    private String uiRendererId;
    private CommandUiContributorRequirement[] uiContributors = EMPTY_UI_CONTRIBUTORS;
    private RosterStorage rosterStorage = RosterStorage.ItemMetadata;
    private String bondedRosterId;
    private Boolean projectRosterToItemMetadata;
    private boolean linkEnabled = true;
    private boolean linkUseTogglesMembership = true;
    private boolean requireTamed = true;
    private Boolean requireOwner;
    private int maxTargets = 25;
    private int maxActive;
    private int cooldownSeconds = 2;
    private boolean requireLineOfSight;
    private AllowedRoles allowedRoles = new AllowAllRoles();
    private CommandEntry[] commandList = EMPTY_COMMAND_LIST;

    public static AssetStore<String, TwCommandItemConfig, DefaultAssetMap<String, TwCommandItemConfig>> getAssetStore() {
        if (ASSET_STORE == null) {
            ASSET_STORE = AssetRegistry.getAssetStore(TwCommandItemConfig.class);
        }
        return ASSET_STORE;
    }

    @Nullable
    public static DefaultAssetMap<String, TwCommandItemConfig> getAssetMap() {
        AssetStore<String, TwCommandItemConfig, DefaultAssetMap<String, TwCommandItemConfig>> store = getAssetStore();
        if (store == null) {
            return null;
        }
        DefaultAssetMap<String, TwCommandItemConfig> assetMap =
                (DefaultAssetMap<String, TwCommandItemConfig>) store.getAssetMap();
        ensureInheritanceFallbackApplied(assetMap);
        return assetMap;
    }

    public static void clearInheritanceFallbackCache() {
        INHERITANCE_CACHE_DIRTY = true;
    }

    private static void ensureInheritanceFallbackApplied(
            @Nullable DefaultAssetMap<String, TwCommandItemConfig> assetMap) {
        if (!INHERITANCE_CACHE_DIRTY || assetMap == null || assetMap.getAssetMap() == null) {
            return;
        }
        synchronized (INHERITANCE_CACHE_LOCK) {
            if (!INHERITANCE_CACHE_DIRTY || assetMap.getAssetMap() == null) {
                return;
            }
            TwAssetInheritanceFallback.repairAll(assetMap);
            INHERITANCE_CACHE_DIRTY = false;
        }
    }

    protected TwCommandItemConfig() {
    }

    public String getId() {
        return id;
    }

    @Override
    @Nullable
    public String getParentIdForFallback() {
        if (data == null || data.getParentKey() == null) {
            return null;
        }
        String parentId = data.getParentKey().toString();
        return parentId == null || parentId.isBlank() ? null : parentId;
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwCommandItemConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys) {
        inheritMissingTopLevelFrom(parent, explicitTopLevelKeys, null);
    }

    @Override
    public void inheritMissingTopLevelFrom(@Nonnull TwCommandItemConfig parent,
                                           @Nonnull Set<String> explicitTopLevelKeys,
                                           @Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel) {
        if (!explicitTopLevelKeys.contains("Enabled")) enabled = parent.enabled;
        if (!explicitTopLevelKeys.contains("ItemIds")) itemIds = parent.itemIds;
        if (!explicitTopLevelKeys.contains("Radius")) radius = parent.radius;
        if (!explicitTopLevelKeys.contains("MembershipMode")) membershipMode = parent.membershipMode;
        if (!explicitTopLevelKeys.contains("CommandFamilyId")) commandFamilyId = parent.commandFamilyId;
        if (!explicitTopLevelKeys.contains("UiRendererId")) uiRendererId = parent.uiRendererId;
        if (!explicitTopLevelKeys.contains("UiContributors")) {
            uiContributors = parent.uiContributors.clone();
        }
        if (!explicitTopLevelKeys.contains("RosterStorage")) rosterStorage = parent.rosterStorage;
        if (!explicitTopLevelKeys.contains("BondedRosterId")) bondedRosterId = parent.bondedRosterId;
        if (!explicitTopLevelKeys.contains("ProjectRosterToItemMetadata")) {
            projectRosterToItemMetadata = parent.projectRosterToItemMetadata;
        }
        if (!explicitTopLevelKeys.contains("LinkEnabled")) linkEnabled = parent.linkEnabled;
        if (!explicitTopLevelKeys.contains("LinkUseTogglesMembership")) {
            linkUseTogglesMembership = parent.linkUseTogglesMembership;
        }
        if (!explicitTopLevelKeys.contains("RequireTamed")) requireTamed = parent.requireTamed;
        if (!explicitTopLevelKeys.contains("RequireOwner")) requireOwner = parent.requireOwner;
        if (!explicitTopLevelKeys.contains("MaxTargets")) maxTargets = parent.maxTargets;
        if (!explicitTopLevelKeys.contains("MaxActive")) maxActive = parent.maxActive;
        if (!explicitTopLevelKeys.contains("CooldownSeconds")) cooldownSeconds = parent.cooldownSeconds;
        if (!explicitTopLevelKeys.contains("RequireLineOfSight")) requireLineOfSight = parent.requireLineOfSight;
        if (!explicitTopLevelKeys.contains("AllowedRoles")) {
            allowedRoles = parent.allowedRoles;
        } else {
            inheritAllowedRolesSection(parent, nestedKeysForTopLevel(explicitNestedKeysByTopLevel, "AllowedRoles"));
        }
        if (!explicitTopLevelKeys.contains("CommandList")) commandList = parent.commandList;
    }

    private void inheritAllowedRolesSection(@Nonnull TwCommandItemConfig parent, @Nullable Set<String> nestedExplicitKeys) {
        if (nestedExplicitKeys == null) {
            return;
        }
        if (!nestedExplicitKeys.contains("Mode")) {
            allowedRoles = parent.allowedRoles;
            return;
        }
        if (allowedRoles == null || parent.allowedRoles == null) {
            return;
        }
        if (allowedRoles instanceof AllowlistRoles childAllowlist && parent.allowedRoles instanceof AllowlistRoles parentAllowlist) {
            if (!nestedExplicitKeys.contains("Allowlist")) {
                childAllowlist.allowlist = parentAllowlist.allowlist;
            }
        } else if (allowedRoles instanceof DenylistRoles childDenylist
                && parent.allowedRoles instanceof DenylistRoles parentDenylist) {
            if (!nestedExplicitKeys.contains("Denylist")) {
                childDenylist.denylist = parentDenylist.denylist;
            }
        }
    }

    @Nullable
    private static Set<String> nestedKeysForTopLevel(@Nullable Map<String, Set<String>> explicitNestedKeysByTopLevel,
                                                     @Nonnull String topLevelKey) {
        if (explicitNestedKeysByTopLevel == null) {
            return null;
        }
        return explicitNestedKeysByTopLevel.get(topLevelKey);
    }

    @Nullable
    private static String normalizeOptional(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @Nullable
    private static String normalizeUiRendererId(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        CommandUiRendererId id = CommandUiRendererId.of(value);
        if (id.reserved()) {
            throw new IllegalArgumentException("The tamework: renderer namespace is reserved.");
        }
        return id.value();
    }

    private static CommandUiContributorRequirement[] normalizeUiContributors(
            @Nullable UiContributorSettings[] value
    ) {
        if (value == null || value.length == 0) {
            return EMPTY_UI_CONTRIBUTORS;
        }
        List<CommandUiContributorRequirement> requirements = new ArrayList<>(value.length);
        Set<CommandUiContributorId> seen = new HashSet<>();
        for (UiContributorSettings settings : value) {
            if (settings == null) {
                throw new IllegalArgumentException("UiContributors cannot contain null entries.");
            }
            CommandUiContributorId id = CommandUiContributorId.of(settings.id);
            if (id.reserved()) {
                throw new IllegalArgumentException("The tamework: contributor namespace is reserved.");
            }
            if (!seen.add(id)) {
                throw new IllegalArgumentException("UiContributors contains duplicate ID: " + id.value());
            }
            requirements.add(new CommandUiContributorRequirement(id, settings.required));
        }
        return requirements.toArray(CommandUiContributorRequirement[]::new);
    }

    private static UiContributorSettings[] toUiContributorSettings(
            @Nullable CommandUiContributorRequirement[] requirements
    ) {
        if (requirements == null || requirements.length == 0) {
            return new UiContributorSettings[0];
        }
        UiContributorSettings[] settings = new UiContributorSettings[requirements.length];
        for (int index = 0; index < requirements.length; index++) {
            CommandUiContributorRequirement requirement = requirements[index];
            UiContributorSettings setting = new UiContributorSettings();
            setting.id = requirement.id().value();
            setting.required = requirement.required();
            settings[index] = setting;
        }
        return settings;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String[] getItemIds() {
        return itemIds;
    }

    public double getRadius() {
        return radius;
    }

    public MembershipMode getMembershipMode() {
        return membershipMode;
    }

    @Nullable
    public String getCommandFamilyId() {
        return commandFamilyId;
    }

    /** Returns the normalized custom menu renderer ID, or null for the standard menu. */
    @Nullable
    public String getUiRendererId() {
        return uiRendererId;
    }

    /** Returns the ordered immutable contributor requirements for this command UI. */
    @Nonnull
    public List<CommandUiContributorRequirement> getUiContributors() {
        return List.of(uiContributors);
    }

    /**
     * Transitional source compatibility for runtime code that still reads the
     * removed provider field. New code must use {@link #getUiRendererId()}.
     */
    @Deprecated
    @Nullable
    public String getUiProviderId() {
        return null;
    }

    public RosterStorage getRosterStorage() {
        return rosterStorage;
    }

    @Nullable
    public String getBondedRosterId() {
        return bondedRosterId;
    }

    public boolean isProjectRosterToItemMetadata() {
        if (projectRosterToItemMetadata != null) {
            return projectRosterToItemMetadata;
        }
        return !usesBondedCompanionRoster();
    }

    public boolean hasProjectRosterToItemMetadataSetting() {
        return projectRosterToItemMetadata != null;
    }

    public boolean usesOwnerCommandFamilyRoster() {
        return rosterStorage == RosterStorage.OwnerCommandFamily;
    }

    public boolean usesBondedCompanionRoster() {
        return rosterStorage == RosterStorage.BondedCompanions;
    }

    public boolean isLinkEnabled() {
        return linkEnabled;
    }

    public boolean isLinkUseTogglesMembership() {
        return linkUseTogglesMembership;
    }

    public boolean isRequireTamed() {
        return requireTamed;
    }

    public boolean isRequireOwner() {
        return requireOwner == null || requireOwner;
    }

    @Nullable
    public Boolean getRequireOwnerOverride() {
        return requireOwner;
    }

    public boolean resolveRequireOwner(boolean fallbackValue) {
        return requireOwner == null ? fallbackValue : requireOwner;
    }

    public int getMaxTargets() {
        return maxTargets;
    }

    public int getMaxActive() {
        return maxActive;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public boolean isRequireLineOfSight() {
        return requireLineOfSight;
    }

    public AllowedRoles getAllowedRoles() {
        return allowedRoles;
    }

    public CommandEntry[] getCommandList() {
        return commandList;
    }

    /**
     * Builds the built-in Return Home command used by panel actions when an item config does not
     * define an explicit return-home command entry.
     */
    public static CommandEntry createBuiltInReturnHomeCommand() {
        return TwCommandSelection.createBuiltInReturnHome();
    }

    @Nullable
    public CommandEntry findCommandById(@Nullable String commandId) {
        return TwCommandSelection.findById(commandList, commandId);
    }

    @Nullable
    public CommandEntry findDefaultCommand() {
        return TwCommandSelection.findDefault(commandList);
    }

    @Nullable
    public CommandEntry findNextCommand(@Nullable String currentCommandId) {
        return TwCommandSelection.findNext(commandList, currentCommandId);
    }

    /** Base role filter model for command targets. */
    public abstract static class AllowedRoles {
        public abstract RoleFilterMode getMode();

        public String[] getAllowlist() {
            return ArrayUtil.EMPTY_STRING_ARRAY;
        }

        public String[] getDenylist() {
            return ArrayUtil.EMPTY_STRING_ARRAY;
        }
    }

    /** Allow command dispatch to all NPC roles. */
    public static final class AllowAllRoles extends AllowedRoles {
        @Override
        public RoleFilterMode getMode() {
            return RoleFilterMode.AllowAll;
        }
    }

    /** Allow command dispatch only for explicitly listed NPC roles. */
    public static final class AllowlistRoles extends AllowedRoles {
        String[] allowlist = ArrayUtil.EMPTY_STRING_ARRAY;

        @Override
        public RoleFilterMode getMode() {
            return RoleFilterMode.Allowlist;
        }

        @Override
        public String[] getAllowlist() {
            return allowlist;
        }
    }

    /** Deny command dispatch for explicitly listed NPC roles. */
    public static final class DenylistRoles extends AllowedRoles {
        String[] denylist = ArrayUtil.EMPTY_STRING_ARRAY;

        @Override
        public RoleFilterMode getMode() {
            return RoleFilterMode.Denylist;
        }

        @Override
        public String[] getDenylist() {
            return denylist;
        }
    }

    public static final class ModeMapping {
        String state;
        String subState;
        String message;

        public String getState() {
            return state;
        }

        public String getSubState() {
            return subState;
        }

        public String getMessage() {
            return message;
        }
    }

    /** Codec-only representation of one contributor requirement. */
    public static final class UiContributorSettings {
        private String id;
        private boolean required;

        public String getId() {
            return id;
        }

        public boolean isRequired() {
            return required;
        }
    }

    public static final class CommandEntry {
        String id;
        String displayName;
        String icon;
        boolean showInRadial = true;
        boolean defaultCommand;
        CommandFeedback feedback;
        ModeMapping modeMapping;
        CommandStep[] steps = EMPTY_STEPS;

        public String getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getIcon() {
            return icon;
        }

        /** Whether this command is rendered in the bounded command radial. */
        public boolean isShowInRadial() {
            return showInRadial;
        }

        public boolean isDefaultCommand() {
            return defaultCommand;
        }

        public CommandFeedback getFeedback() {
            return feedback;
        }

        public ModeMapping getModeMapping() {
            return modeMapping;
        }

        public CommandStep[] getSteps() {
            return steps;
        }
    }

    public abstract static class CommandStep {
        FailurePolicy failurePolicy = FailurePolicy.Continue;
        boolean optional;

        public FailurePolicy getFailurePolicy() {
            return failurePolicy;
        }

        public boolean isOptional() {
            return optional;
        }
    }

    public static final class SetStateStep extends CommandStep {
        String state;
        String subState;

        public String getState() {
            return state;
        }

        public String getSubState() {
            return subState;
        }
    }

    public static final class SetTargetStep extends CommandStep {
        String targetSlot = "MasterTarget";
        TargetSource source = TargetSource.CrosshairTarget;

        public String getTargetSlot() {
            return targetSlot;
        }

        public TargetSource getSource() {
            return source;
        }
    }

    public static final class ClearTargetStep extends CommandStep {
        String targetSlot = "MasterTarget";

        public String getTargetSlot() {
            return targetSlot;
        }
    }

    public static final class ClearCombatStep extends CommandStep {
        String state = "Idle";
        String subState;
        String[] targetSlots = new String[] { "LockedTarget" };
        boolean assignOwnerAsMasterTarget = true;

        public String getState() {
            return state;
        }

        public String getSubState() {
            return subState;
        }

        public String[] getTargetSlots() {
            return targetSlots;
        }

        public boolean isAssignOwnerAsMasterTarget() {
            return assignOwnerAsMasterTarget;
        }
    }

    public static final class MoveToPositionStep extends CommandStep {
        MoveSource source = MoveSource.RaycastHit;

        public MoveSource getSource() {
            return source;
        }
    }

    public static final class StoreHomeStep extends CommandStep {
        StoreSource source = StoreSource.RaycastHit;

        public StoreSource getSource() {
            return source;
        }
    }

    public static final class TriggerHookStep extends CommandStep {
        String hookId;
        Map<String, String> payload = Collections.emptyMap();

        public String getHookId() {
            return hookId;
        }

        public Map<String, String> getPayload() {
            return payload;
        }
    }

    public static final class CommandFeedback {
        String chatMessage;
        String hudMessage;
        String soundEvent;
        String particleSystem;
        Vector3d particleOffset;

        public String getChatMessage() {
            return chatMessage;
        }

        public String getHudMessage() {
            return hudMessage;
        }

        public String getSoundEvent() {
            return soundEvent;
        }

        public String getParticleSystem() {
            return particleSystem;
        }

        public Vector3d getParticleOffset() {
            return particleOffset;
        }
    }
}


