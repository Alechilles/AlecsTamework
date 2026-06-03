package com.alechilles.alecstamework.npc.attachments;

import com.alechilles.alecstamework.config.assets.TwAttachmentDisplayConfig;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolves raw model attachment selections into player-facing display labels.
 */
public final class AttachmentDisplayResolver {
    public static final AttachmentDisplayResolver ASSET_BACKED = new AttachmentDisplayResolver(null);

    private final List<TwAttachmentDisplayConfig> configuredConfigs;

    public AttachmentDisplayResolver(@Nullable List<TwAttachmentDisplayConfig> configuredConfigs) {
        this.configuredConfigs = configuredConfigs == null ? null : List.copyOf(configuredConfigs);
    }

    @Nonnull
    public List<ResolvedAttachmentDisplay> resolveAll(@Nullable String roleId,
                                                      @Nullable String modelId,
                                                      @Nullable Map<String, String> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        List<ResolvedAttachmentDisplay> resolved = new ArrayList<>();
        attachments.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .forEach(entry -> resolved.add(resolve(roleId, modelId, entry.getKey(), entry.getValue())));
        return resolved;
    }

    @Nonnull
    public ResolvedAttachmentDisplay resolve(@Nullable String roleId,
                                             @Nullable String modelId,
                                             @Nonnull String setId,
                                             @Nullable String valueId) {
        Candidate best = null;
        for (TwAttachmentDisplayConfig config : configs()) {
            if (config == null || !config.isEnabled()) {
                continue;
            }
            for (TwAttachmentDisplayConfig.Entry entry : config.getEntries()) {
                Candidate candidate = candidateFor(config, entry, roleId, modelId, setId, valueId);
                if (candidate != null && (best == null || CANDIDATE_ORDER.compare(candidate, best) < 0)) {
                    best = candidate;
                }
            }
        }
        if (best == null) {
            return new ResolvedAttachmentDisplay(setId, setId, valueId, valueId);
        }
        return new ResolvedAttachmentDisplay(
                setId,
                firstNonBlank(best.setDisplay.getLabel(), setId),
                valueId,
                firstNonBlank(best.valueLabel, valueId)
        );
    }

    @Nullable
    private Candidate candidateFor(@Nonnull TwAttachmentDisplayConfig config,
                                   @Nullable TwAttachmentDisplayConfig.Entry entry,
                                   @Nullable String roleId,
                                   @Nullable String modelId,
                                   @Nonnull String setId,
                                   @Nullable String valueId) {
        if (entry == null || entry.getSets().isEmpty()) {
            return null;
        }
        TwAttachmentDisplayConfig.AttachmentSetDisplay setDisplay = findSetDisplay(entry, setId);
        if (setDisplay == null) {
            return null;
        }
        MatchType matchType = matchType(entry.getAppliesTo(), roleId, modelId);
        if (matchType == null) {
            return null;
        }
        String valueLabel = findValueLabel(setDisplay, valueId);
        return new Candidate(config, entry, setDisplay, valueLabel, matchType);
    }

    @Nullable
    private static TwAttachmentDisplayConfig.AttachmentSetDisplay findSetDisplay(
            @Nonnull TwAttachmentDisplayConfig.Entry entry,
            @Nonnull String setId) {
        TwAttachmentDisplayConfig.AttachmentSetDisplay direct = entry.getSets().get(setId);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, TwAttachmentDisplayConfig.AttachmentSetDisplay> candidate : entry.getSets().entrySet()) {
            if (candidate.getKey() != null && candidate.getKey().equalsIgnoreCase(setId)) {
                return candidate.getValue();
            }
        }
        return null;
    }

    @Nullable
    private static String findValueLabel(@Nonnull TwAttachmentDisplayConfig.AttachmentSetDisplay setDisplay,
                                         @Nullable String valueId) {
        if (valueId == null || valueId.isBlank()) {
            return null;
        }
        String direct = setDisplay.getValues().get(valueId);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<String, String> candidate : setDisplay.getValues().entrySet()) {
            if (candidate.getKey() != null && candidate.getKey().equalsIgnoreCase(valueId)) {
                return candidate.getValue();
            }
        }
        return null;
    }

    @Nullable
    private static MatchType matchType(@Nonnull TwAttachmentDisplayConfig.AppliesTo appliesTo,
                                       @Nullable String roleId,
                                       @Nullable String modelId) {
        if (matchesAny(appliesTo.getModelIds(), modelId)) {
            return MatchType.EXACT_MODEL;
        }
        if (matchesAny(appliesTo.getRoleIds(), roleId)) {
            return MatchType.EXACT_ROLE;
        }
        if (matchesAny(appliesTo.getModelNamespaces(), namespaceOf(modelId))) {
            return MatchType.MODEL_NAMESPACE;
        }
        if (matchesAny(appliesTo.getRoleNamespaces(), namespaceOf(roleId))) {
            return MatchType.ROLE_NAMESPACE;
        }
        return appliesTo.isGlobal() ? MatchType.GLOBAL : null;
    }

    private List<TwAttachmentDisplayConfig> configs() {
        if (configuredConfigs != null) {
            return configuredConfigs;
        }
        DefaultAssetMap<String, TwAttachmentDisplayConfig> assetMap = TwAttachmentDisplayConfig.getAssetMap();
        if (assetMap == null || assetMap.getAssetMap() == null || assetMap.getAssetMap().isEmpty()) {
            return List.of();
        }
        return List.copyOf(assetMap.getAssetMap().values());
    }

    private static boolean matchesAny(@Nullable String[] candidates, @Nullable String actual) {
        if (candidates == null || candidates.length == 0 || actual == null || actual.isBlank()) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && candidate.trim().equalsIgnoreCase(actual.trim())) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static String namespaceOf(@Nullable String assetId) {
        if (assetId == null || assetId.isBlank()) {
            return null;
        }
        int separator = assetId.indexOf(':');
        if (separator <= 0) {
            return null;
        }
        return assetId.substring(0, separator);
    }

    @Nullable
    private static String firstNonBlank(@Nullable String first, @Nullable String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER = Comparator
            .comparingInt((Candidate candidate) -> -candidate.matchType.weight)
            .thenComparingInt(candidate -> -candidate.config.getPriority())
            .thenComparing(candidate -> safeLower(candidate.config.getId()))
            .thenComparing(candidate -> safeLower(candidate.entry.getId()));

    private static String safeLower(@Nullable String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private enum MatchType {
        EXACT_MODEL(5),
        EXACT_ROLE(4),
        MODEL_NAMESPACE(3),
        ROLE_NAMESPACE(2),
        GLOBAL(1);

        private final int weight;

        MatchType(int weight) {
            this.weight = weight;
        }
    }

    private static final class Candidate {
        private final TwAttachmentDisplayConfig config;
        private final TwAttachmentDisplayConfig.Entry entry;
        private final TwAttachmentDisplayConfig.AttachmentSetDisplay setDisplay;
        private final String valueLabel;
        private final MatchType matchType;

        private Candidate(TwAttachmentDisplayConfig config,
                          TwAttachmentDisplayConfig.Entry entry,
                          TwAttachmentDisplayConfig.AttachmentSetDisplay setDisplay,
                          @Nullable String valueLabel,
                          MatchType matchType) {
            this.config = Objects.requireNonNull(config);
            this.entry = Objects.requireNonNull(entry);
            this.setDisplay = Objects.requireNonNull(setDisplay);
            this.valueLabel = valueLabel;
            this.matchType = Objects.requireNonNull(matchType);
        }
    }
}
