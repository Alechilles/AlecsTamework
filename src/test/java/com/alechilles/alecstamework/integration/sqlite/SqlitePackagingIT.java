package com.alechilles.alecstamework.integration.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the shadow jar still loads the SQLite driver's native library after its unused
 * platform natives are trimmed. The driver comes only from the packaged jar (child-first loader),
 * so a missing native for this platform fails here instead of in the one-time importer.
 */
class SqlitePackagingIT {
    @Test
    void packagedDriverOpensAWritableDatabaseOnThisPlatform(@TempDir Path directory) throws Exception {
        URL packagedUrl = Path.of(System.getProperty("patchwork.packagedJar")).toUri().toURL();
        try (URLClassLoader loader = new SqliteChildFirstClassLoader(packagedUrl, getClass().getClassLoader())) {
            Driver driver = (Driver) Class.forName("org.sqlite.JDBC", true, loader)
                    .getDeclaredConstructor().newInstance();
            Path database = directory.resolve("packaged.sqlite");
            try (Connection connection = driver.connect("jdbc:sqlite:" + database, new Properties())) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE companion (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO companion (id, name) VALUES (?, ?)")) {
                    insert.setInt(1, 7);
                    insert.setString(2, "Biscuit");
                    assertEquals(1, insert.executeUpdate());
                }
                try (Statement statement = connection.createStatement();
                        ResultSet rows = statement.executeQuery("SELECT name FROM companion WHERE id = 7")) {
                    assertTrue(rows.next());
                    assertEquals("Biscuit", rows.getString(1));
                }
            }
            assertTrue(Files.size(database) > 0);
        }
    }

    /** Loads org.sqlite from the packaged jar before delegating everything else to the parent. */
    private static final class SqliteChildFirstClassLoader extends URLClassLoader {
        private SqliteChildFirstClassLoader(URL packagedUrl, ClassLoader parent) {
            super(new URL[]{packagedUrl}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("org.sqlite.")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = findClass(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }
}
