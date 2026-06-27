package com.alechilles.alecstamework.items;

import com.alechilles.alecstamework.ui.LinkedNpcEntry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CommandTargetHudViewModelTest {
    @Test
    void copiesAttachmentRowsDefensively() {
        LinkedNpcEntry status = new LinkedNpcEntry(
                UUID.randomUUID(),
                "Test",
                10,
                10,
                0,
                0,
                null,
                0,
                0,
                0,
                0,
                true,
                false,
                false,
                false,
                false,
                false,
                0L,
                null
        );

        CommandTargetHudViewModel model = new CommandTargetHudViewModel(
                status,
                null,
                List.of(new CommandTargetHudViewModel.AttachmentRow("Coat", "Black")),
                null
        );

        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> model.attachments().add(new CommandTargetHudViewModel.AttachmentRow("Tail", "Short"))
        );
    }
}
