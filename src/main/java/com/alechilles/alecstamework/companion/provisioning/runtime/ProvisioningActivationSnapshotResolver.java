package com.alechilles.alecstamework.companion.provisioning.runtime;

import com.alechilles.alecstamework.companion.provisioning.ProvisioningActivationRequest;
import com.alechilles.alecstamework.companion.snapshot.CompanionFullStateProjection;
import com.alechilles.alecstamework.companion.snapshot.SnapshotCodecRegistry;
import com.alechilles.alecstamework.companion.snapshot.SnapshotDecodeResult;
import com.alechilles.alecstamework.items.CoopResidentStateSnapshotService.CoopResidentStateSnapshot;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.Objects;
import javax.annotation.Nonnull;

/** Decodes and validates the self-contained initial companion projection. */
final class ProvisioningActivationSnapshotResolver {
    private final SnapshotCodecRegistry snapshotCodecs;

    ProvisioningActivationSnapshotResolver(
            @Nonnull SnapshotCodecRegistry snapshotCodecs
    ) {
        this.snapshotCodecs = Objects.requireNonNull(
                snapshotCodecs, "snapshotCodecs"
        );
    }

    @Nonnull
    SnapshotDecodeResult<CoopResidentStateSnapshot> resolve(
            @Nonnull ProvisioningActivationRequest request
    ) {
        if (request == null
                || !CompanionFullStateProjection.KIND.equals(
                request.fullState().kind()
        )
                || request.fullState().payloadVersion()
                != CompanionFullStateProjection.VERSION) {
            return failed(
                    SnapshotDecodeResult.Failure.TYPE_MISMATCH,
                    "provisioning_activation_projection_kind_mismatch"
            );
        }
        SnapshotDecodeResult<CoopResidentStateSnapshot> decoded =
                snapshotCodecs.decode(
                        request.fullState(),
                        CoopResidentStateSnapshot.class
                );
        if (!(decoded instanceof SnapshotDecodeResult.Decoded<?> found)) {
            return decoded;
        }
        CoopResidentStateSnapshot state =
                (CoopResidentStateSnapshot) found.value();
        return exact(request, state)
                ? decoded
                : failed(
                SnapshotDecodeResult.Failure.DECODE_FAILED,
                "provisioning_activation_projection_state_mismatch"
        );
    }

    private boolean exact(
            ProvisioningActivationRequest request,
            CoopResidentStateSnapshot state
    ) {
        return state != null
                && request.targetAlias().value().equals(state.npcUuid())
                && state.roleId() != null
                && exactEncodedRole(request)
                && state.owner() != null
                && request.groupAdmission().before().ownerId().value()
                        .equals(state.owner().getOwnerId())
                && state.tamed() != null
                && state.tamed().isTamed()
                && state.coopId() == null
                && state.residentSlot() < 0
                && state.capturedAtMs() == request.requestedAtMs();
    }

    private boolean exactEncodedRole(
            ProvisioningActivationRequest request
    ) {
        try {
            JsonElement root = JsonParser.parseString(
                    request.fullState().payloadJson()
            );
            if (!root.isJsonObject()) {
                return false;
            }
            JsonElement role = root.getAsJsonObject().get("roleId");
            return role != null
                    && role.isJsonPrimitive()
                    && role.getAsJsonPrimitive().isString()
                    && request.expectedRoleId().equals(
                            role.getAsString()
                    );
        } catch (RuntimeException invalidJson) {
            return false;
        }
    }

    private SnapshotDecodeResult.Failed<CoopResidentStateSnapshot>
    failed(
            SnapshotDecodeResult.Failure failure,
            String code
    ) {
        return new SnapshotDecodeResult.Failed<>(
                failure, code, null
        );
    }
}
