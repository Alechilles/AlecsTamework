package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.companion.index.CompanionLocation;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.runtime.CompanionQueries;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3d;

/**
 * Lists one owner's command-family roster members from the companion index as command-panel
 * records ({@link CompanionQueries#rosterMembers}).
 *
 * <p>Command items are access keys for this source; item metadata is never a membership
 * authority. Timed summon presentation stays outside {@link LinkedNpcRecord#cachedCommandState},
 * which is reserved for the NPC's gameplay command state.</p>
 */
final class CommandRosterPanelRecordSource {
    private static final String PRESENTATION_UUID_NAMESPACE =
            "tamework-roster-profile\u0000";

    private final BiFunction<UUID, String, List<CompanionRecord>> members;
    private final Predicate<UUID> bodyLoaded;

    CommandRosterPanelRecordSource(@Nonnull CompanionQueries companions) {
        this(companions::rosterMembers, profileId -> companions.loadedBody(profileId) != null);
    }

    /**
     * @param members    an owner's roster members of one family
     * @param bodyLoaded whether a profile has a loaded body now
     */
    CommandRosterPanelRecordSource(
            @Nonnull BiFunction<UUID, String, List<CompanionRecord>> members,
            @Nonnull Predicate<UUID> bodyLoaded
    ) {
        this.members = Objects.requireNonNull(members, "members");
        this.bodyLoaded = Objects.requireNonNull(bodyLoaded, "bodyLoaded");
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
        return snapshotFor(ownerUuid, commandFamilyId).records();
    }

    /**
     * Reads the roster once so every UI concern uses the same member identities
     * during a single panel refresh.
     */
    @Nonnull
    PanelSnapshot snapshotFor(
            @Nullable UUID ownerUuid,
            @Nullable String commandFamilyId
    ) {
        List<PanelMember> found = membersFor(ownerUuid, commandFamilyId);
        if (found.isEmpty()) {
            return PanelSnapshot.empty();
        }
        ArrayList<LinkedNpcRecord> records = new ArrayList<>(found.size());
        for (PanelMember member : found) {
            records.add(toRecord(member));
        }
        return new PanelSnapshot(found, List.copyOf(records));
    }

    /**
     * Returns the roster member behind each panel UUID, sorted by profile id.
     *
     * <p>This is also the bridge used by command feature presentation and actions. The UUID is
     * presentation-only; every action uses the profile id kept here.</p>
     */
    @Nonnull
    List<PanelMember> membersFor(
            @Nullable UUID ownerUuid,
            @Nullable String commandFamilyId
    ) {
        String familyId = commandFamilyId == null || commandFamilyId.isBlank()
                ? null : commandFamilyId.trim();
        if (ownerUuid == null || familyId == null) {
            return List.of();
        }
        List<CompanionRecord> records;
        try {
            records = members.apply(ownerUuid, familyId);
        } catch (RuntimeException | LinkageError ignored) {
            return List.of();
        }
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        ArrayList<PanelMember> found = new ArrayList<>(records.size());
        for (CompanionRecord record : records) {
            boolean live = record.location().kind() == LocationKind.LIVE;
            boolean loaded = live && bodyLoaded.test(record.profileId());
            found.add(new PanelMember(presentationUuid(record), record, loaded));
        }
        found.sort(Comparator.comparing(PanelMember::profileId));
        return List.copyOf(found);
    }

    @Nonnull
    static LinkedNpcRecord toRecord(@Nonnull PanelMember member) {
        CompanionRecord record = member.record();
        CompanionLocation at = record.location();
        boolean placed = at.world() != null;
        return new LinkedNpcRecord(
                member.presentationUuid(),
                member.profileId(),
                placed ? new Vector3d(at.x(), at.y(), at.z()) : null,
                placed ? at.world() : record.homeWorld(),
                null,
                record.displayName(),
                null,
                record.roleId(),
                null,
                true,
                false,
                null
        );
    }

    /** A LIVE member's row follows its current body; any other member's row is derived from its profile. */
    @Nonnull
    private static UUID presentationUuid(@Nonnull CompanionRecord record) {
        return record.location().kind() == LocationKind.LIVE && record.currentNpcUuid() != null
                ? record.currentNpcUuid()
                : presentationUuid(new ProfileId(record.profileId()));
    }

    @Nonnull
    static UUID presentationUuid(@Nonnull ProfileId profileId) {
        Objects.requireNonNull(profileId, "Profile ID is required");
        return UUID.nameUUIDFromBytes(
                (PRESENTATION_UUID_NAMESPACE + profileId)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Identity and current record for one rendered roster row. {@code bodyLoaded} is true only
     * for a LIVE member whose body was loaded when the row was read.
     */
    record PanelMember(
            @Nonnull UUID presentationUuid,
            @Nonnull CompanionRecord record,
            boolean bodyLoaded
    ) {
        PanelMember {
            Objects.requireNonNull(presentationUuid, "Presentation UUID is required");
            Objects.requireNonNull(record, "Record is required");
        }

        @Nonnull
        String profileId() {
            return record.profileId().toString();
        }

        @Nonnull
        String roleId() {
            return record.roleId();
        }
    }

    /** Immutable roster read shared by card-data and feature presentation. */
    record PanelSnapshot(
            @Nonnull List<PanelMember> members,
            @Nonnull List<LinkedNpcRecord> records
    ) {
        PanelSnapshot {
            members = List.copyOf(members);
            records = List.copyOf(records);
        }

        static PanelSnapshot empty() {
            return new PanelSnapshot(List.of(), List.of());
        }
    }
}
