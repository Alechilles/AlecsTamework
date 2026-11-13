package com.alechilles.alecstamework.api;

import javax.annotation.Nonnull;

/**
 * Decides one immutable population-admission request from the provider's own in-memory state,
 * and returns the decision at once.
 *
 * <p><b>Threading.</b> Tamework calls {@link #decide} inline wherever it needs an answer: on a
 * world thread during a tame, a pickup, a holder change, a litter or a spawn, on other threads in
 * capture and restore flows, and sometimes while it holds its companion index lock. It may call
 * one provider from several threads at once. So an implementation must be thread-safe, and it
 * must answer from memory: it must not block, wait on a future, do I/O, enter a world thread, or
 * call Tamework APIs. Tamework cannot interrupt a call; it logs a warning when one takes longer
 * than a few milliseconds.</p>
 *
 * <p><b>Failures.</b> A throw or a null decision is read as
 * {@link PopulationAdmissionProviderStatus#UNAVAILABLE}, and the admission is refused. Return
 * {@code UNAVAILABLE} yourself when your state for the owner is not ready (for example a profile
 * that has not loaded yet), with a message key that says so.</p>
 *
 * <p><b>Limits.</b> Return the domain claims and limits of an allow, so Tamework counts them in
 * the step that stores the companion. A provider that counts companions on its own and returns
 * no claims can be overshot by two changes decided at the same time.</p>
 */
@FunctionalInterface
public interface SynchronousPopulationAdmissionProvider {
    /** Returns an allow, deny or unavailable decision without mutating Tamework state. */
    @Nonnull
    PopulationAdmissionProviderDecision decide(@Nonnull PopulationAdmissionProviderRequest request);
}
