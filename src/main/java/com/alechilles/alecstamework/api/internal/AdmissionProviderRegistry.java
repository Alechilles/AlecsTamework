package com.alechilles.alecstamework.api.internal;

import com.alechilles.alecstamework.api.AdmissionProviderApi;
import com.alechilles.alecstamework.api.PopulationAdmissionProvider;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderDecision;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderRequest;
import com.alechilles.alecstamework.api.PopulationAdmissionProviderStatus;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Thread-safe provider registry with bounded, fail-closed evaluation. */
public final class AdmissionProviderRegistry implements AdmissionProviderApi, AutoCloseable {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_CALLBACK_WORKERS = 4;
    private static final int MAX_PENDING_CALLBACKS = 8;

    private final Duration timeout;
    private final ThreadPoolExecutor callbacks;
    private final ScheduledExecutorService timers;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<ProviderInvocation> invocations =
            ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Registration> registrations =
            new ConcurrentHashMap<>();

    public AdmissionProviderRegistry() {
        this(DEFAULT_TIMEOUT);
    }

    public AdmissionProviderRegistry(@Nonnull Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Provider timeout must be positive");
        }
        this.timeout = timeout;
        this.callbacks = new ThreadPoolExecutor(
                MAX_CALLBACK_WORKERS,
                MAX_CALLBACK_WORKERS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_CALLBACKS),
                daemonFactory("tamework-admission-provider"),
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.timers = timeoutExecutor();
    }

    @Override
    @Nonnull
    public AutoCloseable register(
            @Nonnull String providerId,
            int contractVersion,
            @Nonnull PopulationAdmissionProvider provider
    ) {
        String id = canonical(providerId);
        if (contractVersion <= 0) {
            throw new IllegalArgumentException("contractVersion must be positive");
        }
        Objects.requireNonNull(provider, "provider");
        if (closed.get()) {
            throw new IllegalStateException("Admission provider registry is closed");
        }
        Registration registration = new Registration(
                id,
                contractVersion,
                provider,
                UUID.randomUUID().toString()
        );
        Registration existing = registrations.putIfAbsent(id, registration);
        if (existing != null) {
            throw new IllegalStateException("Provider is already registered: " + id);
        }
        if (closed.get()) {
            registration.active.set(false);
            registrations.remove(id, registration);
            throw new IllegalStateException("Admission provider registry is closed");
        }
        return new RegistrationHandle(id, registration);
    }

    /** Returns immutable readiness and generation evidence for one provider. */
    @Nonnull
    public ProviderReadiness readiness(
            @Nonnull String providerId,
            int contractVersion
    ) {
        String id = canonical(providerId);
        Registration registration = registrations.get(id);
        if (registration == null || !registration.active.get() || closed.get()) {
            return ProviderReadiness.unavailable(id, contractVersion, "provider-not-registered");
        }
        if (registration.contractVersion != contractVersion) {
            return ProviderReadiness.unavailable(
                    id,
                    contractVersion,
                    "provider-contract-mismatch"
            );
        }
        return new ProviderReadiness(
                id,
                registration.contractVersion,
                registration.generationToken,
                true,
                "ready"
        );
    }

    /** Returns all active provider snapshots without exposing callback objects. */
    @Nonnull
    public Map<String, ProviderReadiness> snapshot() {
        java.util.LinkedHashMap<String, ProviderReadiness> result =
                new java.util.LinkedHashMap<>();
        registrations.forEach((id, registration) -> {
            if (registration.active.get() && !closed.get()) {
                result.put(id, new ProviderReadiness(
                        id, registration.contractVersion,
                        registration.generationToken, true, "ready"
                ));
            }
        });
        return Map.copyOf(result);
    }

    /** Evaluates a request outside Tamework's SQLite writer transaction. */
    @Nonnull
    public CompletionStage<PopulationAdmissionProviderDecision> evaluate(
            @Nonnull PopulationAdmissionProviderRequest request
    ) {
        Objects.requireNonNull(request, "request");
        return evaluate(request.providerId(), request);
    }

    /** Evaluates under an explicit provider identity, rejecting mismatches. */
    @Nonnull
    public CompletionStage<PopulationAdmissionProviderDecision> evaluate(
            @Nonnull String providerId,
            @Nullable PopulationAdmissionProviderRequest request
    ) {
        String id = canonical(providerId);
        Registration registration = registrations.get(id);
        if (closed.get() || registration == null || !registration.active.get() || request == null
                || !id.equals(canonical(request.providerId()))
                || request.contractVersion() != registration.contractVersion) {
            return CompletableFuture.completedFuture(
                    unavailable("provider-not-ready")
            );
        }
        ProviderInvocation invocation = new ProviderInvocation(
                registration, request
        );
        invocations.add(invocation);
        try {
            invocation.scheduleTimeout();
            invocation.submit();
        } catch (RejectedExecutionException saturated) {
            invocation.failClosed(
                    closed.get() ? "provider-closed" : "provider-saturated"
            );
        } catch (Throwable failure) {
            invocation.failClosed("provider-exception");
        }
        return invocation.result;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            registrations.values().forEach(registration -> registration.active.set(false));
            registrations.clear();
            invocations.forEach(invocation -> invocation.failClosed("provider-closed"));
            callbacks.shutdownNow();
            timers.shutdownNow();
            try {
                callbacks.awaitTermination(250, TimeUnit.MILLISECONDS);
                timers.awaitTermination(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            invocations.clear();
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable,
                    prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static ScheduledExecutorService timeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                1,
                daemonFactory("tamework-admission-timeout")
        );
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static PopulationAdmissionProviderDecision unavailable(String reason) {
        return PopulationAdmissionProviderDecision.unavailable(reason);
    }

    private final class ProviderInvocation implements Runnable {
        private final Registration registration;
        private final PopulationAdmissionProviderRequest request;
        private final CompletableFuture<PopulationAdmissionProviderDecision> result =
                new CompletableFuture<>();
        private final AtomicReference<Future<?>> submitted = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> timeoutTask =
                new AtomicReference<>();

        private ProviderInvocation(
                Registration registration,
                PopulationAdmissionProviderRequest request
        ) {
            this.registration = registration;
            this.request = request;
        }

        private void scheduleTimeout() {
            ScheduledFuture<?> timeout = timers.schedule(
                    () -> failClosed("provider-timeout"),
                    AdmissionProviderRegistry.this.timeout.toNanos(),
                    TimeUnit.NANOSECONDS
            );
            timeoutTask.set(timeout);
            if (result.isDone()) {
                timeout.cancel(false);
            }
        }

        private void submit() {
            Future<?> future = callbacks.submit(this);
            submitted.set(future);
            if (result.isDone()) {
                future.cancel(true);
            }
        }

        @Override
        public void run() {
            if (result.isDone()) {
                invocations.remove(this);
                return;
            }
            if (closed.get() || !registration.active.get()) {
                failClosed("provider-closed");
                return;
            }
            CompletionStage<PopulationAdmissionProviderDecision> stage;
            try {
                stage = registration.provider.evaluate(request);
            } catch (Throwable failure) {
                failClosed("provider-exception");
                return;
            }
            if (stage == null) {
                failClosed("provider-null-stage");
                return;
            }
            try {
                stage.whenComplete((decision, failure) -> {
                    if (closed.get() || !registration.active.get()) {
                        failClosed("provider-closed");
                    } else if (failure != null) {
                        failClosed("provider-exception");
                    } else if (decision == null) {
                        failClosed("provider-null-decision");
                    } else {
                        complete(decision);
                    }
                });
            } catch (Throwable failure) {
                failClosed("provider-exception");
            }
        }

        private void complete(PopulationAdmissionProviderDecision decision) {
            ScheduledFuture<?> timeout = timeoutTask.get();
            if (timeout != null) {
                timeout.cancel(false);
            }
            result.complete(decision);
            invocations.remove(this);
        }

        private void failClosed(String reason) {
            ScheduledFuture<?> timeout = timeoutTask.get();
            if (timeout != null) {
                timeout.cancel(false);
            }
            result.complete(unavailable(reason));
            Future<?> future = submitted.get();
            if (future != null) {
                future.cancel(true);
                if (future instanceof Runnable task) {
                    callbacks.remove(task);
                }
            }
            invocations.remove(this);
        }
    }

    private static String canonical(String providerId) {
        String normalized = Objects.requireNonNull(providerId, "providerId")
                .trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("providerId is required");
        }
        return normalized;
    }

    private final class RegistrationHandle implements AutoCloseable {
        private final String id;
        private final Registration registration;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RegistrationHandle(String id, Registration registration) {
            this.id = id;
            this.registration = registration;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                registration.active.set(false);
                registrations.remove(id, registration);
            }
        }
    }

    private static final class Registration {
        private final String id;
        private final int contractVersion;
        private final PopulationAdmissionProvider provider;
        private final String generationToken;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Registration(
                String id,
                int contractVersion,
                PopulationAdmissionProvider provider,
                String generationToken
        ) {
            this.id = id;
            this.contractVersion = contractVersion;
            this.provider = provider;
            this.generationToken = generationToken;
        }
    }

    /** Immutable provider registration evidence frozen into admission tokens. */
    public record ProviderReadiness(
            @Nonnull String providerId,
            int contractVersion,
            @Nonnull String generationToken,
            boolean available,
            @Nonnull String detail
    ) {
        public ProviderReadiness {
            providerId = canonical(providerId);
            if (contractVersion < 0 || generationToken == null
                    || generationToken.isBlank() || detail == null
                    || detail.isBlank()) {
                throw new IllegalArgumentException("Complete provider readiness is required");
            }
            generationToken = generationToken.trim();
            detail = detail.trim();
        }

        private static ProviderReadiness unavailable(
                String providerId,
                int contractVersion,
                String detail
        ) {
            return new ProviderReadiness(
                    providerId,
                    Math.max(0, contractVersion),
                    "unavailable",
                    false,
                    detail
            );
        }
    }
}
