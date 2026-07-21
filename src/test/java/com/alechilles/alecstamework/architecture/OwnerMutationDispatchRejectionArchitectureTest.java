package com.alechilles.alecstamework.architecture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards state-independent terminal callbacks when a world rejects deferred mutation work. */
class OwnerMutationDispatchRejectionArchitectureTest {
    private static final Path SCHEDULER = Paths.get(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "ownership", "OwnerMutationScheduler.java"
    );
    private static final Path APPLIER = Paths.get(
            "src", "main", "java", "com", "alechilles", "alecstamework",
            "ownership", "OwnerMutationPreparedApplier.java"
    );

    @Test
    void allWorldDispatchSitesProvideExactlyOneTerminalRejectionCallback() throws IOException {
        String source = Files.readString(SCHEDULER, StandardCharsets.UTF_8);
        String applier = Files.readString(APPLIER, StandardCharsets.UTF_8);

        assertEquals(3, occurrences(source, "callbacks.onWorldDispatchRejected(")
                + occurrences(applier, "callbacks.onWorldDispatchRejected("));

        int preparationBranch = source.indexOf(
                "if (failure != null || preparation == null || !preparation.allowed())"
        );
        int preparedBranch = source.indexOf("PreparedCompanionPopulationAdmission prepared =");
        String preparationDispatch = source.substring(preparationBranch, preparedBranch);
        assertTrue(preparationDispatch.contains(
                "callbacks.onWorldDispatchRejected(reason, false, null)"
        ));

        int releaseHelper = source.indexOf(
                "private void releaseAndDenyBeforePreparation(", preparedBranch);
        String preparedDispatch = source.substring(preparedBranch, releaseHelper);
        int cancellation = preparedDispatch.indexOf(
                "cancelPrepared(prepared, \"owner-mutation-world-unavailable\")"
        );
        int terminalCallback = preparedDispatch.indexOf(
                "callbacks.onWorldDispatchRejected(\"owner-mutation-world-unavailable\", false, null)"
        );
        assertTrue(cancellation >= 0 && terminalCallback > cancellation);

        int completion = applier.indexOf("completion.whenComplete");
        int methodEnd = applier.indexOf("private void cancelPrepared", completion);
        String completionDispatch = applier.substring(completion, methodEnd);
        assertTrue(completionDispatch.contains(
                "\"owner-mutation-world-unavailable\", true, commit"
        ));
    }

    @Test
    void dispatchStartWatchdogUsesReservationLeaseAndAtomicTerminalStates() throws IOException {
        Path dispatcher = Paths.get(
                "src", "main", "java", "com", "alechilles", "alecstamework",
                "runtime", "dispatch", "LeaseBoundWorldDispatcher.java"
        );
        String source = Files.readString(dispatcher, StandardCharsets.UTF_8);

        assertTrue(source.contains(
                "OwnerPopulationTransitionRequest.DEFAULT_LEASE_DURATION.toNanos()"
        ));
        assertTrue(source.contains("AtomicReference<DispatchState>"));
        assertTrue(source.contains(
                "compareAndSet(DispatchState.PENDING, DispatchState.STARTED)"
        ));
        assertTrue(source.contains(
                "compareAndSet(DispatchState.PENDING, DispatchState.REJECTED)"
        ));
        assertTrue(source.contains(
                "dispatcher.accept(() -> runStarted(state, pendingTask, pendingRejection))"
        ));
        assertTrue(source.contains("pendingTask.set(null)"));
        assertTrue(source.contains("pendingRejection.getAndSet(null)"));
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
