package com.alechilles.alecstamework.companion.coop.runtime;

import com.alechilles.alecstamework.companion.coop.CompanionCoopReleaseRequest;
import com.alechilles.alecstamework.persistence.runtime.HytaleWorldOperationGateway;

/**
 * World-thread receipt resolution and insertion for one coop release.
 *
 * <p>An implementation must resolve the exact spawn receipt first. Absence permits insertion but
 * is never a confirmed result and can never evict the durable coop resident.</p>
 */
@FunctionalInterface
public interface CompanionCoopReleaseWorldGateway
        extends HytaleWorldOperationGateway<CompanionCoopReleaseRequest> {
}
