package com.alechilles.alecstamework.items;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandLoadedNpcStatusSnapshotServiceTest {
    @Test
    void computePercentClampsToZeroAndHundred() {
        Assertions.assertEquals(0, CommandLoadedNpcStatusSnapshotService.computePercentForTests(-10.0, 0.0, 100.0));
        Assertions.assertEquals(50, CommandLoadedNpcStatusSnapshotService.computePercentForTests(50.0, 0.0, 100.0));
        Assertions.assertEquals(100, CommandLoadedNpcStatusSnapshotService.computePercentForTests(150.0, 0.0, 100.0));
    }

    @Test
    void formatSignedUsesExplicitPositiveSign() {
        Assertions.assertEquals("+1.25", CommandLoadedNpcStatusSnapshotService.formatSignedForTests(1.25));
        Assertions.assertEquals("-0.50", CommandLoadedNpcStatusSnapshotService.formatSignedForTests(-0.5));
    }
}
