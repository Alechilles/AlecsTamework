package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopVanillaProjectionAdoptionGateway.AdoptionRequest;
import com.alechilles.alecstamework.npc.components.TameworkProjectionIdentityComponent;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopAuthorityKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Marker and ECS-threading contract for in-place deployed projection adoption. */
class HytaleManagedCoopVanillaProjectionAdoptionGatewayTest {
    private static final UUID SOURCE = new UUID(0L, 201L);
    private static final ManagedCoopAuthorityKey AUTHORITY =
            new ManagedCoopAuthorityKey("world-a", 4, 5, 6);

    @Test
    void createsAnExactPersistentImportAdoptionMarker() {
        AdoptionRequest request = request();

        TameworkProjectionIdentityComponent marker =
                HytaleManagedCoopVanillaProjectionAdoptionGateway.marker(request);

        assertEquals("profile-a", marker.getProfileId());
        assertEquals(request.operationId(), marker.getOperationId());
        assertEquals(TameworkProjectionIdentityComponent.KIND_MANAGED_COOP_IMPORT_ADOPTION,
                marker.getProjectionKind());
        assertEquals(AUTHORITY.slotKey(2), marker.getSlotKey());
        assertEquals(SOURCE, marker.getSourceNpcUuid());
        assertEquals(0L, marker.getGeneration());
        assertTrue(HytaleManagedCoopVanillaProjectionAdoptionGateway.matches(marker, request));

        marker.setSourceNpcUuid(new UUID(0L, 202L));
        assertFalse(HytaleManagedCoopVanillaProjectionAdoptionGateway.matches(marker, request));
    }

    @Test
    void synchronousAdoptOnlyQueuesImmutableWorldWork() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/items/"
                        + "HytaleManagedCoopVanillaProjectionAdoptionGateway.java"));
        String adopt = methodBody(
                source,
                "public AdoptionResult adopt(@Nonnull AdoptionRequest request)",
                "\n    private void completeOnWorldThread");

        assertTrue(adopt.contains("world.execute(() -> completeOnWorldThread(created))"));
        assertFalse(adopt.contains("putComponent("));
        assertFalse(adopt.contains("removeComponent("));
        assertFalse(adopt.contains("removeComponentIfExists("));

        int markerInstall = source.indexOf(
                "resolution.store().putComponent(resolution.reference(), markerType, expected)");
        int vanillaDetach = source.indexOf("resolution.store().removeComponent(");
        assertTrue(markerInstall >= 0 && vanillaDetach > markerInstall,
                "the persistent adoption marker must be installed before vanilla detachment");
        assertFalse(source.contains("spawnEntity("));
        assertFalse(source.contains("removeEntity("));
        assertFalse(source.contains("setToDespawn("));
    }

    private AdoptionRequest request() {
        return new AdoptionRequest(
                AUTHORITY,
                "coop_chicken",
                "managed-coop-import:" + "a".repeat(64),
                "managed-coop-import-source:" + "b".repeat(64),
                "c".repeat(64),
                "managed-coop-import-operation:" + "d".repeat(64),
                "resident-a",
                "profile-a",
                2,
                SOURCE,
                0L,
                "e".repeat(64));
    }

    private String methodBody(String source, String startToken, String endToken) {
        int start = source.indexOf(startToken);
        int end = source.indexOf(endToken, start);
        assertTrue(start >= 0 && end > start);
        return source.substring(start, end);
    }
}
