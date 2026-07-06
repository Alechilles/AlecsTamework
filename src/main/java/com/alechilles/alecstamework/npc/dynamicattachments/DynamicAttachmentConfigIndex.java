package com.alechilles.alecstamework.npc.dynamicattachments;

import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Role-keyed lookup facade for dynamic attachment rule entries. */
public final class DynamicAttachmentConfigIndex {
    private final Map<String, List<TwDynamicAttachmentsConfig.RoleRuleEntry>> entriesByRole;
    private final Function<String, List<TwDynamicAttachmentsConfig.RoleRuleEntry>> roleResolver;

    private DynamicAttachmentConfigIndex(
            @Nullable Map<String, List<TwDynamicAttachmentsConfig.RoleRuleEntry>> entriesByRole,
            @Nullable Function<String, List<TwDynamicAttachmentsConfig.RoleRuleEntry>> roleResolver) {
        this.entriesByRole = entriesByRole;
        this.roleResolver = roleResolver;
    }

    @Nonnull
    public static DynamicAttachmentConfigIndex current() {
        return new DynamicAttachmentConfigIndex(null, TwDynamicAttachmentsConfig::resolveRulesForRole);
    }

    @Nonnull
    public static DynamicAttachmentConfigIndex empty() {
        return forTest(Map.of());
    }

    @Nonnull
    public static DynamicAttachmentConfigIndex emptyForTest() {
        return empty();
    }

    @Nonnull
    public static DynamicAttachmentConfigIndex forTest(
            @Nullable Map<String, List<TwDynamicAttachmentsConfig.RoleRuleEntry>> entriesByRole) {
        if (entriesByRole == null || entriesByRole.isEmpty()) {
            return new DynamicAttachmentConfigIndex(Map.of(), null);
        }
        Map<String, List<TwDynamicAttachmentsConfig.RoleRuleEntry>> mutable = new HashMap<>();
        for (Map.Entry<String, List<TwDynamicAttachmentsConfig.RoleRuleEntry>> entry : entriesByRole.entrySet()) {
            String roleKey = normalizeRoleKey(entry.getKey());
            if (roleKey.isEmpty() || entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            List<TwDynamicAttachmentsConfig.RoleRuleEntry> rules =
                    mutable.computeIfAbsent(roleKey, ignored -> new ArrayList<>());
            for (TwDynamicAttachmentsConfig.RoleRuleEntry ruleEntry : entry.getValue()) {
                if (ruleEntry != null) {
                    rules.add(ruleEntry);
                }
            }
        }
        if (mutable.isEmpty()) {
            return new DynamicAttachmentConfigIndex(Map.of(), null);
        }
        Map<String, List<TwDynamicAttachmentsConfig.RoleRuleEntry>> immutable = new HashMap<>();
        for (Map.Entry<String, List<TwDynamicAttachmentsConfig.RoleRuleEntry>> entry : mutable.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                immutable.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
        }
        return new DynamicAttachmentConfigIndex(Map.copyOf(immutable), null);
    }

    public boolean hasRulesForRole(@Nullable String roleId) {
        return !rulesForRole(roleId).isEmpty();
    }

    @Nonnull
    public List<TwDynamicAttachmentsConfig.RoleRuleEntry> rulesForRole(@Nullable String roleId) {
        String roleKey = normalizeRoleKey(roleId);
        if (roleKey.isEmpty()) {
            return List.of();
        }
        if (entriesByRole != null) {
            return entriesByRole.getOrDefault(roleKey, List.of());
        }
        if (roleResolver == null) {
            return List.of();
        }
        List<TwDynamicAttachmentsConfig.RoleRuleEntry> entries = roleResolver.apply(roleKey);
        return entries == null || entries.isEmpty() ? List.of() : entries;
    }

    @Nonnull
    private static String normalizeRoleKey(@Nullable String roleId) {
        return roleId == null ? "" : roleId.trim().toLowerCase(Locale.ROOT);
    }
}
