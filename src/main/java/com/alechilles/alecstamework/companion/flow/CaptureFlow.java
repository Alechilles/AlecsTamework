package com.alechilles.alecstamework.companion.flow;

import com.alechilles.alecstamework.companion.admission.CompanionAdmission;
import com.alechilles.alecstamework.companion.admission.CompanionAdmissionGate;
import com.alechilles.alecstamework.companion.admission.ProviderAdmission;
import com.alechilles.alecstamework.companion.index.CompanionIndex;
import com.alechilles.alecstamework.companion.index.CompanionRecord;
import com.alechilles.alecstamework.companion.index.LocationKind;
import com.alechilles.alecstamework.companion.item.CaptureItemKeys;
import com.alechilles.alecstamework.companion.live.CompanionSnapshots;
import com.alechilles.alecstamework.companion.live.LoadedBodies;
import com.alechilles.alecstamework.companion.store.SnapshotEnvelope;
import com.hypixel.hytale.logger.HytaleLogger;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bson.BsonDocument;

/**
 * Commit-first capture into an item (spec 8.2). The caller has taken the snapshot on the body's
 * world thread; this flow commits ITEM, flushes, and only then lets the caller remove the body
 * and hand over the item. A flush failure reverts the record and re-registers the body.
 *
 * <p>The caller removes the body and gives the item only on {@link Result#CAPTURED}. After any
 * other result the body stays in the world. When a newer change replaced the commit
 * ({@link Result#CONFLICT}), the body is already unregistered and its stamp is stale, so the
 * generation fence removes it when it next loads.
 *
 * <p>A capture that gives a managed role's companion a new owner first asks the role's admission
 * provider (plan 6 R10), before the index lock is taken. The claims and domain limits it allows
 * are checked again with the built-in caps under the lock, and the claims are stored on the
 * committed record. Every other capture commits before {@link #capture} returns.
 *
 * <p>Continuations run on whichever thread completes the provider's decision or the flush, so
 * the caller hands its entity work back to the owning world thread. The flow touches no live
 * entities; {@code R} is only a registry key for the body.
 *
 * @param <R> the body reference type (Ref&lt;EntityStore&gt; in production)
 */
