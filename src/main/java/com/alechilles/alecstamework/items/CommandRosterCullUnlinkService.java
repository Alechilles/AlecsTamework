package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.api.CommandFamilyRosterApi;
import com.alechilles.alecstamework.api.CommandFamilyRosterMembershipView;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationRequest;
import com.alechilles.alecstamework.api.CommandFamilyRosterMutationResult;
import com.alechilles.alecstamework.api.CommandFamilyRosterView;
import com.alechilles.alecstamework.api.TameworkApi;
import com.alechilles.alecstamework.api.TameworkApiCapability;
import com.alechilles.alecstamework.config.CommandItemRegistry;
import com.alechilles.alecstamework.config.assets.TwCommandItemConfig;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Removes a command-roster membership before its live NPC is culled. */
final class CommandRosterCullUnlinkService {
    private static final String CALLER = "Alechilles:Tamework:Cull";

    enum PreparationStatus {
        NOT_ROSTER_MEMBER,
        READY,
        UNAVAILABLE
    }

    record Preparation(PreparationStatus status,
                       @Nullable CommandFamilyRosterApi rosters,
                       @Nullable CommandFamilyRosterMutationRequest request) {
        boolean isReady() {
            return status == PreparationStatus.READY
                    && rosters != null && request != null;
        }
    }

    private final CommandItemRegistry registry;
    private final Supplier<CommandFamilyRosterApi> rosters;

    CommandRosterCullUnlinkService(
            @Nullable CommandItemRegistry registry,
            Supplier<TameworkApi> api
    ) {
        this.registry = registry;
        this.rosters = () -> {
            TameworkApi current = api == null ? null : api.get();
            return current == null || !current.getCapabilities().contains(
                    TameworkApiCapability.COMMAND_FAMILY_ROSTERS)
                    ? null : current.commandFamilyRosters();
        };
    }

    CommandRosterCullUnlinkService(
            @Nullable CommandItemRegistry registry,
            @Nullable CommandFamilyRosterApi rosters
    ) {
        this.registry = registry;
        this.rosters = () -> rosters;
    }

    /** Resolves the one durable roster row that owns a command-roster NPC. */
    Preparation prepare(@Nullable UUID ownerUuid, @Nullable String profileId) {
        if (ownerUuid == null || profileId == null || profileId.isBlank()) {
            return unavailable();
        }
        CommandFamilyRosterApi currentRosters = rosters.get();
        if (currentRosters == null || registry == null) {
            return unavailable();
        }
        Set<String> visitedFamilies = new HashSet<>();
        for (TwCommandItemConfig config : registry.snapshot().values()) {
            String familyId = config == null ? null : config.getCommandFamilyId();
            String configId = config == null ? null : config.getId();
            if (config == null || !config.usesOwnerCommandFamilyRoster()
                    || familyId == null || familyId.isBlank()
                    || configId == null || configId.isBlank()
                    || !visitedFamilies.add(familyId)) {
                continue;
            }
            Optional<CommandFamilyRosterMembershipView> membership =
                    currentRosters.getMembership(ownerUuid, familyId, profileId);
            if (membership.isEmpty()) {
                continue;
            }
            if (registry.validateOwnerFamilyAccess(
                    familyId, configId, null, membership.get().roleId()
            ) != null) {
                continue;
            }
            Optional<CommandFamilyRosterView> roster = currentRosters.get(
                    ownerUuid, familyId
            );
            if (roster.isEmpty()) {
                return unavailable();
            }
            CommandFamilyRosterMembershipView member = membership.get();
            return new Preparation(
                    PreparationStatus.READY,
                    currentRosters,
                    new CommandFamilyRosterMutationRequest(
                            CALLER,
                            "cull:" + profileId + ":" + familyId + ":"
                                    + roster.get().revision(),
                            null,
                            ownerUuid,
                            familyId,
                            profileId,
                            configId,
                            null,
                            member.state(),
                            member.groupId(),
                            member.activeForBulkCommands(),
                            member.homePosition(),
                            roster.get().revision(),
                            member.profileRevision()
                    )
            );
        }
        return new Preparation(
                PreparationStatus.NOT_ROSTER_MEMBER, null, null
        );
    }

    @Nullable
    CompletionStage<Boolean> remove(Preparation preparation) {
        if (!preparation.isReady()) {
            return null;
        }
        try {
            CompletionStage<CommandFamilyRosterMutationResult> stage =
                    preparation.rosters().remove(preparation.request());
            return stage == null ? null : stage.thenApply(result ->
                    result != null && result.accepted());
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    private static Preparation unavailable() {
        return new Preparation(PreparationStatus.UNAVAILABLE, null, null);
    }
}
