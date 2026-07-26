package com.alechilles.alecstamework.items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Regression coverage for bonded-revive payment admission and recovery. */
class BondedCompanionChargeCoordinatorTest {

    /** Protects against the former replaceAll path debiting slot one before slot two contended. */
    @Test
    void multiSlotContentionLeavesEverySlotUntouchedAndReleasesPreparation() {
        FakeAttempt attempt = new FakeAttempt(1, 1);
        attempt.contendSecondSlot = true;

        BondedCompanionChargeCoordinator.Outcome outcome =
                new BondedCompanionChargeCoordinator().consume(attempt);

        assertEquals(BondedCompanionChargeCoordinator.Status.UNAVAILABLE,
                outcome.status());
        assertEquals(1, attempt.firstSlot);
        assertEquals(0, attempt.secondSlot);
        assertEquals(1, attempt.contendingItem);
        assertEquals(BondedCompanionChargeCoordinator.State.ABSENT,
                attempt.state);
        assertFalse(attempt.refundCalled);
    }

    /** Protects against discarding pending evidence when charged receipt persistence fails. */
    @Test
    void chargedTransitionFailureRetainsRecoveryAndNeverDebitsAgain() {
        FakeAttempt attempt = new FakeAttempt(1, 1);
        attempt.failMarkCharged = true;
        attempt.failFirstRefund = true;
        BondedCompanionChargeCoordinator coordinator =
                new BondedCompanionChargeCoordinator();

        BondedCompanionChargeCoordinator.Outcome first =
                coordinator.consume(attempt);

        assertEquals(BondedCompanionChargeCoordinator.Status.RECOVERY_PENDING,
                first.status());
        assertEquals(0, attempt.firstSlot + attempt.secondSlot);
        assertEquals(1, attempt.debitCalls);
        assertEquals(BondedCompanionChargeCoordinator.State.DEBITED,
                attempt.state);

        BondedCompanionChargeCoordinator.Outcome retry =
                coordinator.consume(attempt);

        assertEquals(BondedCompanionChargeCoordinator.Status.UNAVAILABLE,
                retry.status());
        assertEquals(2, attempt.firstSlot + attempt.secondSlot);
        assertEquals(1, attempt.debitCalls);
        assertEquals(1, attempt.refundApplications);
        assertEquals(BondedCompanionChargeCoordinator.State.COMPENSATED,
                attempt.state);
        assertTrue(attempt.refundCalled);

        BondedCompanionChargeCoordinator.Outcome laterRetry =
                coordinator.consume(attempt);
        assertEquals(BondedCompanionChargeCoordinator.Status.UNAVAILABLE,
                laterRetry.status());
        assertEquals(2, attempt.firstSlot + attempt.secondSlot);
        assertEquals(1, attempt.debitCalls);
        assertEquals(1, attempt.refundApplications);
    }

    /** Protects against refunding a full recipe after an unexpected one-slot partial debit. */
    @Test
    void unexpectedPartialDebitCompensatesOnlyTheMutatedSlot() {
        FakeAttempt attempt = new FakeAttempt(1, 1);
        attempt.partialDebit = true;

        BondedCompanionChargeCoordinator.Outcome outcome =
                new BondedCompanionChargeCoordinator().consume(attempt);

        assertEquals(BondedCompanionChargeCoordinator.Status.UNAVAILABLE,
                outcome.status());
        assertEquals(1, attempt.firstSlot);
        assertEquals(1, attempt.secondSlot);
        assertEquals(1, attempt.debitCalls);
        assertEquals(1, attempt.refundApplications);
        assertEquals(BondedCompanionChargeCoordinator.State.COMPENSATED,
                attempt.state);
    }

    private static final class FakeAttempt implements
            BondedCompanionChargeCoordinator.Attempt {
        private int firstSlot;
        private int secondSlot;
        private int contendingItem;
        private int debitCalls;
        private int debitedQuantity;
        private int refundApplications;
        private boolean contendSecondSlot;
        private boolean partialDebit;
        private boolean failMarkCharged;
        private boolean failFirstRefund;
        private boolean refundCalled;
        private BondedCompanionChargeCoordinator.State state =
                BondedCompanionChargeCoordinator.State.ABSENT;

        private FakeAttempt(int firstSlot, int secondSlot) {
            this.firstSlot = firstSlot;
            this.secondSlot = secondSlot;
        }

        @Override
        public BondedCompanionChargeCoordinator.State state() {
            return state;
        }

        @Override
        public boolean installPending() {
            if (state != BondedCompanionChargeCoordinator.State.ABSENT) {
                return state == BondedCompanionChargeCoordinator.State.PREPARED;
            }
            state = BondedCompanionChargeCoordinator.State.PREPARED;
            return true;
        }

        @Override
        public BondedCompanionChargeCoordinator.DebitResult debitAtomically() {
            debitCalls++;
            if (contendSecondSlot) {
                secondSlot--;
                contendingItem++;
            }
            if (firstSlot + secondSlot < 2) {
                return BondedCompanionChargeCoordinator.DebitResult.NONE;
            }
            if (partialDebit) {
                firstSlot--;
                debitedQuantity = 1;
                state = BondedCompanionChargeCoordinator.State.DEBITED;
                return BondedCompanionChargeCoordinator.DebitResult.PARTIAL;
            }
            firstSlot--;
            secondSlot--;
            debitedQuantity = 2;
            state = BondedCompanionChargeCoordinator.State.DEBITED;
            return BondedCompanionChargeCoordinator.DebitResult.EXACT;
        }

        @Override
        public boolean markCharged() {
            if (failMarkCharged) return false;
            state = BondedCompanionChargeCoordinator.State.CHARGED;
            return true;
        }

        @Override
        public boolean refund() {
            refundCalled = true;
            if (state == BondedCompanionChargeCoordinator.State.COMPENSATED) {
                return true;
            }
            if (failFirstRefund) {
                failFirstRefund = false;
                return false;
            }
            firstSlot++;
            if (debitedQuantity == 2) secondSlot++;
            debitedQuantity = 0;
            refundApplications++;
            state = BondedCompanionChargeCoordinator.State.COMPENSATED;
            return true;
        }

        @Override
        public boolean releasePrepared() {
            if (state != BondedCompanionChargeCoordinator.State.PREPARED) {
                return false;
            }
            state = BondedCompanionChargeCoordinator.State.ABSENT;
            return true;
        }
    }
}
