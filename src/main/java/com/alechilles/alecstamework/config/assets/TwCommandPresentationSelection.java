package com.alechilles.alecstamework.config.assets;

import com.alechilles.alecstamework.api.commandhud.CommandHudContributorId;
import com.alechilles.alecstamework.api.commandhud.CommandHudContributorRequirement;
import com.alechilles.alecstamework.api.commandhud.CommandHudRendererId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorId;
import com.alechilles.alecstamework.api.commandui.CommandUiContributorRequirement;
import com.alechilles.alecstamework.api.commandui.CommandUiRendererId;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Owns command-menu and command-HUD presentation selections for one item config.
 *
 * <p>The selection is kept separate from command behavior so each presentation
 * surface can be inherited and replaced independently.</p>
 */
final class TwCommandPresentationSelection {
    static final BuilderCodec<TwCommandItemConfig.UiContributorSettings> UI_CONTRIBUTOR_CODEC =
            BuilderCodec.builder(
                            TwCommandItemConfig.UiContributorSettings.class,
                            TwCommandItemConfig.UiContributorSettings::new
                    )
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
    static final ArrayCodec<TwCommandItemConfig.UiContributorSettings> UI_CONTRIBUTOR_ARRAY_CODEC =
            new ArrayCodec<>(UI_CONTRIBUTOR_CODEC, TwCommandItemConfig.UiContributorSettings[]::new);
    static final BuilderCodec<TwCommandItemConfig.UiContributorSettings> HUD_CONTRIBUTOR_CODEC =
            BuilderCodec.builder(
                            TwCommandItemConfig.UiContributorSettings.class,
                            TwCommandItemConfig.UiContributorSettings::new
                    )
                    .<String>append(
                            new KeyedCodec<>("Id", Codec.STRING),
                            (settings, value) -> settings.id = value,
                            settings -> settings.id
                    )
                    .documentation("Namespaced contributor ID selected for this command HUD surface.")
                    .add()
                    .<Boolean>append(
                            new KeyedCodec<>("Required", Codec.BOOLEAN),
                            (settings, value) -> settings.required = value != null && value,
                            settings -> settings.required
                    )
                    .documentation("When true, contributor failure requires standard HUD fallback.")
                    .add()
                    .build();
    static final ArrayCodec<TwCommandItemConfig.UiContributorSettings> HUD_CONTRIBUTOR_ARRAY_CODEC =
            new ArrayCodec<>(HUD_CONTRIBUTOR_CODEC, TwCommandItemConfig.UiContributorSettings[]::new);

    private static final CommandUiContributorRequirement[] EMPTY_UI_CONTRIBUTORS =
            new CommandUiContributorRequirement[0];
    private static final CommandHudContributorRequirement[] EMPTY_HUD_CONTRIBUTORS =
            new CommandHudContributorRequirement[0];

    private String uiRendererId;
    private CommandUiContributorRequirement[] uiContributors = EMPTY_UI_CONTRIBUTORS;
    private String targetHudRendererId;
    private CommandHudContributorRequirement[] targetHudContributors = EMPTY_HUD_CONTRIBUTORS;
    private String hotswapHudRendererId;
    private CommandHudContributorRequirement[] hotswapHudContributors = EMPTY_HUD_CONTRIBUTORS;

    void setUiRendererId(@Nullable String value) {
        uiRendererId = normalizeUiRendererId(value);
    }

    void setUiContributors(@Nullable TwCommandItemConfig.UiContributorSettings[] value) {
        uiContributors = normalizeUiContributors(value);
    }

    void setTargetHudRendererId(@Nullable String value) {
        targetHudRendererId = normalizeHudRendererId(value);
    }

    void setTargetHudContributors(@Nullable TwCommandItemConfig.UiContributorSettings[] value) {
        targetHudContributors = normalizeHudContributors(value, "TargetHudContributors");
    }

    void setHotswapHudRendererId(@Nullable String value) {
        hotswapHudRendererId = normalizeHudRendererId(value);
    }

    void setHotswapHudContributors(@Nullable TwCommandItemConfig.UiContributorSettings[] value) {
        hotswapHudContributors = normalizeHudContributors(value, "HotswapHudContributors");
    }

    @Nullable
    String uiRendererId() {
        return uiRendererId;
    }

    @Nonnull
    List<CommandUiContributorRequirement> uiContributors() {
        return List.of(uiContributors);
    }

    @Nullable
    String targetHudRendererId() {
        return targetHudRendererId;
    }

    @Nonnull
    List<CommandHudContributorRequirement> targetHudContributors() {
        return List.of(targetHudContributors);
    }

    @Nullable
    String hotswapHudRendererId() {
        return hotswapHudRendererId;
    }

