package com.alechilles.alecstamework.persistence.control;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Forked JVM fixture that holds one engine lease until its parent closes stdin. */
public final class PersistenceEngineLeaseChild {
    private PersistenceEngineLeaseChild() {
    }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        PersistenceEngineLineage lineage =
                PersistenceEngineLineage.valueOf(args[1]);
        try (PersistenceEngineLease ignored =
                     lineage == PersistenceEngineLineage.LEGACY_PUBLIC
                             ? PersistenceEngineLease.acquireLegacy(directory)
                             : PersistenceEngineLease.acquireReplacement(directory)) {
            System.out.println("READY");
            System.out.flush();
            new BufferedReader(new InputStreamReader(
                    System.in,
                    StandardCharsets.UTF_8
            )).readLine();
        }
    }
}
