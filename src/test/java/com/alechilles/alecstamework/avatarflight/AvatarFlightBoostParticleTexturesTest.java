package com.alechilles.alecstamework.avatarflight;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies generated AvatarFlight boost sprites remain usable Hytale particle textures. */
class AvatarFlightBoostParticleTexturesTest {
    private static final Path TEXTURE_ROOT = Path.of(
            "src/main/resources/Common/Particles/Textures/Tamework/AvatarFlight/Boost"
    );
    private static final Set<String> EXPECTED_TEXTURES = Set.of(
            "Forward_Compression_Arc.png",
            "Forward_Wind_Lance.png",
            "Upward_Downwash_Fan.png",
            "Upward_Lift_Ribbon.png"
    );

    @Test
    void boostTexturesHaveExpectedDimensionsAndAlpha() throws IOException {
        try (var files = Files.list(TEXTURE_ROOT)) {
            Set<String> names = files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
            assertEquals(EXPECTED_TEXTURES, names);
        }

        for (String name : EXPECTED_TEXTURES) {
            BufferedImage image = ImageIO.read(TEXTURE_ROOT.resolve(name).toFile());
            assertNotNull(image, name + " is not a readable PNG");
            assertEquals(64, image.getWidth(), name + " has the wrong width");
            assertEquals(64, image.getHeight(), name + " has the wrong height");
            assertTrue(image.getColorModel().hasAlpha(), name + " has no alpha channel");
            assertEquals(0, alphaAt(image, 0, 0), name + " has a visible corner");

            int visible = 0;
            int partialAlpha = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int alpha = alphaAt(image, x, y);
                    if (alpha > 8) {
                        visible++;
                    }
                    if (alpha > 0 && alpha < 255) {
                        partialAlpha++;
                    }
                }
            }
            double coverage = visible / (double) (image.getWidth() * image.getHeight());
            assertTrue(coverage > 0.02 && coverage < 0.35,
                    name + " has unusable visible coverage: " + coverage);
            assertTrue(partialAlpha > 100, name + " lost its soft alpha edges");
        }
    }

    @Test
    void boostSilhouettesRemainDirectionalAfterDownscaling() throws IOException {
        assertHorizontal("Forward_Wind_Lance.png");
        assertHorizontal("Forward_Compression_Arc.png");
        assertHorizontal("Upward_Downwash_Fan.png");

        Rectangle liftBounds = visibleBounds("Upward_Lift_Ribbon.png");
        assertTrue(liftBounds.height > liftBounds.width,
                "upward lift ribbon must remain vertically readable");
    }

    private static void assertHorizontal(String name) throws IOException {
        Rectangle bounds = visibleBounds(name);
        assertTrue(bounds.width > bounds.height, name + " must remain horizontally readable");
    }

    private static Rectangle visibleBounds(String name) throws IOException {
        BufferedImage image = ImageIO.read(TEXTURE_ROOT.resolve(name).toFile());
        int minX = image.getWidth();
        int minY = image.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (alphaAt(image, x, y) > 8) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        assertTrue(maxX >= minX && maxY >= minY, name + " is blank");
        return new Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }

    private static int alphaAt(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) >>> 24;
    }
}
