package com.alechilles.alecstamework.persistence.control;

/** Identifies the lock path and ownership scope that blocked engine startup. */
final class PersistenceEngineLockUnavailableException
        extends IllegalStateException {
    private final boolean sameProcess;

    private PersistenceEngineLockUnavailableException(
            String path,
            boolean sameProcess,
            Throwable cause
    ) {
        super(
                "persistence_engine_lock_unavailable:path=" + path
                        + ";scope="
                        + (sameProcess
                        ? "same_process"
                        : "external_process"),
                cause
        );
        this.sameProcess = sameProcess;
    }

    static PersistenceEngineLockUnavailableException active(
            boolean sameProcess,
            Throwable cause
    ) {
        return unavailable("active", sameProcess, cause);
    }

    static PersistenceEngineLockUnavailableException legacy(
            boolean sameProcess,
            Throwable cause
    ) {
        return unavailable("legacy", sameProcess, cause);
    }

    private static PersistenceEngineLockUnavailableException unavailable(
            String path,
            boolean sameProcess,
            Throwable cause
    ) {
        return new PersistenceEngineLockUnavailableException(
                path,
                sameProcess,
                cause
        );
    }

    boolean sameProcess() {
        return sameProcess;
    }
}
