package com.alechilles.alecstamework.ui;

import com.hypixel.hytale.server.core.ui.PatchStyle;
import com.hypixel.hytale.server.core.ui.Value;

/** Default colors for bundled white UI symbols. Configured external artwork keeps its own colors. */
final class UiIconStyle {
    private UiIconStyle() {
    }

    static PatchStyle forTexture(String path) {
        PatchStyle style = new PatchStyle(Value.of(path));
        String color = switch (path) {
            case "TwConfigCaretDown.png",
                    "TwConfigCaretUp.png" -> "#b4bfcc";
            case "TwConfigCheck.png" -> "#000000";
            case "Tamework/CommandHotswaps/Aggressive.png",
                    "Tamework/CommandHotswaps/AttackTarget.png",
                    "Tamework/CommandHotswaps/CycleGroup.png",
                    "Tamework/CommandHotswaps/Defend.png",
                    "Tamework/CommandHotswaps/FlightToggle.png",
                    "Tamework/CommandHotswaps/Follow.png",
                    "Tamework/CommandHotswaps/Hold.png",
                    "Tamework/CommandHotswaps/Home.png",
                    "Tamework/CommandHotswaps/Idle.png",
                    "Tamework/CommandHotswaps/Link.png",
                    "Tamework/CommandHotswaps/MoveToPing.png",
                    "Tamework/CommandHotswaps/OpenMenu.png",
                    "Tamework/CommandHotswaps/Recall.png" -> "#f7eed4";
            case "Tamework/LinkedPanelIcons/Gender_Female.png" -> "#fc7cb4";
            case "Tamework/LinkedPanelIcons/Gender_Male.png" -> "#50c8ef";
            case "Tamework/LinkedPanelIcons/Need_Happiness.png" -> "#f2c97c";
            case "Tamework/LinkedPanelIcons/Need_Hunger.png" -> "#d9a066";
            case "Tamework/LinkedPanelIcons/Need_Thirst.png" -> "#84dbff";
            case "Tamework/LinkedPanelIcons/TalentPoint_UpArrow.png" -> "#d2aa1e";
            case "Tamework/LinkedPanelIcons/Trait_Appetite.png" -> "#f7e8c6";
            case "Tamework/LinkedPanelIcons/Trait_Bounty.png",
                    "Tamework/LinkedPanelIcons/Trait_Disposition.png",
                    "Tamework/LinkedPanelIcons/Trait_Fertility.png",
                    "Tamework/LinkedPanelIcons/Trait_Health.png",
                    "Tamework/LinkedPanelIcons/Trait_Size.png",
                    "Tamework/LinkedPanelIcons/Trait_Strength.png",
                    "Tamework/LinkedPanelIcons/Trait_Swiftness.png",
                    "Tamework/LinkedPanelIcons/Trait_Toughness.png" -> "#f7ecd0";
            case "Tamework/LinkedPanelIcons/Trait_Productivity.png" -> "#f5e9cd";
            case "Tamework/LinkedPanelIcons/Trait_Regrowth.png" -> "#f8eaca";
            case "Tamework/LinkedPanelIcons/Trait_ThirstEfficiency.png" -> "#f6e9cc";
            case "Tamework/PanelActions/Back_Glyph_Default.png" -> "#f7eac7";
            case "Tamework/PanelActions/BreedingOff_Glyph_Default.png",
                    "Tamework/PanelActions/BreedingOn_Glyph_Default.png",
                    "Tamework/PanelActions/FlightAirborne_Glyph_Default.png",
                    "Tamework/PanelActions/Recall_Glyph_Default.png",
                    "Tamework/PanelActions/Release_Glyph_Default.png",
                    "Tamework/PanelActions/ReturnHome_Glyph_Default.png",
                    "Tamework/PanelActions/Revive_Glyph_Default.png",
                    "Tamework/PanelActions/SetHome_Glyph_Default.png",
                    "Tamework/PanelActions/ShoulderOff_Glyph_Default.png",
                    "Tamework/PanelActions/ShoulderOn_Glyph_Default.png" -> "#f9edd5";
            case "Tamework/PanelActions/Copy_Glyph_Default.png" -> "#f2e8cd";
            case "Tamework/PanelActions/Cull_Glyph_Default.png" -> "#f67762";
            case "Tamework/PanelActions/FlightGrounded_Glyph_Default.png",
                    "Tamework/PanelActions/Locate_Glyph_Default.png",
                    "Tamework/PanelActions/Recover_Glyph_Default.png",
                    "Tamework/PanelActions/Unlink_Glyph_Default.png" -> "#f8ecd4";
            case "Tamework/PanelActions/Remove_Glyph_Default.png" -> "#f67661";
            case "Tamework/PanelActions/Settings_Glyph_Default.png" -> "#f5e7ca";
            case "Tamework/PanelControls/GroupChevron.png" -> "#c9cec8";
            case "Tamework/PanelControls/GroupEdit.png" -> "#e7e3d9";
            case "Tamework/PanelControls/Quality_Star.png" -> "#ffd447";
            case "Tamework/PanelControls/TalentPoints.png" -> "#f5e8c9";
            case "Tamework/StatusEmblems/Dead.png" -> "#f5e5c1";
            case "Tamework/StatusEmblems/Lost.png" -> "#fbe8bc";
            default -> null;
        };
        return color == null ? style : style.setColor(Value.of(color));
    }
}
