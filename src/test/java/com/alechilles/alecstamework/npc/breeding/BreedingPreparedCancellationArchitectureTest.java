package com.alechilles.alecstamework.npc.breeding;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Static guards for the two defensive failure branches that are not injectable at runtime. */
class BreedingPreparedCancellationArchitectureTest {
    @Test
    void registrationFailureFailsGateBeforeCandidateCancellationStarts() throws Exception {
        String handoff = read("../actions/BreedingPreparedPairingHandoffService.java");
        int failure = handoff.indexOf("catch (RuntimeException | LinkageError registrationFailure)");
        int end = handoff.indexOf("return;", failure);
        String branch = handoff.substring(failure, end);

        int failGate = branch.indexOf("failPreparation(storeScope, jobId)");
        int cancelCandidate = branch.indexOf("cancelPrepared(");
        assertTrue(failGate >= 0 && cancelCandidate > failGate);
        assertFalse(branch.contains("finishPreparation(storeScope, jobId)"));
    }

    @Test
    void synchronousPreparationFailuresPermanentlyFailTheGate() throws Exception {
        String handoff = read("../actions/BreedingPreparedPairingHandoffService.java");
        int preparation = handoff.indexOf("completion = populationService.prepareAsync(");
        int dispatch = handoff.indexOf("completion.whenComplete(", preparation);
        String failureBranches = handoff.substring(preparation, dispatch);

        assertTrue(occurrences(
                failureBranches, "failPreparation(storeScope, jobId)"
        ) >= 2);
        assertFalse(failureBranches.contains("finishPreparation(storeScope, jobId)"));
    }

    @Test
    void synchronousCommitStartFailuresCompleteWaitingCancellationFalse() throws Exception {
        String entry = read("BreedingPreparedPopulationEntry.java");
        int commit = entry.indexOf("CompletableFuture<CompanionPopulationCommitResult> commit(");
        int finishCommit = entry.indexOf("private CompanionPopulationCommitResult finishCommit(");
        String commitBody = entry.substring(commit, finishCommit);
        int retainCalls = occurrences(commitBody, "retainAmbiguous(index,");
        int retain = entry.indexOf("void retainAmbiguous(");
        int states = entry.indexOf("synchronized List<", retain);
        String retainBody = entry.substring(retain, states);

        assertTrue(commitBody.contains("terminalCompletions.set(index, terminal)"));
        assertTrue(retainCalls >= 2);
        assertTrue(retainBody.contains("terminal.complete(false)"));
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        int cursor = 0;
        while ((cursor = source.indexOf(needle, cursor)) >= 0) {
            count++;
            cursor += needle.length();
        }
        return count;
    }

    private static String read(String relative) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/npc/breeding", relative
        )).replace("\r\n", "\n");
    }
}
