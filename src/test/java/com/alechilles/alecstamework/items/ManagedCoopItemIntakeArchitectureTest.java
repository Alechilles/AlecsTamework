package com.alechilles.alecstamework.items;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the Hytale 0.5.6 interaction cutover and vanilla-authority boundary. */
class ManagedCoopItemIntakeArchitectureTest {
    @Test
    void customCaptureCrateCutsManagedPlacementOffBeforeVanillaMutation() throws Exception {
        String source = read("src/main/java/com/alechilles/alecstamework/interactions/"
                + "TameworkManagedCoopCaptureCrateInteraction.java");

        assertTrue(source.contains("extends UseCaptureCrateInteraction"));
        assertEquals(2, occurrences(source, "super.interactWithBlock("));
        assertTrue(source.contains("if (managed == null)"));
        assertTrue(source.contains("if (hasTameworkItemEvidence(held))"));
        assertTrue(source.contains("if (hasRetirementReceipt(held))"));
        assertTrue(source.indexOf("if (managed == null)")
                < source.indexOf("handleManaged("));
        assertFalse(source.contains("tryPutResident"));
        assertFalse(source.contains("CoopFeatureHandler"));
        assertFalse(source.contains(
                "import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent"));
    }

    @Test
    void filledManagedEnvelopeReachesBlockDispatchButNotVanillaMutation() throws Exception {
        String source = read("src/main/java/com/alechilles/alecstamework/interactions/"
                + "TameworkManagedCoopCaptureCrateInteraction.java");

        int tickReceiptGuard = source.indexOf("if (hasRetirementReceipt(item))");
        int envelopeBranch = source.indexOf("if (hasManagedEnvelope(item))", tickReceiptGuard);
        int metadataValidation = source.indexOf(
                "if (hasDecodableVanillaCapturedResident(item))", envelopeBranch);
        int blockDispatch = source.indexOf(
                "super.tick0(true, time, type, context, cooldownHandler);", metadataValidation);
        int targetLookup = source.indexOf("context.getTargetEntity()", blockDispatch);
        int unmanagedEvidenceGuard = source.indexOf("if (hasTameworkItemEvidence(held))");
        int managedHandler = source.indexOf("handleManaged(", unmanagedEvidenceGuard);

        assertTrue(tickReceiptGuard >= 0);
        assertTrue(envelopeBranch > tickReceiptGuard);
        assertTrue(metadataValidation > envelopeBranch);
        assertTrue(blockDispatch > metadataValidation);
        assertTrue(targetLookup > blockDispatch);
        assertTrue(unmanagedEvidenceGuard >= 0);
        assertTrue(managedHandler > unmanagedEvidenceGuard);
        assertTrue(source.contains("ManagedCoopItemRetirementReceiptCodec.METADATA_KEY"));
        assertTrue(source.contains("return false;"));
    }

    @Test
    void captureAuthoringWritesBothMetadataLayersBeforeRemovingNpc() throws Exception {
        String interaction = read("src/main/java/com/alechilles/alecstamework/interactions/"
                + "TameworkManagedCoopCaptureCrateInteraction.java");
        String authoring = read("src/main/java/com/alechilles/alecstamework/items/"
                + "ManagedCoopCapturedItemAuthoringService.java");

        int tick = interaction.indexOf("protected void tick0(");
        int vanillaMetadata = interaction.indexOf(".withMetadata(CapturedNPCMetadata.KEYED_CODEC");
        int managedEnvelope = interaction.indexOf(
                "ManagedCoopCapturedItemEnvelopeCodec.METADATA_KEY", vanillaMetadata);
        int replacement = interaction.indexOf("replaceItemStackInSlot(", managedEnvelope);
        int succeeded = interaction.indexOf(".succeeded()", replacement);
        int removal = interaction.indexOf("commandBuffer.removeEntity(", succeeded);
        int capturedOutcome = interaction.indexOf("captured = true;", removal);
        int fenceCompletion = interaction.indexOf(
                "prepared.completeCapture(captured);", capturedOutcome);

        assertTrue(tick >= 0);
        assertTrue(vanillaMetadata > tick);
        assertTrue(managedEnvelope > vanillaMetadata);
        assertTrue(replacement > managedEnvelope);
        assertTrue(succeeded > replacement);
        assertTrue(removal > succeeded);
        assertTrue(capturedOutcome > removal);
        assertTrue(fenceCompletion > capturedOutcome);
        assertEquals(2, occurrences(interaction, "prepared.completeCapture(false);"));
        assertTrue(interaction.contains("} finally {"));
        assertTrue(authoring.indexOf("cancelThenCaptureSnapshotRetainingFence(")
                < authoring.indexOf("captureSnapshotForPersistence("));
        assertTrue(authoring.contains("CancellationReason.CAPTURE_CRATE"));
        assertTrue(authoring.contains("handoff.cancellation().safeToCapture()"));
        assertTrue(authoring.contains("breeding_capture_cancellation_not_durable"));
    }

    @Test
    void asyncInventoryWorkReResolvesStableIdsOnWorldThread() throws Exception {
        String source = read("src/main/java/com/alechilles/alecstamework/items/"
                + "HytaleManagedCoopItemInteractionSession.java");

        assertTrue(source.contains("world.execute("));
        assertTrue(source.contains("world.getEntityRef(playerUuid)"));
        assertTrue(source.contains("RETIREMENT_RECEIPT_METADATA_KEY"));
        assertFalse(source.contains("PlayerRef.getComponent"));
        assertFalse(source.contains("Universe"));
        assertFalse(source.contains("tryPutResident"));
    }

    @Test
    void allNewRuntimeClassesStayBelowGodClassThreshold() throws Exception {
        String[] files = {
                "ManagedCoopCapturedItemEnvelopeCodec.java",
                "ManagedCoopCaptureSourceEvidence.java",
                "ManagedCoopCapturedItemAttemptFactory.java",
                "ManagedCoopItemCaptureFinalizer.java",
                "ManagedCoopItemIntakeHandler.java",
                "HytaleManagedCoopItemInteractionSession.java",
                "HytaleManagedCoopItemReceiptGateway.java",
                "HytaleManagedCoopItemTargetResolver.java",
                "ManagedCoopItemIntakeRuntime.java",
                "ManagedCoopItemRetirementReceiptCodec.java",
                "ManagedCoopItemReceiptVerifier.java",
                "ManagedCoopItemCaptureRecoveryService.java",
                "ManagedCoopCapturedItemAuthoringService.java"
        };
        for (String file : files) {
            long lines = Files.lines(Path.of(
                    "src/main/java/com/alechilles/alecstamework/items", file)).count();
            assertTrue(lines <= 500, file + " has " + lines + " lines");
        }
        String[] interactions = {
                "TameworkManagedCoopCaptureCrateInteraction.java",
                "HytaleCapturedNpcMetadataFactory.java"
        };
        for (String file : interactions) {
            long lines = Files.lines(Path.of(
                    "src/main/java/com/alechilles/alecstamework/interactions", file)).count();
            assertTrue(lines <= 500, file + " has " + lines + " lines");
        }
    }

    private String read(String file) throws Exception {
        return Files.readString(Path.of(file)).replace("\r\n", "\n");
    }

    private int occurrences(String source, String needle) {
        int count = 0;
        int cursor = 0;
        while ((cursor = source.indexOf(needle, cursor)) >= 0) {
            count++;
            cursor += needle.length();
        }
        return count;
    }
}
