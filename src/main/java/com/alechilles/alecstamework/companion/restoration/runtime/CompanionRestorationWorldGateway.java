package com.alechilles.alecstamework.companion.restoration.runtime;

import com.alechilles.alecstamework.companion.restoration.CompanionRestorationRequest;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationGateway;

/**
 * World-thread entity receipt resolution and insertion for one restoration.
 *
 * <p>Implementations receive only the current world and its current store. They must first resolve
 * {@code request.targetAlias()} and confirm the exact spawn receipt when present; absence permits
 * a new insertion but is never itself a confirmed result.</p>
 */
@FunctionalInterface
public interface CompanionRestorationWorldGateway
        extends HytaleWorldOperationGateway<CompanionRestorationRequest> {
}
