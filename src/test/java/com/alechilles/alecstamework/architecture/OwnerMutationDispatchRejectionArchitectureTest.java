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

    @Test
    void allWorldDispatchSitesProvideExactlyOneTerminalRejectionCallback() throws IOException {
        String source = Files.readString(SCHEDULER, StandardCharsets.UTF_8);

        assertEquals(3, occurrences(source, "callbacks.onWorldDispatchRejected("));

        int preparationBranch = source.indexOf(
                "if (failure != null || preparation == null || !preparation.allowed())"
        );
        int preparedBranch = source.indexOf("PreparedCompanionPopulationAdmission prepared =");
        String preparationDispatch = source.substring(preparationBranch, preparedBranch);
        assertTrue(preparationDispatch.contains(
                "callbacks.onWorldDispatchRejected(reason, false, null)"
        ));

        int applyMethod = source.indexOf("private void applyPrepared(", preparedBranch);
        String preparedDispatch = source.substring(preparedBranch, applyMethod);
        int cancellation = preparedDispatch.indexOf(
                "cancelPrepared(prepared, \"owner-mutation-world-unavailable\")"
        );
        int terminalCallback = preparedDispatch.indexOf(
                "callbacks.onWorldDispatchRejected(\"owner-mutation-world-unavailable\", false, null)"
        );
        assertTrue(cancellation >= 0 && terminalCallback > cancellation);

        int completion = source.indexOf("completion.whenComplete", applyMethod);
        int methodEnd = source.indexOf("private void releaseAndDenyBeforePreparation", completion);
        String completionDispatch = source.substring(completion, methodEnd);
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
        assertTrue(source.contains("dispatcher.accept(() -> runStarted(state, task))"));
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
