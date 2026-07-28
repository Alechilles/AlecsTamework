package com.alechilles.alecstamework.persistence.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Contract tests for reads that never flatten storage failure into absence. */
class PersistenceReadResultTest {
    @Test
    void foundCarriesValueAndAllowsRevisionZero() {
        PersistenceReadResult<String> result = PersistenceReadResult.found("profile-a", 0);

        PersistenceReadResult.Found<String> found =
                assertInstanceOf(PersistenceReadResult.Found.class, result);
        assertEquals("profile-a", found.value());
        assertEquals(0, found.revision());
    }

    @Test
    void absentHasNoFailureOrValueChannel() {
        PersistenceReadResult<String> result = PersistenceReadResult.absent();

        assertInstanceOf(PersistenceReadResult.Absent.class, result);
    }

    @Test
    void failedCarriesTypedFailureAndIsNotAbsent() {
        StorageFailure failure = new StorageFailure(
                StorageFailureKind.CORRUPT,
                "profile_row_corrupt",
                "load_profile",
                false,
                new IllegalStateException("invalid lifecycle")
        );

        PersistenceReadResult<String> result = PersistenceReadResult.failed(failure);

        PersistenceReadResult.Failed<String> failed =
                assertInstanceOf(PersistenceReadResult.Failed.class, result);
        assertEquals(failure, failed.failure());
    }

    @Test
    void invalidVariantsAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new PersistenceReadResult.Found<>(null, 0));
        assertThrows(IllegalArgumentException.class, () -> new PersistenceReadResult.Found<>("profile-a", -1));
        assertThrows(IllegalArgumentException.class, () -> new PersistenceReadResult.Failed<>(null));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageFailure(null, "code", "operation", false, null));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageFailure(StorageFailureKind.IO, " ", "operation", true, null));
        assertThrows(IllegalArgumentException.class,
                () -> new StorageFailure(StorageFailureKind.IO, "code", " ", true, null));
    }
}
