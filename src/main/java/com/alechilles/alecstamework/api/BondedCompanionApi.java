package com.alechilles.alecstamework.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.annotation.Nonnull;

/** Separate capability-gated authority for bonded companion profiles and leases. */
public interface BondedCompanionApi {
    @Nonnull
    BondedCompanionAvailability availability();

    @Nonnull
    CompletableFuture<BondedCompanionResult<List<BondedCompanionProfileView>>>
            list(@Nonnull UUID ownerUuid, @Nonnull String rosterId);

    @Nonnull
    CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>>
            provision(@Nonnull BondedCompanionProvisionRequest request);

    @Nonnull
    CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>>
            summon(@Nonnull BondedCompanionActionRequest request);

    @Nonnull
    CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>>
            store(@Nonnull BondedCompanionActionRequest request);

    @Nonnull
    CompletableFuture<BondedCompanionResult<BondedCompanionReviveQuote>>
            quoteRevive(@Nonnull BondedCompanionActionRequest request);

    @Nonnull
    CompletableFuture<BondedCompanionResult<BondedCompanionProfileView>>
            revive(@Nonnull BondedCompanionReviveRequest request);

    @Nonnull
    CompletableFuture<BondedCompanionResult<BondedCompanionExtensionData>>
            getExtensionData(@Nonnull BondedCompanionExtensionDataKey key);

    @Nonnull
    CompletableFuture<BondedCompanionResult<BondedCompanionExtensionData>>
            compareAndSetExtensionData(
                    @Nonnull BondedCompanionExtensionDataUpdate update
            );

    @Nonnull
    AutoCloseable subscribe(
            @Nonnull Consumer<BondedCompanionChangedEvent> listener
    );

    static BondedCompanionApi unavailable() {
        return UnavailableHolder.INSTANCE;
    }

    /** Holder avoids allocating a fallback facade for every API call. */
    final class UnavailableHolder {
        private static final String REASON =
                "bonded-companion-authority-unavailable";
        private static final BondedCompanionAvailability AVAILABILITY =
                BondedCompanionAvailability.unavailable(REASON);
        private static final BondedCompanionApi INSTANCE =
                new BondedCompanionApi() {
                    @Override
                    public BondedCompanionAvailability availability() {
                        return AVAILABILITY;
                    }

                    @Override
                    public CompletableFuture<BondedCompanionResult<
                            List<BondedCompanionProfileView>>> list(
                            UUID ownerUuid,
                            String rosterId
                    ) {
                        Objects.requireNonNull(ownerUuid, "ownerUuid");
                        Objects.requireNonNull(rosterId, "rosterId");
                        return unavailableResult();
                    }

                    @Override
                    public CompletableFuture<BondedCompanionResult<
                            BondedCompanionProfileView>> provision(
                            BondedCompanionProvisionRequest request
                    ) {
                        Objects.requireNonNull(request, "request");
                        return unavailableResult();
                    }

                    @Override
                    public CompletableFuture<BondedCompanionResult<
                            BondedCompanionProfileView>> summon(
                            BondedCompanionActionRequest request
                    ) {
                        Objects.requireNonNull(request, "request");
                        return unavailableResult();
                    }

                    @Override
                    public CompletableFuture<BondedCompanionResult<
                            BondedCompanionProfileView>> store(
                            BondedCompanionActionRequest request
                    ) {
                        Objects.requireNonNull(request, "request");
                        return unavailableResult();
                    }

                    @Override
                    public CompletableFuture<BondedCompanionResult<
                            BondedCompanionReviveQuote>> quoteRevive(
                            BondedCompanionActionRequest request
                    ) {
                        Objects.requireNonNull(request, "request");
                        return unavailableResult();
                    }

                    @Override
                    public CompletableFuture<BondedCompanionResult<
                            BondedCompanionProfileView>> revive(
                            BondedCompanionReviveRequest request
                    ) {
                        Objects.requireNonNull(request, "request");
                        return unavailableResult();
                    }

                    @Override
                    public CompletableFuture<BondedCompanionResult<
                            BondedCompanionExtensionData>> getExtensionData(
                            BondedCompanionExtensionDataKey key
                    ) {
                        Objects.requireNonNull(key, "key");
                        return unavailableResult();
                    }

                    @Override
                    public CompletableFuture<BondedCompanionResult<
                            BondedCompanionExtensionData>>
                            compareAndSetExtensionData(
                                    BondedCompanionExtensionDataUpdate update
                            ) {
                        Objects.requireNonNull(update, "update");
                        return unavailableResult();
                    }

                    @Override
                    public AutoCloseable subscribe(
                            Consumer<BondedCompanionChangedEvent> listener
                    ) {
                        Objects.requireNonNull(listener, "listener");
                        return () -> { };
                    }

                    private <T> CompletableFuture<
                            BondedCompanionResult<T>> unavailableResult() {
                        return CompletableFuture.completedFuture(
                                BondedCompanionResult.unavailable(REASON)
                        );
                    }
                };

        private UnavailableHolder() {
        }
    }
}
