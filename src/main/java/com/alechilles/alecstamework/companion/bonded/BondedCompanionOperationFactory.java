package com.alechilles.alecstamework.companion.bonded;

/** Builds canonical operation receipts from transition boundary inputs. */
final class BondedCompanionOperationFactory {
    private final BondedCompanionSnapshotCodec snapshots =
            new BondedCompanionSnapshotCodec();

    BondedCompanionOperationReceipt creation(
            BondedCompanionTransitionService.CreationRequest request,
            BondedCompanionOperationReceipt.Action action,
            String familyId
    ) {
        if (request.familyId() == null) {
            return BondedCompanionOperationReceipt.of(
                    request.operationId(), action, request.ownerUuid(), -1L,
                    request.expectedPolicyRevision(), request.nowMs(),
                    request.rosterId(), request.profileId(), request.roleId(),
                    snapshots.encode(request.snapshot())
            );
        }
        return BondedCompanionOperationReceipt.of(
                request.operationId(), action, request.ownerUuid(), -1L,
                request.expectedPolicyRevision(), request.nowMs(),
                request.rosterId(), "family:" + familyId,
                request.profileId(), request.roleId(),
                snapshots.encode(request.snapshot())
        );
    }

    BondedCompanionOperationReceipt mutation(
            BondedCompanionTransitionService.MutationRequest request,
            BondedCompanionOperationReceipt.Action action,
            String... payload
    ) {
        return BondedCompanionOperationReceipt.of(
                request.operationId(), action, request.actorOwnerUuid(),
                request.expectedRevision(), request.expectedPolicyRevision(),
                request.nowMs(), payload
        );
    }

    String snapshotPayload(BondedCompanionSnapshot snapshot) {
        return snapshots.encode(snapshot);
    }
}
