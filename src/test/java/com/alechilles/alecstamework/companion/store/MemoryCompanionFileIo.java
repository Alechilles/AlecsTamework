package com.alechilles.alecstamework.companion.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonDocument;

final class MemoryCompanionFileIo implements CompanionFileIo {
    final Map<Path, BsonDocument> files = new ConcurrentHashMap<>();
    final List<Path> writeOrder = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger failNextWrites = new AtomicInteger();
    volatile CountDownLatch blockWrites;

    @Override
    public CompletableFuture<Void> write(Path file, BsonDocument document) {
        CountDownLatch gate = blockWrites;
        if (gate != null) {
            try { gate.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (failNextWrites.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            return CompletableFuture.failedFuture(new IOException("injected write failure"));
        }
        files.put(file, document.clone());
        writeOrder.add(file);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public BsonDocument readNow(Path file) {
        return files.get(file);
    }

    @Override
    public CompletableFuture<Void> delete(Path file) {
        files.remove(file);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public List<Path> list(Path directory) {
        return files.keySet().stream().filter(p -> directory.equals(p.getParent())).sorted().toList();
    }

    @Override
    public void moveAside(Path file, String suffix) {
        BsonDocument doc = files.remove(file);
        if (doc != null) {
            files.put(file.resolveSibling(file.getFileName() + suffix), doc);
        }
    }

    int writesTo(Path file) {
        return (int) writeOrder.stream().filter(file::equals).count();
    }
}
