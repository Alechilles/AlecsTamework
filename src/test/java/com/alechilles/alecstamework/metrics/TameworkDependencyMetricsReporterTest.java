package com.alechilles.alecstamework.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TameworkDependencyMetricsReporterTest {

    @TempDir
    Path tempDir;

    @Test
    void reportsOnlyTrackedModsThatDependOnTamework() throws Exception {
        RecordingClient client = new RecordingClient(true);
        TameworkDependencyMetricsReporter reporter = new TameworkDependencyMetricsReporter(
                null,
                new InstalledModManifestDiscovery(null),
                client
        );

        List<InstalledModManifest> manifests = List.of(
                manifest("Alechilles", "Alec's Cats!", "1.5.6", true),
                manifest("Alechilles", "Alec's Nametags!", "1.1.2", true),
                manifest("Alechilles", "Alec's Coops!", "1.0.0", true),
                manifest("Alechilles", "Alec's Animal Husbandry!", "1.0.3", false),
                manifest("Example", "Other Mod", "1.0.0", true)
        );

        Path serverUuidFile = tempDir.resolve("hstats-server-uuid.txt");
        Files.writeString(serverUuidFile, "header\nline2\n\nenabled=true\nserver-uuid-123\n");

        reporter.reportTrackedDependencies(manifests, serverUuidFile);

        assertEquals(3, client.calls.size());
        assertEquals("fba66910-eab2-4721-b8e5-a90b6f493887", client.calls.get(0).pluginUuid);
        assertEquals("4f1d847d-57fe-4aef-8042-2e77690e2a4a", client.calls.get(1).pluginUuid);
        assertEquals("3ce09431-552e-4279-90ca-e0735bd9763b", client.calls.get(2).pluginUuid);
    }

    @Test
    void skipsReportingWhenMetricsDisabled() throws Exception {
        RecordingClient client = new RecordingClient(true);
        TameworkDependencyMetricsReporter reporter = new TameworkDependencyMetricsReporter(
                null,
                new InstalledModManifestDiscovery(null),
                client
        );

        Path serverUuidFile = tempDir.resolve("hstats-server-uuid.txt");
        Files.writeString(serverUuidFile, "header\nline2\n\nenabled=false\nserver-uuid-123\n");

        reporter.reportTrackedDependencies(
                List.of(manifest("Alechilles", "Alec's Cats!", "1.5.6", true)),
                serverUuidFile
        );

        assertEquals(0, client.calls.size());
    }

    private static InstalledModManifest manifest(String group, String name, String version, boolean dependsOnTamework) {
        return new InstalledModManifest(group, name, version, dependsOnTamework, Path.of("."), 0L);
    }

    private static final class RecordingClient implements HStatsAddPluginClient {
        private final boolean result;
        private final List<Call> calls = new ArrayList<>();

        private RecordingClient(boolean result) {
            this.result = result;
        }

        @Override
        public boolean addPlugin(String serverUuid, String pluginUuid, String pluginVersion) {
            calls.add(new Call(serverUuid, pluginUuid, pluginVersion));
            return result;
        }

        private static final class Call {
            private final String serverUuid;
            private final String pluginUuid;
            private final String pluginVersion;

            private Call(String serverUuid, String pluginUuid, String pluginVersion) {
                this.serverUuid = serverUuid;
                this.pluginUuid = pluginUuid;
                this.pluginVersion = pluginVersion;
            }
        }
    }
}
