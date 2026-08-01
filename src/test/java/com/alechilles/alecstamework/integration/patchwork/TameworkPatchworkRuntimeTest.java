package com.alechilles.alecstamework.integration.patchwork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.alechilles.patchwork.embedded.EmbeddedPatchworkService;
import com.alechilles.patchwork.embedded.PatchworkContributionHandle;
import com.alechilles.patchwork.embedded.PatchworkHostContribution;
import com.alechilles.patchwork.embedded.PatchworkReloadObservation;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Regression coverage for Tamework's embedded Patchwork lifecycle ownership. */
class TameworkPatchworkRuntimeTest {
    @Test
    void startBootstrapsAndStartsTheEmbeddedServiceOnlyOnce() {
        RecordingService service = new RecordingService(Path.of("generated"));
        AtomicInteger bootstraps = new AtomicInteger();
        TameworkPatchworkRuntime runtime = new TameworkPatchworkRuntime(
                null,
                ignored -> {
                    bootstraps.incrementAndGet();
                    return service;
                }
        );

        runtime.start();
        runtime.start();

        assertEquals(1, bootstraps.get());
        assertEquals(1, service.starts);
    }

    @Test
    void generatedRootIsVisibleOnlyWhileTheServiceIsActive() {
        Path generatedRoot = Path.of("generated");
        TameworkPatchworkRuntime runtime = new TameworkPatchworkRuntime(null, ignored -> new RecordingService(generatedRoot));

        assertThrows(IllegalStateException.class, runtime::generatedPatchRoot);

        runtime.start();

        assertSame(generatedRoot, runtime.generatedPatchRoot());
        runtime.close();
        assertThrows(IllegalStateException.class, runtime::generatedPatchRoot);
    }

    @Test
    void closeBeforeStartDoesNothing() {
        AtomicInteger bootstraps = new AtomicInteger();
        TameworkPatchworkRuntime runtime = new TameworkPatchworkRuntime(null, ignored -> {
            bootstraps.incrementAndGet();
            return new RecordingService(Path.of("generated"));
        });

        runtime.close();

        assertEquals(0, bootstraps.get());
        assertThrows(IllegalStateException.class, runtime::start);
    }

    @Test
    void successfulCloseMakesTheRuntimeTerminal() {
        TameworkPatchworkRuntime runtime = new TameworkPatchworkRuntime(
                null,
                ignored -> new RecordingService(Path.of("generated"))
        );
        runtime.start();

        runtime.close();

        assertThrows(IllegalStateException.class, runtime::start);
    }

    @Test
    void activeServiceMustProvideANonNullGeneratedRoot() {
        TameworkPatchworkRuntime runtime = new TameworkPatchworkRuntime(
                null,
                ignored -> new RecordingService(null)
        );
        runtime.start();

        assertThrows(NullPointerException.class, runtime::generatedPatchRoot);
    }

    @Test
    void failedStartClosesAndDiscardsTheCandidateService() {
        RecordingService failedService = new RecordingService(Path.of("failed"));
        failedService.startFailure = new IllegalStateException("start failed");
        TameworkPatchworkRuntime runtime = new TameworkPatchworkRuntime(null, ignored -> failedService);

        assertThrows(IllegalStateException.class, runtime::start);

        assertEquals(1, failedService.starts);
        assertEquals(1, failedService.closes);
        assertThrows(IllegalStateException.class, runtime::generatedPatchRoot);
    }

    @Test
    void failedCloseKeepsTheServiceForRetry() {
        RecordingService service = new RecordingService(Path.of("generated"));
        service.closeFailuresRemaining = 1;
        TameworkPatchworkRuntime runtime = new TameworkPatchworkRuntime(null, ignored -> service);
        runtime.start();

        assertThrows(IllegalStateException.class, runtime::close);

        assertSame(service.generatedRoot, runtime.generatedPatchRoot());
        runtime.close();
        assertEquals(2, service.closes);
        assertThrows(IllegalStateException.class, runtime::generatedPatchRoot);
    }

    private static final class RecordingService implements EmbeddedPatchworkService {
        private final Path generatedRoot;
        private int starts;
        private int closes;
        private int closeFailuresRemaining;
        private RuntimeException startFailure;

        private RecordingService(Path generatedRoot) {
            this.generatedRoot = generatedRoot;
        }

        @Override
        public void start() {
            starts++;
            if (startFailure != null) {
                throw startFailure;
            }
        }

        @Override
        public PatchworkContributionHandle registerContribution(PatchworkHostContribution contribution) {
            throw new UnsupportedOperationException("Task 3 owns contribution registration.");
        }

        @Override
        public Path generatedPatchRoot() {
            return generatedRoot;
        }

        @Override
        public void recordObservation(PatchworkReloadObservation observation) {
            throw new UnsupportedOperationException("Task 4 owns observation forwarding.");
        }

        @Override
        public void close() {
            closes++;
            if (closeFailuresRemaining > 0) {
                closeFailuresRemaining--;
                throw new IllegalStateException("close failed");
            }
        }
    }
}
