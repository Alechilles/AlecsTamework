package com.alechilles.alecstamework.persistence.adapter.sqlite;

import com.alechilles.alecstamework.companion.extension.ProfileExtensionData;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionDataDecoder;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionDecodeResult;
import com.alechilles.alecstamework.companion.extension.ProfileExtensionKey;
import com.alechilles.alecstamework.companion.identity.ProfileId;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadKind;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadPriority;
import com.alechilles.alecstamework.persistence.kernel.PersistenceReadResult;
import com.alechilles.alecstamework.persistence.kernel.StorageFailure;
import com.alechilles.alecstamework.persistence.kernel.StorageFailureKind;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import javax.annotation.Nonnull;

/**
 * Async logical extension reader that preserves absence, deletion, corruption, and storage failure.
 */
public final class SqliteProfileExtensionReader {
    private static final PersistenceReadKind FIND =
            new PersistenceReadKind("profile_extension_find");
    private static final PersistenceReadKind LIST =
            new PersistenceReadKind("profile_extension_list");

    private final SqliteReadExecutor reads;

    public SqliteProfileExtensionReader(@Nonnull SqliteReadExecutor reads) {
        if (reads == null) {
            throw new IllegalArgumentException("Extension read executor is required");
        }
        this.reads = reads;
    }

    /** Finds one active value and validates its version, hash, and JSON syntax. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<ProfileExtensionData>> findActive(
            @Nonnull ProfileExtensionKey key
    ) {
        if (key == null) {
            throw new IllegalArgumentException("Extension key is required");
        }
        return reads.execute(new SqliteReadCommand<>(
                FIND,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> validateOne(
                        new SqliteProfileExtensionDataStore(connection).find(key),
                        FIND
                )
        ));
    }

    /** Lists active values in deterministic key order after validating every payload. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<List<ProfileExtensionData>>> findNamespace(
            @Nonnull ProfileId profileId,
            @Nonnull String namespace
    ) {
        if (profileId == null || namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("Extension profile and namespace are required");
        }
        String normalized = namespace.trim();
        return reads.execute(new SqliteReadCommand<>(
                LIST,
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> validateAll(
                        new SqliteProfileExtensionDataStore(connection)
                                .findNamespace(profileId, normalized)
                )
        ));
    }

    /** Lists validated active rows and tombstones for projection rebuild. */
    @Nonnull
    public CompletionStage<PersistenceReadResult<List<ProfileExtensionData>>>
    findAll() {
        return reads.execute(new SqliteReadCommand<>(
                new PersistenceReadKind("profile_extension_all"),
                PersistenceReadPriority.GAMEPLAY_CRITICAL,
                connection -> validateAll(
                        new SqliteProfileExtensionDataStore(connection)
                                .findAll()
                )
        ));
    }

    private PersistenceReadResult<ProfileExtensionData> validateOne(
            PersistenceReadResult<ProfileExtensionData> result,
            PersistenceReadKind kind
    ) {
        if (result instanceof PersistenceReadResult.Found<ProfileExtensionData> found) {
            if (found.value().deleted()) {
                return PersistenceReadResult.absent();
            }
            ProfileExtensionDecodeResult decode =
                    ProfileExtensionDataDecoder.decode(found.value());
            if (decode instanceof ProfileExtensionDecodeResult.Failed failed) {
                return decodeFailure(kind, failed);
            }
        }
        return result;
    }

    private PersistenceReadResult<List<ProfileExtensionData>> validateAll(
            PersistenceReadResult<List<ProfileExtensionData>> result
    ) {
        if (!(result instanceof
                PersistenceReadResult.Found<List<ProfileExtensionData>> found)) {
            return result;
        }
        ArrayList<ProfileExtensionData> validated = new ArrayList<>();
        for (ProfileExtensionData value : found.value()) {
            ProfileExtensionDecodeResult decode = ProfileExtensionDataDecoder.decode(value);
            if (decode instanceof ProfileExtensionDecodeResult.Failed failed) {
                return decodeFailure(LIST, failed);
            }
            validated.add(value);
        }
        return PersistenceReadResult.found(List.copyOf(validated), found.revision());
    }

    private <T> PersistenceReadResult<T> decodeFailure(
            PersistenceReadKind kind,
            ProfileExtensionDecodeResult.Failed failed
    ) {
        return PersistenceReadResult.failed(new StorageFailure(
                StorageFailureKind.DECODE,
                failed.code(),
                kind.value(),
                false,
                failed.cause()
        ));
    }
}
