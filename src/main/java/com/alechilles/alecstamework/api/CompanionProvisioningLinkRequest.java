package com.alechilles.alecstamework.api;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Atomic dormant provisioning plus canonical command-family roster membership. */
public record CompanionProvisioningLinkRequest(
        @Nonnull CompanionProvisioningRequest provisioning,
        @Nonnull String commandFamilyId,
        @Nonnull String requiredCommandConfigId,
        @Nullable String accessItemId,
        @Nullable String groupId,
        boolean activeForBulkCommands,
        boolean requestInitialProjection) {
    public CompanionProvisioningLinkRequest {
        provisioning = Objects.requireNonNull(provisioning, "provisioning");
        if (provisioning.disposition() != CompanionProvisioningDisposition.PROVISIONED_DORMANT) {
            throw new IllegalArgumentException(
                    "Provision-and-link creates a dormant roster member; projection is a separate admitted step.");
        }
        commandFamilyId = requireText(commandFamilyId, "commandFamilyId");
        requiredCommandConfigId = requireText(requiredCommandConfigId, "requiredCommandConfigId");
        accessItemId = normalize(accessItemId);
        groupId = normalize(groupId);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required.");
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