    @Nonnull
    List<CommandHudContributorRequirement> hotswapHudContributors() {
        return List.of(hotswapHudContributors);
    }

    void inheritMissingTopLevelFrom(@Nonnull TwCommandPresentationSelection parent,
                                    @Nonnull Set<String> explicitTopLevelKeys) {
        if (!explicitTopLevelKeys.contains("UiRendererId")) {
            uiRendererId = parent.uiRendererId;
        }
        if (!explicitTopLevelKeys.contains("UiContributors")) {
            uiContributors = parent.uiContributors.clone();
        }
        if (!explicitTopLevelKeys.contains("TargetHudRendererId")) {
            targetHudRendererId = parent.targetHudRendererId;
        }
        if (!explicitTopLevelKeys.contains("TargetHudContributors")) {
            targetHudContributors = parent.targetHudContributors.clone();
        }
        if (!explicitTopLevelKeys.contains("HotswapHudRendererId")) {
            hotswapHudRendererId = parent.hotswapHudRendererId;
        }
        if (!explicitTopLevelKeys.contains("HotswapHudContributors")) {
            hotswapHudContributors = parent.hotswapHudContributors.clone();
        }
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

    @Nullable
    private static String normalizeHudRendererId(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return CommandHudRendererId.of(value).value();
    }

    private static CommandUiContributorRequirement[] normalizeUiContributors(
            @Nullable TwCommandItemConfig.UiContributorSettings[] value
    ) {
        if (value == null || value.length == 0) {
            return EMPTY_UI_CONTRIBUTORS;
        }
        List<CommandUiContributorRequirement> requirements = new ArrayList<>(value.length);
        Set<CommandUiContributorId> seen = new HashSet<>();
        for (TwCommandItemConfig.UiContributorSettings settings : value) {
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

    private static CommandHudContributorRequirement[] normalizeHudContributors(
            @Nullable TwCommandItemConfig.UiContributorSettings[] value,
            @Nonnull String fieldName
    ) {
        if (value == null || value.length == 0) {
            return EMPTY_HUD_CONTRIBUTORS;
        }
        List<CommandHudContributorRequirement> requirements = new ArrayList<>(value.length);
        Set<CommandHudContributorId> seen = new HashSet<>();
        for (TwCommandItemConfig.UiContributorSettings settings : value) {
            if (settings == null) {
                throw new IllegalArgumentException(fieldName + " cannot contain null entries.");
            }
            CommandHudContributorId id = CommandHudContributorId.of(settings.id);
            if (!seen.add(id)) {
                throw new IllegalArgumentException(fieldName + " contains duplicate ID: " + id.value());
            }
            requirements.add(new CommandHudContributorRequirement(id, settings.required));
        }
        return requirements.toArray(CommandHudContributorRequirement[]::new);
    }

    private static TwCommandItemConfig.UiContributorSettings[] toUiContributorSettings(
            @Nullable CommandUiContributorRequirement[] requirements
    ) {
        if (requirements == null || requirements.length == 0) {
            return new TwCommandItemConfig.UiContributorSettings[0];
        }
        TwCommandItemConfig.UiContributorSettings[] settings =
                new TwCommandItemConfig.UiContributorSettings[requirements.length];
        for (int index = 0; index < requirements.length; index++) {
            CommandUiContributorRequirement requirement = requirements[index];
            TwCommandItemConfig.UiContributorSettings setting =
                    new TwCommandItemConfig.UiContributorSettings();
            setting.id = requirement.id().value();
            setting.required = requirement.required();
            settings[index] = setting;
        }
        return settings;
    }

    private static TwCommandItemConfig.UiContributorSettings[] toHudContributorSettings(
            @Nullable CommandHudContributorRequirement[] requirements
    ) {
        if (requirements == null || requirements.length == 0) {
            return new TwCommandItemConfig.UiContributorSettings[0];
        }
        TwCommandItemConfig.UiContributorSettings[] settings =
                new TwCommandItemConfig.UiContributorSettings[requirements.length];
        for (int index = 0; index < requirements.length; index++) {
            CommandHudContributorRequirement requirement = requirements[index];
            TwCommandItemConfig.UiContributorSettings setting =
                    new TwCommandItemConfig.UiContributorSettings();
            setting.id = requirement.id().value();
            setting.required = requirement.required();
            settings[index] = setting;
        }
        return settings;
    }

    TwCommandItemConfig.UiContributorSettings[] uiContributorSettings() {
        return toUiContributorSettings(uiContributors);
    }

    TwCommandItemConfig.UiContributorSettings[] targetHudContributorSettings() {
        return toHudContributorSettings(targetHudContributors);
    }

    TwCommandItemConfig.UiContributorSettings[] hotswapHudContributorSettings() {
        return toHudContributorSettings(hotswapHudContributors);
    }
}
