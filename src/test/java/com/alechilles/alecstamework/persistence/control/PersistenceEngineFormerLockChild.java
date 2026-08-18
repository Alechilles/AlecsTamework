package com.alechilles.alecstamework.persistence.control;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Forked JVM fixture that holds the former persistence-engine lock file. */
public final class PersistenceEngineFormerLockChild {
    private PersistenceEngineFormerLockChild() {
    }

    public static void main(String[] args) throws Exception {
        Path lockPath = Path.of(args[0]).resolve(
                LegacyEngineLockSentinel.LEGACY_LOCK_FILENAME
        );
        try (FileChannel channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE
        ); FileLock ignored = channel.lock()) {
            System.out.println("READY");
            System.out.flush();
            new BufferedReader(new InputStreamReader(
                    System.in,
                    StandardCharsets.UTF_8
            )).readLine();
        }
    }
}
