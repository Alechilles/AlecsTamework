package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.items.ManagedCoopReleaseCoordinator.SpawnReady;
import com.alechilles.alecstamework.persistence.sqlite.CoopLifecycleOperationRepository.OperationRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Preflight/adoption boundary for restart-visible managed-coop release projections. */
interface ManagedCoopPersistedProjectionRecovery {
    enum Status {
        ABSENT,
        EXACT,
        BLOCKED
    }

    record Resolution(
            @Nonnull Status status,
            @Nullable String worldName,
            @Nullable Integer chunkX,
            @Nullable Integer chunkZ,
            long evidenceRevision,
            long loadedIdentityRevision,
            @Nullable String detail
    ) {
        public Resolution {
            Objects.requireNonNull(status, "status");
            boolean exact = status == Status.EXACT;
            if (exact != (worldName != null && chunkX != null && chunkZ != null)) {
                throw new IllegalArgumentException("persisted projection location shape mismatch");
            }
            if (evidenceRevision < 0L || loadedIdentityRevision < 0L) {
                throw new IllegalArgumentException("evidence revisions must not be negative");
            }
        }

        boolean exact() {
            return status == Status.EXACT;
        }

        static Resolution absent() {
            return absent(0L);
        }

        static Resolution absent(long revision) {
            return absent(revision, 0L);
        }

        static Resolution absent(long revision, long loadedIdentityRevision) {
            return new Resolution(
                    Status.ABSENT, null, null, null,
                    revision, loadedIdentityRevision, null);
        }

        static Resolution exact(String worldName, int chunkX, int chunkZ) {
            return exact(worldName, chunkX, chunkZ, 0L);
        }

        static Resolution exact(String worldName, int chunkX, int chunkZ, long revision) {
            return exact(worldName, chunkX, chunkZ, revision, 0L);
        }

        static Resolution exact(
                String worldName,
                int chunkX,
                int chunkZ,
                long revision,
                long loadedIdentityRevision) {
            return new Resolution(
                    Status.EXACT, worldName, chunkX, chunkZ,
                    revision, loadedIdentityRevision, null);
        }

        static Resolution blocked(String detail) {
            return new Resolution(Status.BLOCKED, null, null, null, 0L, 0L, detail);
        }
    }

    record Adoption(boolean adopted, @Nullable String detail) {
        static Adoption adopted(String detail) {
            return new Adoption(true, detail);
        }

        static Adoption blocked(String detail) {
            return new Adoption(false, detail);
        }
    }

    @Nonnull
    Resolution resolve(@Nonnull OperationRecord operation, @Nonnull ResidentRecord resident);

    /** Confirms the sealed generation has not changed after a durable spawn claim. */
    boolean current(@Nonnull Resolution resolution);

    @Nullable
    CompletableFuture<Adoption> adopt(
            @Nonnull OperationRecord operation,
            @Nonnull SpawnReady claim,
            @Nonnull ResidentRecord resident,
            @Nonnull Resolution projection);

    static ManagedCoopPersistedProjectionRecovery passthrough() {
        return new ManagedCoopPersistedProjectionRecovery() {
            @Override
            public Resolution resolve(OperationRecord operation, ResidentRecord resident) {
                return Resolution.absent();
            }

            @Override
            public boolean current(Resolution resolution) {
                return true;
            }

            @Override
            public CompletableFuture<Adoption> adopt(
                    OperationRecord operation, SpawnReady claim, ResidentRecord resident,
                    Resolution projection) {
                return CompletableFuture.completedFuture(Adoption.blocked(
                        "persisted_projection_passthrough_cannot_adopt"));
            }
        };
    }
}
