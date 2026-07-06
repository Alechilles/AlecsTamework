package com.alechilles.alecstamework.npc.dynamicattachments;

import com.alechilles.alecstamework.config.assets.TwDynamicAttachmentsConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Resolves ordered dynamic attachment rules into permanent and temporary slot selections. */
public final class DynamicAttachmentRuleResolver {
    private DynamicAttachmentRuleResolver() {
    }

    @Nonnull
    public static DynamicAttachmentResolution resolve(
            @Nullable DynamicAttachmentNpcSnapshot snapshot,
            @Nullable List<TwDynamicAttachmentsConfig.RoleRuleEntry> orderedRules) {
        if (orderedRules == null || orderedRules.isEmpty()) {
            return new DynamicAttachmentResolution(Map.of(), Map.of());
        }
        Map<String, String> permanent = new HashMap<>();
        Map<String, DynamicAttachmentResolution.TemporaryAttachment> temporary = new HashMap<>();
        for (int index = 0; index < orderedRules.size(); index++) {
            TwDynamicAttachmentsConfig.RoleRuleEntry entry = orderedRules.get(index);
            if (entry == null) {
                continue;
            }
            TwDynamicAttachmentsConfig.Rule rule = entry.getRule();
            if (rule == null || !matchesAllConditions(rule, snapshot)) {
                continue;
            }
            applyAttachments(entry, rule, permanent, temporary);
        }
        return new DynamicAttachmentResolution(permanent, temporary);
    }

    private static boolean matchesAllConditions(@Nonnull TwDynamicAttachmentsConfig.Rule rule,
                                                @Nullable DynamicAttachmentNpcSnapshot snapshot) {
        TwDynamicAttachmentsConfig.Condition[] conditions = rule.getConditions();
        for (int index = 0; index < conditions.length; index++) {
            if (!DynamicAttachmentConditionEvaluator.matches(conditions[index], snapshot)) {
                return false;
            }
        }
        return true;
    }

    private static void applyAttachments(
            @Nonnull TwDynamicAttachmentsConfig.RoleRuleEntry entry,
            @Nonnull TwDynamicAttachmentsConfig.Rule rule,
            @Nonnull Map<String, String> permanent,
            @Nonnull Map<String, DynamicAttachmentResolution.TemporaryAttachment> temporary) {
        Map<String, String> attachments = rule.getAttachments();
        if (attachments.isEmpty()) {
            return;
        }
        String ruleKey = null;
        for (Map.Entry<String, String> attachment : attachments.entrySet()) {
            String slot = clean(attachment.getKey());
            String value = clean(attachment.getValue());
            if (slot == null || value == null || permanent.containsKey(slot) || temporary.containsKey(slot)) {
                continue;
            }
            if (rule.getPersistence() == TwDynamicAttachmentsConfig.Persistence.WHILE_MATCHING) {
                if (ruleKey == null) {
                    ruleKey = ruleKey(entry);
                }
                temporary.put(slot, new DynamicAttachmentResolution.TemporaryAttachment(value, ruleKey));
            } else {
                permanent.put(slot, value);
            }
        }
    }

    @Nonnull
    private static String ruleKey(@Nonnull TwDynamicAttachmentsConfig.RoleRuleEntry entry) {
        TwDynamicAttachmentsConfig config = entry.getConfig();
        TwDynamicAttachmentsConfig.Rule rule = entry.getRule();
        String configId = config == null ? null : clean(config.getId());
        String ruleId = rule == null ? null : clean(rule.getId());
        if (configId != null && ruleId != null) {
            return configId + "/" + ruleId;
        }
        if (configId != null) {
            return configId + "#" + entry.getDeclarationOrder();
        }
        if (ruleId != null) {
            return ruleId;
        }
        return "#" + entry.getDeclarationOrder();
    }

    @Nullable
    private static String clean(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
