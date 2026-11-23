package calc.ui.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class DesignTokensTest {

    private static Properties minimal() {
        Properties properties = new Properties();
        properties.setProperty("color.light.ink", "#000000");
        properties.setProperty("color.light.paper", "#FFFFFF");
        properties.setProperty("color.dark.ink", "#ffffff");
        properties.setProperty("color.dark.paper", "#101010");
        properties.setProperty("font.ui.family", "Inter");
        properties.setProperty("font.mono.family", "JetBrains Mono");
        properties.setProperty("font.size.body", "14");
        properties.setProperty("space.2", "8");
        properties.setProperty("space.10", "40");
        properties.setProperty("space.1", "4");
        properties.setProperty("radius.small", "4");
        properties.setProperty("contrast.ui.ring", "ink on paper");
        properties.setProperty("contrast.text.body", "ink on paper");
        return properties;
    }

    private static String refusal(String key, String value) {
        Properties properties = minimal();
        if (value == null) {
            properties.remove(key);
        } else {
            properties.setProperty(key, value);
        }
        return assertThrows(IllegalStateException.class, () -> DesignTokens.parse(properties)).getMessage();
    }

    @Test
    void theTokensOfTheApplicationAreReadOnceAndShared() {
        assertSame(DesignTokens.load(), DesignTokens.load());
        assertTrue(DesignTokens.load().colorRoles().contains("surface.base"));
        assertEquals("Inter", DesignTokens.load().fontFamily("ui"));
        assertEquals("JetBrains Mono", DesignTokens.load().fontFamily("mono"));
    }

    @Test
    void aWellFormedSetGivesItsColoursSizesFontsAndPairs() {
        DesignTokens tokens = DesignTokens.parse(minimal());
        assertEquals(List.of("ink", "paper"), tokens.colorRoles());
        assertEquals(Color.WHITE, tokens.color(Theme.DARK, "ink"));
        assertEquals(new Color(16, 16, 16), tokens.color(Theme.DARK, "paper"));
        assertEquals(40, tokens.size("space.10"));
        assertEquals(List.of("font.size.body", "space.1", "space.2", "space.10", "radius.small"), tokens.sizeKeys());
        assertEquals(List.of(new DesignTokens.ContrastPair("contrast.text.body", true, "ink", "paper"),
                new DesignTokens.ContrastPair("contrast.ui.ring", false, "ink", "paper")), tokens.contrastPairs());
    }

    @Test
    void anUnknownSizeOrFontIsRefusedWithItsName() {
        DesignTokens tokens = DesignTokens.parse(minimal());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> tokens.size("space.3")).getMessage().contains("space.3"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> tokens.fontFamily("serif")).getMessage().contains("serif"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> tokens.color(Theme.LIGHT, "sky")).getMessage().contains("sky"));
    }

    @Test
    void aMalformedSetIsRefusedWithTheFaultyKey() {
        assertTrue(refusal("color.dark.ink", "#FFF").contains("color.dark.ink"));
        assertTrue(refusal("color.dark.paper", null).contains("color.dark.*"));
        assertTrue(refusal("color.dark.sky", "#0000FF").contains("color.dark.*"));
        assertTrue(refusal("space.3", "10").contains("space.3"));
        assertTrue(refusal("radius.small", "4px").contains("radius.small"));
        assertTrue(refusal("font.size.body", "-1").contains("font.size.body"));
        assertTrue(refusal("font.ui.family", " ").contains("font.ui.family"));
        assertTrue(refusal("contrast.text.body", "ink over paper").contains("contrast.text.body"));
        assertTrue(refusal("contrast.text.body", "sky on paper").contains("sky"));
        assertTrue(refusal("contrast.ui.ring", "ink on sky").contains("sky"));
        assertTrue(refusal("shadow.small", "2").contains("shadow.small"));
        assertTrue(refusal("font.ui.weight", "400").contains("font.ui.weight"));
    }

    @Test
    void aSetWithoutColoursIsRefused() {
        Properties properties = new Properties();
        properties.setProperty("space.1", "4");
        assertTrue(assertThrows(IllegalStateException.class, () -> DesignTokens.parse(properties)).getMessage().contains("color.light"));
    }

    @Test
    void theFileIsReadAsUtf8AndAMissingOneIsNamed() throws IOException {
        String text = "color.light.ink=#000000\ncolor.dark.ink=#FFFFFF\nfont.ui.family=Évidence\n";
        DesignTokens tokens = DesignTokens.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals("Évidence", tokens.fontFamily("ui"));
        assertTrue(assertThrows(IllegalStateException.class, () -> DesignTokens.read(null)).getMessage().contains("tokens.properties"));
    }

    @Test
    void theTokensOfTheApplicationHaveNoEmptyGroup() {
        DesignTokens tokens = DesignTokens.load();
        assertFalse(tokens.contrastPairs().isEmpty());
        assertFalse(tokens.sizeKeys().isEmpty());
        assertEquals("light", Theme.LIGHT.key());
        assertEquals("dark", Theme.DARK.key());
    }
}
