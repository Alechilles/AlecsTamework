package com.alechilles.alecstamework.api;

/**
 * An animal's age at the moment of a managed activity, as the breeding config's aging lifecycle sees it.
 *
 * <p>{@link #JUVENILE} is any animal not yet grown; the others are the adult aging stages. A role whose
 * aging is disabled reports no stage at all.</p>
 */
public enum AnimalAgeStage {
    JUVENILE,
    ADULT,
    PRIME,
    SENIOR
}
