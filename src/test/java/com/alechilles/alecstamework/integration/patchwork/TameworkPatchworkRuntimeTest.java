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
                },
                ignored -> new TameworkPatchworkContribution("test")
        );

        runtime.start();
        runtime.start();

        assertEquals(1, bootstraps.get());
        assertEquals(1, service.starts);
        assertEquals(1, service.registrations);
        assertEquals("start,register", service.lifecycle.toString());
    }

    @Test
    void generatedRootIsVisibleOnlyWhileTheServiceIsActive() {
        Path generatedRoot = Path.of("generated");
        TameworkPatchworkRuntime runtime = new TameworkPatchworkRuntime(null, ignored -> new RecordingService(generatedRoot), ignored -> new TameworkPatchworkContribution("test"));

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
        }, ignored -> new TameworkPatchworkContribution("test"));

        runtime.close();

        assertEquals(0, bootstraps.get());
        assertThrows(IllegalStateException.class, runtime::start);
    }

    @Test
    void successfulCloseMakesTheRuntimeTerminal() {
        TameworkPatchworkRuntime runtime = new TameworkPatchworkRuntime(
                null,
                ignored -> new RecordingService(Path.of("generated")),
                ignored -> new TameworkPatchworkContribution("test")
        );
        runtime.start();

        runtime.close();

        assertThrows(IllegalStateException.class, runtime::start);
    }

    @Test
    void activeServiceMustProvideANonNullGeneratedRoot() {
        TameworkPatchworkRuntime runtime = new TameworkPatchworkRuntime(
                null,
                ignored -> new RecordingService(null),
                ignored -> new TameworkPatchworkContribution("test")
        );
        runtime.start();

        assertThrows(NullPointerException.class, runtime::generatedPatchRoot);
    }

    @Test
    void failedStartClosesAndDiscardsTheCandidateService() {
        RecordingService failedService = new RecordingService(Path.of("failed"));
        failedService.startFailure = new IllegalStateException("start failed");
        TameworkPatchworkRuntime runtime = new TameworkPatchworkRuntime(null, ignored -> failedService, ignored -> new TameworkPatchworkContribution("test"));

        assertThrows(IllegalStateException.class, runtime::start);

        assertEquals(1, failedService.starts);
        assertEquals(1, failedService.closes);
        assertThrows(IllegalStateException.class, runtime::generatedPatchRoot);
    }

    @Test
    void failedCloseKeepsTheServiceForRetry() {
        RecordingService service = new RecordingService(Path.of("generated"));
        service.closeFailuresRemaining = 1;
        TameworkPatchworkRuntime runtime = new TameworkPatchworkRuntime(null, ignored -> service, ignored -> new TameworkPatchworkContribution("test"));
        runtime.start();

        assertThrows(IllegalStateException.class, runtime::close);

        assertSame(service.generatedRoot, runtime.generatedPatchRoot());
        runtime.close();
        assertEquals(2, service.closes);
        assertThrows(IllegalStateException.class, runtime::generatedPatchRoot);
    }

    @Test
    void failedRegistrationClosesAndDiscardsTheStartedService() {
        RecordingService service = new RecordingService(Path.of("generated"));
        service.registrationFailure = new IllegalStateException("register failed");
        TameworkPatchworkRuntime runtime = new TameworkPatchworkRuntime(null, ignored -> service, ignored -> new TameworkPatchworkContribution("test"));

        assertThrows(IllegalStateException.class, runtime::start);

        assertEquals(1, service.starts);
        assertEquals(1, service.registrations);
        assertEquals(1, service.closes);
        assertThrows(IllegalStateException.class, runtime::generatedPatchRoot);
    }

    @Test
    void closeContributionBeforeServiceAndRetainsBothHandlesAfterContributionFailure() {
        RecordingService service = new RecordingService(Path.of("generated"));
        service.contributionHandle.closeFailuresRemaining = 1;
        TameworkPatchworkRuntime runtime = new TameworkPatchworkRuntime(null, ignored -> service, ignored -> new TameworkPatchworkContribution("test"));
        runtime.start();

        assertThrows(IllegalStateException.class, runtime::close);
        assertEquals("start,register,contribution-close", service.lifecycle.toString());
        assertSame(service.generatedRoot, runtime.generatedPatchRoot());

        runtime.close();
        assertEquals("start,register,contribution-close,contribution-close,service-close", service.lifecycle.toString());
    }

    private static final class RecordingService implements EmbeddedPatchworkService {
        private final Path generatedRoot;
        private int starts;
        private int closes;
        private int registrations;
        private int closeFailuresRemaining;
        private RuntimeException startFailure;
        private RuntimeException registrationFailure;
        private final StringBuilder lifecycle = new StringBuilder();
        private final RecordingContributionHandle contributionHandle = new RecordingContributionHandle(this);

        private RecordingService(Path generatedRoot) {
            this.generatedRoot = generatedRoot;
        }

        @Override
        public void start() {
            starts++;
            appendLifecycle("start");
            if (startFailure != null) {
                throw startFailure;
            }
        }

        @Override
        public PatchworkContributionHandle registerContribution(PatchworkHostContribution contribution) {
            registrations++;
            appendLifecycle("register");
            if (registrationFailure != null) {
                throw registrationFailure;
            }
            return contributionHandle;
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
            appendLifecycle("service-close");
            if (closeFailuresRemaining > 0) {
                closeFailuresRemaining--;
                throw new IllegalStateException("close failed");
            }
        }

        private void appendLifecycle(String event) {
            if (!lifecycle.isEmpty()) lifecycle.append(',');
            lifecycle.append(event);
        }
    }

    private static final class RecordingContributionHandle implements PatchworkContributionHandle {
        private final RecordingService service;
        private int closeFailuresRemaining;

        private RecordingContributionHandle(RecordingService service) { this.service = service; }

        @Override
        public void close() {
            service.appendLifecycle("contribution-close");
            if (closeFailuresRemaining > 0) {
                closeFailuresRemaining--;
                throw new IllegalStateException("contribution close failed");
            }
        }
    }
}
