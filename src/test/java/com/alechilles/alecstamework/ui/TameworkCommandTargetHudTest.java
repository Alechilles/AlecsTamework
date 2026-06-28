package com.alechilles.alecstamework.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TameworkCommandTargetHudTest {
    @Test
    void refreshUpdatesExistingHudInsteadOfShowingAgain() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/ui/TameworkCommandTargetHud.java"
        ));

        int refreshMethod = source.indexOf("public void refresh");
        int bindCall = source.indexOf("CommandTargetHudBinder.bind(commandBuilder, updatedModel, updatedLanguage)", refreshMethod);
        int updateCall = source.indexOf("update(false, commandBuilder)", refreshMethod);
        int keepVisibleMethod = source.indexOf("public void keepVisible()", refreshMethod);
        String refreshBody = source.substring(refreshMethod, keepVisibleMethod);

        Assertions.assertTrue(refreshMethod >= 0);
        Assertions.assertTrue(bindCall > refreshMethod);
        Assertions.assertTrue(updateCall > bindCall);
        Assertions.assertTrue(keepVisibleMethod > updateCall);
        Assertions.assertFalse(refreshBody.contains("show()"));
    }

    @Test
    void keepVisibleResendsCurrentHudWithoutChangingModel() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/ui/TameworkCommandTargetHud.java"
        ));

        int keepVisibleMethod = source.indexOf("public void keepVisible()");
        int showCall = source.indexOf("show()", keepVisibleMethod);
        int hideMethod = source.indexOf("public void hideNow()", keepVisibleMethod);
        String keepVisibleBody = source.substring(keepVisibleMethod, hideMethod);

        Assertions.assertTrue(keepVisibleMethod >= 0);
        Assertions.assertTrue(showCall > keepVisibleMethod);
        Assertions.assertFalse(keepVisibleBody.contains("this.model"));
        Assertions.assertFalse(keepVisibleBody.contains("CommandTargetHudBinder.bind"));
    }

    @Test
    void hideNowSendsClearPacketInsteadOfOnlyTogglingRootVisibility() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/alechilles/alecstamework/ui/TameworkCommandTargetHud.java"
        ));

        int hideMethod = source.indexOf("public void hideNow()");
        int clearUpdate = source.indexOf("update(true, commandBuilder)", hideMethod);

        Assertions.assertTrue(hideMethod >= 0);
        Assertions.assertTrue(clearUpdate > hideMethod);
        Assertions.assertFalse(source.substring(hideMethod).contains("#Root.Visible\", false"));
    }
}
