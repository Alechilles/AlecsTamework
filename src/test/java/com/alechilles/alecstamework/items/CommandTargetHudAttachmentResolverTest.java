package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.npc.attachments.ResolvedAttachmentDisplay;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetHudAttachmentResolverTest {
    @Test
    void capsAttachmentRowsAndReportsFriendlyLabels() {
        CommandTargetHudAttachmentResolver resolver = new CommandTargetHudAttachmentResolver(
                (roleId, modelId, attachments) -> List.of(
                        new ResolvedAttachmentDisplay("Coat", "Coat", "Black", "Black"),
                        new ResolvedAttachmentDisplay("Horns", "Horns", "Curled", "Curled"),
                        new ResolvedAttachmentDisplay("Mane", "Mane", "Long", "Long"),
                        new ResolvedAttachmentDisplay("Tail", "Tail", "Short", "Short")
                )
        );

        List<CommandTargetHudViewModel.AttachmentRow> rows = resolver.resolveRows(
                "Role",
                "Model",
                Map.of("Coat", "Black", "Horns", "Curled", "Mane", "Long", "Tail", "Short"),
                3
        );

        Assertions.assertEquals(3, rows.size());
        Assertions.assertEquals("Coat", rows.get(0).setLabel());
        Assertions.assertEquals("Black", rows.get(0).valueLabel());
    }
}
