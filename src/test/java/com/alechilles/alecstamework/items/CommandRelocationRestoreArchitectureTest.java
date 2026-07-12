package com.alechilles.alecstamework.items;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards conservative population handling when cross-world source restoration is ambiguous. */
class CommandRelocationRestoreArchitectureTest {

    @Test
    void addExceptionOnlyCountsAsRestoredWhenUuidIsActuallyPresent() throws Exception {
        String access = source("CommandRelocationWorldAccess.java");

        assertTrue(access.contains("boolean restoreSourceEntity("),
                "Source restoration must return a verified outcome.");
        int catchStart = access.indexOf("catch (Exception | LinkageError exception)");
        int diagnostic = access.indexOf("diagnostic.accept(", catchStart);
        String addException = access.substring(catchStart, diagnostic);
        assertTrue(addException.contains("isEntityPresent(sourceWorld, npcUuid)")
                        && addException.contains("return true;"),
                "An add exception is successful only when the planned UUID is live afterward.");
    }

    @Test
    void invalidAddRefRequiresUuidPresenceAndFailedRestoreCommitsAsLost() throws Exception {
        String access = source("CommandRelocationWorldAccess.java");
        String service = source("CommandNpcRelocationService.java");

        assertTrue(access.contains(": isEntityPresent(sourceWorld, npcUuid);"),
                "A valid or invalid add ref must still be verified through UUID presence.");
        assertTrue(service.contains("if (!worldAccess.restoreSourceEntity(")
                        && service.contains("commitUnconfirmedRelocationAsLost("),
                "Failed/ambiguous restoration must retain capacity and terminate through LOST commit.");
        int failedRestore = service.indexOf("if (!worldAccess.restoreSourceEntity(");
        assertTrue(service.indexOf("pending.markPhysicalMutationCompensated()", failedRestore)
                        > failedRestore,
                "APPLYING may be canceled/retried only after verified source compensation.");
    }

    private static String source(String fileName) throws Exception {
        return Files.readString(Path.of(
                "src", "main", "java", "com", "alechilles", "alecstamework", "items", fileName
        ), StandardCharsets.UTF_8);
    }
}
