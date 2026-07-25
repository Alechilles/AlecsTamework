package com.alechilles.alecstamework.companion.provisioning.runtime;

import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.alechilles.alecstamework.items.persistence.TameworkSnapshotCodecs;
import com.alechilles.alecstamework.npc.components.TameworkOwnerComponent;
import com.alechilles.alecstamework.npc.components.TameworkTamedComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Full-state identity validation for crash-self-sufficient activation. */
class ProvisioningActivationSnapshotResolverTest {
    private final SnapshotCodecRegistry codecs =
            TameworkSnapshotCodecs.create();
    private final ProvisioningActivationSnapshotResolver resolver =
            new ProvisioningActivationSnapshotResolver(codecs);

    @Test
    void acceptsExactAliasRoleOwnerAndInitialState() {
        ProvisioningActivationRequest request = request(state(
                null, "Mini", null, true, -4_000
        ));

        assertInstanceOf(
                SnapshotDecodeResult.Decoded.class,
                resolver.resolve(request)
        );
    }

    @Test
    void rejectsEveryMismatchedInitialProjectionAuthority() {
        assertFailed(state(
                java.util.UUID.randomUUID(),
                "Mini",
                null,
                true,
                -4_000
        ));
        assertFailed(state(null, "Other", null, true, -4_000));
        assertFailed(state(
                null,
                "Mini",
                java.util.UUID.randomUUID(),
                true,
                -4_000
        ));
        assertFailed(state(null, "Mini", null, false, -4_000));
        assertFailed(state(null, "Mini", null, true, -3_999));
    }

    @Test
    void rejectsRoleAssetIdWithDifferentCase() {
        SnapshotDecodeResult.Failed<?> failed = assertInstanceOf(
                SnapshotDecodeResult.Failed.class,
                resolver.resolve(request(state(
                        null, "mini", null, true, -4_000
                )))
        );

        assertEquals(
                "provisioning_activation_projection_state_mismatch",
                failed.code()
        );
    }

    private void assertFailed(CoopResidentStateSnapshot state) {
        assertInstanceOf(
                SnapshotDecodeResult.Failed.class,
                resolver.resolve(request(state))
        );
    }

    private ProvisioningActivationRequest request(
            CoopResidentStateSnapshot state
    ) {
        ProvisioningActivationRequest template =
                ProvisioningActivationWorldTestFixture.request();
        return new ProvisioningActivationRequest(
                template.origin(),
                template.groupAdmission(),
                template.targetAlias(),
                template.expectedRoleId(),
                codecs.encode(
                        CompanionFullStateProjection.KIND,
                        CompanionFullStateProjection.VERSION,
                        CoopResidentStateSnapshot.class,
                        state
                ),
                template.placement(),
                template.spawnReceiptKey(),
                template.timedActivation(),
                template.requestedAtMs()
        );
    }

    private CoopResidentStateSnapshot state(
            java.util.UUID alias,
            String roleId,
            java.util.UUID owner,
            boolean tamed,
            long capturedAtMs
    ) {
        ProvisioningActivationRequest request =
                ProvisioningActivationWorldTestFixture.request();
        return new CoopResidentStateSnapshot(
                alias == null
                        ? request.targetAlias().value()
                        : alias,
                null,
                -1,
                roleId,
                null,
                new TameworkOwnerComponent(
                        owner == null
                                ? request.groupAdmission().before()
                                .ownerId().value()
                                : owner,
                        "Owner"
                ),
                new TameworkTamedComponent(tamed),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                capturedAtMs
        );
    }
}
