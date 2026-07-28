package com.alechilles.alecstamework.items.persistence;

import com.alechilles.alecstamework.companion.identity.CompanionIdentity;
import com.alechilles.alecstamework.companion.identity.CompanionToolLink;
import com.alechilles.alecstamework.companion.profile.CompanionProfileMutation;
import com.alechilles.alecstamework.persistence.kernel.Sha256Hash;
import java.util.List;

/** Builds an atomic live adoption only from immutable world-thread facts. */
final class SpawnerCaptureAdoptionFactory {

    CompanionProfileMutation.AdoptLive create(
            SpawnerCaptureContext context,
            SpawnerCaptureEvidenceFreezer.FrozenCapture frozen
    ) {
        SpawnerCaptureLiveFacts facts = frozen.liveFacts();
        String metadata = facts.metadataJson();
        CompanionIdentity identity = new CompanionIdentity(
                context.profileId(),
                facts.displayName(),
                firstText(facts.roleId(), context.roleId()),
                metadata,
                Sha256Hash.ofUtf8(metadata),
                context.worldKey(),
                frozen.requestedAt(),
                frozen.requestedAt(),
                frozen.requestedAt(),
                0L
        );
        return new CompanionProfileMutation.AdoptLive(
                identity,
                context.sourceAlias(),
                context.liveOwnerId(),
                context.worldKey(),
                toolLinks(context, facts, frozen.requestedAt()),
                frozen.requestedAt()
        );
    }

    private List<CompanionToolLink> toolLinks(
            SpawnerCaptureContext context,
            SpawnerCaptureLiveFacts facts,
            long requestedAt
    ) {
        return facts.toolIds().stream()
                .map(toolId -> new CompanionToolLink(
                        context.profileId(),
                        toolId,
                        "profile",
                        requestedAt,
                        requestedAt
                ))
                .toList();
    }

    private String firstText(String first, String fallback) {
        return first != null && !first.isBlank()
                ? first.trim()
                : fallback == null || fallback.isBlank()
                ? null
                : fallback.trim();
    }
}
