package com.alechilles.alecstamework.persistence.sqlite;

import org.sqlite.SQLiteJDBCLoader;
import org.sqlite.util.LibraryLoaderUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Extracts a renamed bundled sqlite-jdbc native library to a real platform filename before the driver loads.
 */
final class SqliteNativeLibraryBootstrap {
    static final String SQLITE_LIB_PATH_PROPERTY = "org.sqlite.lib.path";
    static final String SQLITE_LIB_NAME_PROPERTY = "org.sqlite.lib.name";

    private static final String RESOURCE_SUFFIX = ".bin";
    private static final String EXTRACT_ROOT_DIR = "alecstamework-sqlite";
    private static final Object LOCK = new Object();

    private static volatile boolean prepared;

    private SqliteNativeLibraryBootstrap() {
    }

    static void prepare() {
        if (prepared) {
            return;
        }
        synchronized (LOCK) {
            if (prepared) {
                return;
            }
            if (hasExternalNativeOverride()) {
                prepared = true;
                return;
            }

            String nativeLibRoot = LibraryLoaderUtil.getNativeLibResourcePath();
            String nativeLibName = LibraryLoaderUtil.getNativeLibName();
            String resourcePath = buildBundledResourcePath(nativeLibRoot, nativeLibName);
            if (!resourceExists(resourcePath)) {
                prepared = true;
                return;
            }

            try {
                Path extractionDir = resolveExtractionDirectory(nativeLibRoot);
                Files.createDirectories(extractionDir);

                Path extractedLibrary = extractionDir.resolve(nativeLibName);
                extractIfMissing(resourcePath, extractedLibrary);
                markExecutable(extractedLibrary);

                System.setProperty(SQLITE_LIB_PATH_PROPERTY, extractionDir.toString());
                System.setProperty(SQLITE_LIB_NAME_PROPERTY, nativeLibName);
                prepared = true;
            } catch (IOException ex) {
                throw new IllegalStateException("sqlite_native_library_bootstrap_failed", ex);
            }
        }
    }

    static String currentBundledResourcePath() {
        return buildBundledResourcePath(
                LibraryLoaderUtil.getNativeLibResourcePath(),
                LibraryLoaderUtil.getNativeLibName()
        );
    }

    static Path currentExtractionPath() {
        String nativeLibRoot = LibraryLoaderUtil.getNativeLibResourcePath();
        return resolveExtractionDirectory(nativeLibRoot).resolve(LibraryLoaderUtil.getNativeLibName());
    }

    static String buildBundledResourcePath(String nativeLibRoot, String nativeLibName) {
        return nativeLibRoot + "/" + nativeLibName + RESOURCE_SUFFIX;
    }

    private static boolean hasExternalNativeOverride() {
        return isNotBlank(System.getProperty(SQLITE_LIB_PATH_PROPERTY))
                || isNotBlank(System.getProperty(SQLITE_LIB_NAME_PROPERTY));
    }

    private static boolean resourceExists(String resourcePath) {
        return SqliteNativeLibraryBootstrap.class.getResource(resourcePath) != null;
    }

    private static Path resolveExtractionDirectory(String nativeLibRoot) {
        String normalizedRoot = nativeLibRoot.startsWith("/")
                ? nativeLibRoot.substring(1)
                : nativeLibRoot;
        return Path.of(System.getProperty("java.io.tmpdir"), EXTRACT_ROOT_DIR, SQLiteJDBCLoader.getVersion())
                .resolve(normalizedRoot);
    }

    private static void extractIfMissing(String resourcePath, Path extractedLibrary) throws IOException {
        if (Files.exists(extractedLibrary) && Files.size(extractedLibrary) > 0L) {
            return;
        }

        Path tempFile = Files.createTempFile(extractedLibrary.getParent(), "sqlitejdbc-", ".tmp");
        try (InputStream input = openResource(resourcePath)) {
            Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tempFile, extractedLibrary, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tempFile);
        }
        extractedLibrary.toFile().deleteOnExit();
    }

    private static InputStream openResource(String resourcePath) throws IOException {
        InputStream input = SqliteNativeLibraryBootstrap.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IOException("Missing bundled sqlite resource: " + resourcePath);
        }
        return input;
    }

    private static void markExecutable(Path extractedLibrary) {
        extractedLibrary.toFile().setReadable(true);
        extractedLibrary.toFile().setWritable(true, true);
        extractedLibrary.toFile().setExecutable(true);
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}
