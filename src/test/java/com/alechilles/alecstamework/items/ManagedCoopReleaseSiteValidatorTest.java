package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.ReleaseSite;
import com.alechilles.alecstamework.items.ManagedCoopRuntimeOperationDispatcher.ReleaseSitePolicy;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityState;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Queued release-site race guard for exact managed and confirmed-removed projections. */
class ManagedCoopReleaseSiteValidatorTest {
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world", 1, 2, 3);

    @Test
    void exactManagedSiteIsRevalidatedAndUsesCurrentRotation() {
        ManagedCoopReleaseSiteValidator validator = new ManagedCoopReleaseSiteValidator();

        var validation = validator.validate(normalSite(), exactManaged(9));

        assertTrue(validation.allowed());
        assertEquals(9, validation.currentRotationIndex());
    }

    @Test
    void normalReleaseCannotProjectAfterBlockWasRemoved() {
        ManagedCoopReleaseSiteValidator validator = new ManagedCoopReleaseSiteValidator();

        var validation = validator.validate(normalSite(), removed());

        assertFalse(validation.allowed());
        assertEquals("managed_coop_release_removed_site_not_authorized", validation.detail());
    }

    @Test
    void removedReleaseRequiresCurrentDurableDisabledAuthority() {
        ManagedCoopResidentIndex residents = new ManagedCoopResidentIndex();
        AtomicBoolean trusted = new AtomicBoolean(true);
        rebuild(residents, AuthorityState.DISABLED);
        ManagedCoopReleaseSiteValidator validator =
                new ManagedCoopReleaseSiteValidator(residents, trusted::get);

        assertTrue(validator.validate(removedSite(), removed()).allowed());

        rebuild(residents, AuthorityState.TWORK_MANAGED);
        assertFalse(validator.validate(removedSite(), removed()).allowed());

        rebuild(residents, AuthorityState.DISABLED);
        trusted.set(false);
        assertFalse(validator.validate(removedSite(), removed()).allowed());
    }

    @Test
    void matchingBlockMissingComponentRemainsBlockedEvenWhenAuthorityDisabled() {
        ManagedCoopResidentIndex residents = new ManagedCoopResidentIndex();
        rebuild(residents, AuthorityState.DISABLED);
        ManagedCoopReleaseSiteValidator validator =
                new ManagedCoopReleaseSiteValidator(residents, () -> true);
        var physical = new ManagedCoopRemovalEvidence.Result(
                ManagedCoopRemovalEvidence.Status.DEFERRED_MATCHING_BLOCK_COMPONENT_MISSING,
                0, "matching_missing");

        assertFalse(validator.validate(removedSite(), physical).allowed());
    }

    private static ReleaseSite normalSite() {
        return new ReleaseSite(
                "world", "coop_chicken", 1, 2, 3, 4,
                0.0, 0.0, 3.0, ReleaseSitePolicy.EXACT_MANAGED_COOP);
    }

    private static ReleaseSite removedSite() {
        return new ReleaseSite(
                "world", "coop_chicken", 1, 2, 3, 0,
                0.0, 0.0, 3.0,
                ReleaseSitePolicy.EXACT_MANAGED_OR_DISABLED_REMOVAL);
    }

    private static ManagedCoopRemovalEvidence.Result exactManaged(int rotation) {
        return new ManagedCoopRemovalEvidence.Result(
                ManagedCoopRemovalEvidence.Status.EXACT_MANAGED_COOP,
                rotation, null);
    }

    private static ManagedCoopRemovalEvidence.Result removed() {
        return new ManagedCoopRemovalEvidence.Result(
                ManagedCoopRemovalEvidence.Status.CONFIRMED_REMOVED,
                0, "confirmed");
    }

    private static void rebuild(ManagedCoopResidentIndex residents, AuthorityState state) {
        AuthorityRecord authority = new AuthorityRecord(
                AUTHORITY.authorityId(), AUTHORITY, "coop_chicken", state,
                true, 1, -100L, -90L, null);
        assertTrue(residents.rebuild(
                ManagedCoopReadResult.loaded(List.of(authority)),
                ManagedCoopReadResult.loaded(List.of())).rebuilt());
    }
}
