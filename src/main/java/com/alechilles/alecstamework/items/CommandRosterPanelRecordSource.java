package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.command.CommandFamilyKey;
import com.alechilles.alecstamework.companion.command.CommandRosterActionView;
import com.alechilles.alecstamework.companion.command.CommandRosterHome;
import com.alechilles.alecstamework.companion.command.CommandRosterMembership;
import com.alechilles.alecstamework.companion.identity.OwnerId;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.lifecycle.LifecycleState;
import com.alechilles.alecstamework.persistence.runtime.PublicPersistenceQueries;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Adapts the canonical owner/family roster projection to command-panel records.
 *
 * <p>Command items are access keys for this source; item metadata is never a
 * membership authority. Timed lease presentation deliberately remains outside
 * {@link LinkedNpcRecord#cachedCommandState}, which is reserved for the NPC's
 * gameplay command state.</p>
 */
final class CommandRosterPanelRecordSource {
    private static final String PRESENTATION_UUID_NAMESPACE =
            "tamework-roster-profile\u0000";

    private final ProjectionLookup projections;

    CommandRosterPanelRecordSource(
            @Nonnull PublicPersistenceQueries queries
    ) {
        this(queries::projectedCommandRosterActions);
    }

    CommandRosterPanelRecordSource(@Nonnull ProjectionLookup projections) {
        this.projections = Objects.requireNonNull(
                projections, "Roster projections are required"
        );
    }

    /**
     * Returns the complete, deterministic panel record set for one physical
     * owner's command-family access item.
     */
    @Nonnull
    List<LinkedNpcRecord> recordsFor(
            @Nullable UUID ownerUuid,
            @Nullable String commandFamilyId
    ) {
        List<PanelMember> members = membersFor(ownerUuid, commandFamilyId);
        if (members.isEmpty()) {
            return List.of();
        }
        ArrayList<LinkedNpcRecord> records =
                new ArrayList<>(members.size());
        for (PanelMember member : members) {
            records.add(toRecord(member.view()));
        }
        return List.copyOf(records);
    }

    /**
     * Returns the durable roster identity behind each panel UUID.
     *
     * <p>This is also the bridge used by command feature presentation and
     * actions. The UUID remains presentation-only; every mutation is authored
     * from the stable profile identity retained here.</p>
     */
    @Nonnull
    List<PanelMember> membersFor(
            @Nullable UUID ownerUuid,
            @Nullable String commandFamilyId
    ) {
        CommandFamilyKey familyKey = familyKey(ownerUuid, commandFamilyId);
        if (familyKey == null) {
            return List.of();
        }
        Map<ProfileId, CommandRosterActionView> snapshot;
        try {
            snapshot = projections.actionSnapshot();
        } catch (RuntimeException | LinkageError ignored) {
            return List.of();
        }
        if (snapshot == null || snapshot.isEmpty()) {
            return List.of();
        }

        ArrayList<PanelMember> matches = new ArrayList<>();
        for (Map.Entry<ProfileId, CommandRosterActionView> entry
                : snapshot.entrySet()) {
            CommandRosterActionView view = entry.getValue();
            if (!consistent(entry.getKey(), view)
                    || !familyKey.equals(view.membership().familyKey())) {
                continue;
            }
            matches.add(new PanelMember(
                    presentationUuid(view),
                    view.membership().profileId().toString(),
                    view.roleId(),
                    view.lifecycle().state(),
                    view
            ));
        }
        matches.sort(Comparator.comparing(
                PanelMember::profileId
        ));
        return List.copyOf(matches);
    }

    @Nullable
    private static CommandFamilyKey familyKey(
            @Nullable UUID ownerUuid,
            @Nullable String commandFamilyId
    ) {
        if (ownerUuid == null || commandFamilyId == null
                || commandFamilyId.isBlank()) {
            return null;
        }
        return new CommandFamilyKey(
                new OwnerId(ownerUuid), commandFamilyId
        );
    }

    private static boolean consistent(
            @Nullable ProfileId key,
            @Nullable CommandRosterActionView view
    ) {
        return key != null && view != null
                && key.equals(view.membership().profileId());
    }

    @Nonnull
    private static LinkedNpcRecord toRecord(
            @Nonnull CommandRosterActionView view
    ) {
        CommandRosterMembership membership = view.membership();
        CommandRosterHome home = membership.home();
        return new LinkedNpcRecord(
                presentationUuid(view),
                membership.profileId().toString(),
                null,
                null,
                home == null ? null : new Vector3d(
                        home.x(), home.y(), home.z()
                ),
                null,
                null,
                view.roleId(),
                null,
                membership.activeForBulkCommands(),
                false,
                membership.groupId()
        );
    }

    @Nonnull
    private static UUID presentationUuid(
            @Nonnull CommandRosterActionView view
    ) {
        return view.currentAlias() != null
                ? view.currentAlias().value()
                : presentationUuid(view.membership().profileId());
    }

    @Nonnull
    static UUID presentationUuid(@Nonnull ProfileId profileId) {
        Objects.requireNonNull(profileId, "Profile ID is required");
        return UUID.nameUUIDFromBytes(
                (PRESENTATION_UUID_NAMESPACE + profileId)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    /** Test seam for immutable replacement projection snapshots. */
    @FunctionalInterface
    interface ProjectionLookup {
        @Nonnull
        Map<ProfileId, CommandRosterActionView> actionSnapshot();
    }

    /** Stable identity and lifecycle context for one rendered roster row. */
    record PanelMember(
            @Nonnull UUID presentationUuid,
            @Nonnull String profileId,
            @Nonnull String roleId,
            @Nonnull LifecycleState lifecycleState,
            @Nonnull CommandRosterActionView view
    ) {
        PanelMember {
            Objects.requireNonNull(presentationUuid, "Presentation UUID is required");
            profileId = Objects.requireNonNull(profileId, "Profile ID is required");
            roleId = Objects.requireNonNull(roleId, "Role ID is required");
            Objects.requireNonNull(lifecycleState, "Lifecycle state is required");
            Objects.requireNonNull(view, "Roster action view is required");
        }
    }
}
