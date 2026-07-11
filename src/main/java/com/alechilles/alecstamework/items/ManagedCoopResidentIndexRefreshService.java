package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopReadResult;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.AuthorityRecord;
import com.alechilles.alecstamework.persistence.sqlite.ManagedCoopResidentRepository.ResidentRecord;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Atomically refreshes the managed-coop runtime index from fail-closed repository snapshots. */
public final class ManagedCoopResidentIndexRefreshService {
    public enum RefreshStatus {
        REFRESHED,
        REJECTED
    }

    public record RefreshResult(@Nonnull RefreshStatus status,
                                long revision,
                                @Nullable String detail) {
        public boolean refreshed() {
            return status == RefreshStatus.REFRESHED;
        }
    }

    private final ManagedCoopResidentIndex index;
    private final SnapshotSource source;
    private final WarningSink warningSink;

    public ManagedCoopResidentIndexRefreshService(@Nonnull ManagedCoopResidentRepository repository,
                                                  @Nonnull ManagedCoopResidentIndex index,
                                                  @Nullable HytaleLogger logger) {
        this(index, new RepositorySnapshotSource(repository), loggerWarningSink(logger));
    }

    ManagedCoopResidentIndexRefreshService(@Nonnull ManagedCoopResidentIndex index,
                                           @Nonnull SnapshotSource source,
                                           @Nonnull WarningSink warningSink) {
        this.index = Objects.requireNonNull(index, "index");
        this.source = Objects.requireNonNull(source, "source");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
    }

    /** Replaces the visible index only when both complete typed reads and cross-table checks pass. */
    @Nonnull
    public synchronized RefreshResult refresh() {
        ManagedCoopReadResult<List<AuthorityRecord>> authorities = source.loadAuthorities();
        ManagedCoopReadResult<List<ResidentRecord>> residents = source.loadResidents();
        ManagedCoopResidentIndex.RebuildResult rebuild = index.rebuild(authorities, residents);
        long revision = index.snapshot().revision();
        if (rebuild.rebuilt()) {
            return new RefreshResult(RefreshStatus.REFRESHED, revision, null);
        }
        String detail = failureDetail(authorities, residents, rebuild.detail());
        warningSink.warn("Managed coop resident index refresh rejected: " + detail);
        return new RefreshResult(RefreshStatus.REJECTED, revision, detail);
    }

    @Nonnull
    private String failureDetail(ManagedCoopReadResult<?> authorities,
                                 ManagedCoopReadResult<?> residents,
                                 @Nullable String rebuildDetail) {
        String authorityFailure = typedFailure("authorities", authorities);
        if (authorityFailure != null) {
            return authorityFailure;
        }
        String residentFailure = typedFailure("residents", residents);
        if (residentFailure != null) {
            return residentFailure;
        }
        return rebuildDetail == null || rebuildDetail.isBlank()
                ? "managed_coop_index_rebuild_rejected"
                : rebuildDetail;
    }

    @Nullable
    private String typedFailure(@Nonnull String label, @Nullable ManagedCoopReadResult<?> result) {
        if (result == null) {
            return label + ":missing_read_result";
        }
        if (result.status() == ManagedCoopReadResult.Status.LOADED) {
            return null;
        }
        if (result.failure() == null) {
            return label + ":" + result.status().name().toLowerCase();
        }
        return label + ":" + result.failure().kind().name().toLowerCase()
                + ":" + result.failure().detail();
    }

    @Nonnull
    private static WarningSink loggerWarningSink(@Nullable HytaleLogger logger) {
        if (logger == null) {
            return ignored -> { };
        }
        return message -> logger.at(Level.WARNING).log(message);
    }

    interface SnapshotSource {
        @Nonnull
        ManagedCoopReadResult<List<AuthorityRecord>> loadAuthorities();

        @Nonnull
        ManagedCoopReadResult<List<ResidentRecord>> loadResidents();
    }

    interface WarningSink {
        void warn(@Nonnull String message);
    }

    private record RepositorySnapshotSource(ManagedCoopResidentRepository repository) implements SnapshotSource {
        private RepositorySnapshotSource {
            Objects.requireNonNull(repository, "repository");
        }

        @Nonnull
        @Override
        public ManagedCoopReadResult<List<AuthorityRecord>> loadAuthorities() {
            return repository.loadAllActiveAuthorities();
        }

        @Nonnull
        @Override
        public ManagedCoopReadResult<List<ResidentRecord>> loadResidents() {
            return repository.loadAllActiveResidents();
        }
    }
}