public final class CaptureFlow<R> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * OWNED_LIMIT and GROUP_LIMIT: the record after capture would pass a population cap.
     * PROVIDER_DENIED: an admission provider denied the capture or one of its domain limits is
     * reached. PROVIDER_UNAVAILABLE: the provider gave no decision. Nothing changed for any of them.
     */
    public enum Result { CAPTURED, NOT_CAPTURABLE, CONFLICT, COMMIT_FAILED, OWNED_LIMIT, GROUP_LIMIT,
        PROVIDER_DENIED, PROVIDER_UNAVAILABLE }

    /**
     * {@code itemRef} is what to write on the item; null unless CAPTURED. {@code messageKey} is the
     * translation key of a population refusal (a cap, a provider's denial or a domain limit); null
     * for every other result.
     */
    public record Outcome(@Nonnull Result result, @Nullable CaptureItemKeys.Ref itemRef, @Nullable String messageKey) {
        public Outcome(@Nonnull Result result, @Nullable CaptureItemKeys.Ref itemRef) {
            this(result, itemRef, null);
        }
    }

    /**
     * One capture. {@code stampedProfileId} null means an unstamped body (wild or owned but never
     * stamped): it gets a fresh record at generation 0. {@code owner} is the record owner after
     * capture (null only for an unowned wild capture).
     */
    public record Capture<R>(@Nullable UUID stampedProfileId, long stampedGeneration, @Nonnull R body,
                             @Nonnull CompanionTransitions.BodyFacts facts, @Nullable UUID owner,
                             @Nullable String ownerName, @Nonnull BsonDocument snapshotData) {
        public Capture {
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(facts, "facts");
            Objects.requireNonNull(snapshotData, "snapshotData");
        }
    }

    private final CompanionIndex index;
    private final LoadedBodies<R> loaded;
    private final BiConsumer<UUID, SnapshotEnvelope> queueSnapshot;
    private final Function<UUID, CompletableFuture<Void>> flushOwner;
    private final ProviderAdmission providers;
    private final CompanionAdmissionGate.Check admission;

    /** A flow with no admission providers; {@code admission} is the built-in caps only. */
    public CaptureFlow(@Nonnull CompanionIndex index, @Nonnull LoadedBodies<R> loaded,
                       @Nonnull BiConsumer<UUID, SnapshotEnvelope> queueSnapshot,
                       @Nonnull Function<UUID, CompletableFuture<Void>> flushOwner,
                       @Nonnull BiFunction<CompanionRecord, CompanionRecord, CompanionAdmission.Refusal> admission) {
        this(index, loaded, queueSnapshot, flushOwner, ProviderAdmission.none(), builtInOnly(admission));
    }

    /**
     * @param queueSnapshot queues a snapshot write for a profile
     * @param flushOwner    writes the given owner's file now (null for unowned); fails on error or timeout
     * @param providers     the admission provider stage, asked before the index lock is taken
     * @param admission     population caps and provider domain limits for the change from the
     *                      current record (null for a new one) to the captured one; returns null
     *                      to admit. Called under the index lock.
     */
    public CaptureFlow(@Nonnull CompanionIndex index, @Nonnull LoadedBodies<R> loaded,
                       @Nonnull BiConsumer<UUID, SnapshotEnvelope> queueSnapshot,
                       @Nonnull Function<UUID, CompletableFuture<Void>> flushOwner,
                       @Nonnull ProviderAdmission providers, @Nonnull CompanionAdmissionGate.Check admission) {
        this.index = Objects.requireNonNull(index, "index");
        this.loaded = Objects.requireNonNull(loaded, "loaded");
        this.queueSnapshot = Objects.requireNonNull(queueSnapshot, "queueSnapshot");
        this.flushOwner = Objects.requireNonNull(flushOwner, "flushOwner");
        this.providers = Objects.requireNonNull(providers, "providers");
        this.admission = Objects.requireNonNull(admission, "admission");
    }

    /** Never completes exceptionally for an expected failure; the {@link Result} says what happened. */
    @Nonnull
    public CompletableFuture<Outcome> capture(@Nonnull Capture<R> capture) {
        // What the commit would write, read without the lock, so the provider is asked off it.
        UUID stamped = capture.stampedProfileId();
        CompanionRecord seen = stamped == null ? null : index.get(stamped);
        if (stamped != null && seen == null) {
            return CompletableFuture.completedFuture(new Outcome(Result.NOT_CAPTURABLE, null));
        }
        CompanionRecord created = stamped != null ? null
                : CompanionTransitions.newItem(UUID.randomUUID(), capture.facts(), capture.owner(), capture.ownerName());
        CompanionRecord preview = created != null ? created
                : CompanionTransitions.capturedToItem(seen, capture.facts().summary(), capture.owner(),
                        capture.ownerName()).apply(seen.toBuilder()).build();
        return providers.evaluate(seen, preview).toCompletableFuture().thenCompose(provider -> provider.admitted()
                ? commitAndFlush(capture, seen, created, provider)
                : CompletableFuture.completedFuture(
                        new Outcome(result(provider.refusal()), null, provider.messageKey())));
    }

    private CompletableFuture<Outcome> commitAndFlush(Capture<R> capture, @Nullable CompanionRecord seen,
                                                      @Nullable CompanionRecord created,
                                                      ProviderAdmission.Outcome provider) {
        Commit commit = index.atomically(() -> commit(capture, seen, created, provider));
        if (commit.refusal() != null) {
            return CompletableFuture.completedFuture(new Outcome(commit.refusal(), null, commit.messageKey()));
        }
        CompanionRecord after = commit.after();
        UUID profileId = after.profileId();
        try {
            queueSnapshot.accept(profileId, new SnapshotEnvelope(profileId, CompanionSnapshots.FORMAT,
                    after.generation(), capture.snapshotData()));
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure)
                    .log("Could not queue the snapshot of captured companion %s; the capture is undone", profileId);
            revertSafely(capture, commit);
            return CompletableFuture.completedFuture(new Outcome(Result.COMMIT_FAILED, null));
        }
        return OwnerFileFlush.flushOwners(flushOwner, commit.before(), after).handle((ignored, error) -> error)
                .thenApply(error -> {
                    if (error != null) {
                        LOGGER.at(Level.WARNING).withCause(error)
                                .log("Capture of companion %s was not written; the capture is undone", profileId);
                        revertSafely(capture, commit);
                        return new Outcome(Result.COMMIT_FAILED, null);
                    }
                    // A change that kept the item holder (a rename, say) does not undo the capture.
                    CompanionRecord now = index.get(profileId);
                    if (now == null || now.location().kind() != LocationKind.ITEM
                            || now.generation() != after.generation()) {
                        return new Outcome(Result.CONFLICT, null);
                    }
                    return new Outcome(Result.CAPTURED, new CaptureItemKeys.Ref(profileId, after.generation()));
                });
    }

    /**
     * @param seen     the stamped record the provider was asked about; null for an unstamped body
     * @param created  the new record of an unstamped body; null for a stamped one
     * @param provider what the provider allowed for the change from {@code seen}
     */
    private Commit commit(Capture<R> capture, @Nullable CompanionRecord seen, @Nullable CompanionRecord created,
                          ProviderAdmission.Outcome provider) {
        CompanionTransitions.BodyFacts facts = capture.facts();
        UUID stamped = capture.stampedProfileId();
        CompanionAdmission.Provided provided = provider.provided();
        if (stamped != null) {
            CompanionRecord before = index.get(stamped);
            if (before == null || before.location().kind() != LocationKind.LIVE
                    || before.generation() != capture.stampedGeneration()) {
                return Commit.refused(Result.NOT_CAPTURABLE);
            }
            R registered = loaded.get(stamped);
            if (registered != null && !registered.equals(capture.body())) {
                // Another body holds this profile; capturing this one would strand the registered one.
                return Commit.refused(Result.NOT_CAPTURABLE);
            }
            if (!Objects.equals(before.ownerUuid(), seen.ownerUuid())) {
                // The owner changed while the provider was asked; its answer is for another change.
                return Commit.refused(Result.CONFLICT);
            }
            UnaryOperator<CompanionRecord.Builder> captured =
                    CompanionTransitions.capturedToItem(before, facts.summary(), capture.owner(), capture.ownerName());
            UnaryOperator<CompanionRecord.Builder> change = !provider.asked() ? captured
                    : b -> captured.apply(b).domainClaims(provided.claims());
            Commit capped = capped(before, change.apply(before.toBuilder()).build(), provided);
            if (capped != null) {
                return capped;
            }
            CompanionIndex.Mutation m = index.update(stamped, before.revision(), change);
            if (!m.applied()) {
                return Commit.refused(Result.CONFLICT);
            }
            boolean unregistered = loaded.removeIfSame(stamped, capture.body());
            return new Commit(null, null, before, m.after(), unregistered);
        }
        if (index.byNpcUuid(facts.npcUuid()) != null) {
            return Commit.refused(Result.NOT_CAPTURABLE);
        }
        CompanionRecord claimed = !provider.asked() ? created
                : created.toBuilder().domainClaims(provided.claims()).build();
        Commit capped = capped(null, claimed, provided);
        if (capped != null) {
            return capped;
        }
        CompanionIndex.Mutation m = index.insert(claimed);
        return m.applied() ? new Commit(null, null, null, m.after(), false) : Commit.refused(Result.CONFLICT);
    }

    /** The refused commit when a cap or a provider domain limit refuses the change; null when admitted. */
    @Nullable
    private Commit capped(@Nullable CompanionRecord before, CompanionRecord after,
                          CompanionAdmission.Provided provided) {
        CompanionAdmissionGate.Denial denial = admission.deny(before, after, provided);
        return denial == null ? null : new Commit(result(denial.refusal()), denial.messageKey(), null, null, false);
    }

    private static Result result(CompanionAdmission.Refusal refusal) {
        return switch (refusal) {
            case OWNED -> Result.OWNED_LIMIT;
            case GROUP_OWNED, GROUP_DEPLOYED -> Result.GROUP_LIMIT;
            case PROVIDER_DENIED -> Result.PROVIDER_DENIED;
            case PROVIDER_UNAVAILABLE -> Result.PROVIDER_UNAVAILABLE;
        };
    }

    private static CompanionAdmissionGate.Check builtInOnly(
            BiFunction<CompanionRecord, CompanionRecord, CompanionAdmission.Refusal> admission) {
        Objects.requireNonNull(admission, "admission");
        return (before, after, provided) -> {
            CompanionAdmission.Refusal refusal = admission.apply(before, after);
            return refusal == null ? null : CompanionAdmissionGate.Denial.of(refusal);
        };
    }

    /** A failed undo is logged; it never fails the returned future. */
    private void revertSafely(Capture<R> capture, Commit commit) {
        try {
            revertCommit(capture, commit);
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure)
                    .log("Could not undo the capture of companion %s", commit.after().profileId());
        }
    }

    /**
     * Undoes an unwritten commit. A stamped body goes back to its record and registry entry, and
     * the snapshot is queued again at the old generation, because a snapshot newer than its record
     * cannot be restored. A body that never had a record leaves a tombstone, since there is nothing
     * to go back to. Changes nothing when the record changed since the commit.
     */
    private void revertCommit(Capture<R> capture, Commit commit) {
        CompanionRecord after = commit.after();
        UUID profileId = after.profileId();
        CompanionRecord before = commit.before();
        index.atomically(() -> {
            if (before == null) {
                index.update(profileId, after.revision(), CompanionTransitions.released(after));
                return null;
            }
            if (index.revert(profileId, after.revision(), before).applied()) {
                if (commit.unregistered()) {
                    loaded.put(profileId, capture.body());
                }
                queueSnapshot.accept(profileId, new SnapshotEnvelope(profileId, CompanionSnapshots.FORMAT,
                        before.generation(), capture.snapshotData()));
            }
            return null;
        });
    }

    private record Commit(@Nullable Result refusal, @Nullable String messageKey, @Nullable CompanionRecord before,
                          @Nullable CompanionRecord after, boolean unregistered) {
        static Commit refused(Result refusal) {
            return new Commit(refusal, null, null, null, false);
        }
    }
}
