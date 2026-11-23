package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.ui.design.DesignTokens;
import calc.ui.theme.ThemeManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.swing.JLabel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The result of a row, alone: its integer part ends on the column of the commas, whether the row is painted on screen or
 * printed (the screenshots print it), at the scale of a normal screen or of a 150 % one, whatever text hints the look and
 * feel gave the component, and even when the metrics of its font were asked before the font was installed. Java keeps the
 * metrics of a font by its name, style and size: a window opened before {@link ThemeManager#installFonts()} (as the timing
 * tests do) left the metrics of the fallback font under the name JetBrains Mono, and four-digit integers drawn in the real
 * one ended 3 px right of the column (NotebookRowsAcceptanceTest, run after those tests).
 */
class AlignedResultTest {
    private static final List<String> INTEGERS = List.of("0", "437", "1250", "5167", "6201", "123456789");
    private static final List<String> DECIMALS = List.of("437,5", "5167,5", "0,2", "516,75", "0,333333333333…");
    private static final String DONE = "DONE";

    @BeforeAll
    static void installTheFonts() {
        ThemeManager.installFonts();
    }

    @Test
    @DisplayName("des métriques demandées avant l'installation des polices ne déplacent pas la colonne des virgules")
    void metricsAskedBeforeTheFontsWereInstalledDoNotMoveTheColumn() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(java, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                FontsInstalledLate.class.getName()).redirectErrorStream(true).start();
        List<String> lines;
        try (BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            lines = output.lines().toList();
        }
        System.out.println("Fonts installed late, in a fresh process: " + lines);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the probe ends");
        assertTrue(lines.contains(DONE), "the probe ran to its end: " + lines);
        List<String> ends = lines.stream().filter(line -> line.startsWith("END ")).toList();
        assertEquals(INTEGERS.size(), ends.size(), "one measure per integer: " + lines);
        for (String line : ends) {
            String[] fields = line.split(" ");
            int end = Integer.parseInt(fields[2]);
            int anchor = Integer.parseInt(fields[3]);
            int cell = Integer.parseInt(fields[4]);
            assertTrue(end >= anchor - cell / 2 && end <= anchor + 1,
                    fields[1] + " ends at " + end + ", the column of the commas is at " + anchor + " (" + lines + ")");
        }
    }

    /**
     * Run in a fresh process: asks the metrics of JetBrains Mono before the fonts are installed, as a window opened without
     * {@link ThemeManager#installFonts()} does, then installs them and paints each integer; prints {@code END text end anchor
     * cell} for each, then {@value #DONE}.
     */
    public static final class FontsInstalledLate {
        public static void main(String[] args) {
            Font early = new Font(DesignTokens.load().fontFamily("mono"), Font.PLAIN, DesignTokens.load().size("font.size.result"));
            Graphics2D graphics = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
            graphics.setFont(early);
            System.out.println("EARLY " + new JLabel().getFontMetrics(early).charWidth('0') + " " + graphics.getFontMetrics().charWidth('0'));
            graphics.dispose();
            ThemeManager.installFonts();
            NotebookRowView.AlignedResult result = result();
            int cell = (int) Math.round(result.getFont().createGlyphVector(new FontRenderContext(null, true, false), "0")
                    .getGlyphPosition(1).getX());
            for (String integer : INTEGERS) {
                int end = lastInk(render(result, integer, integer, true, 1.0)) + 1;
                System.out.println("END " + integer + " " + end + " " + result.anchor() + " " + cell);
            }
            System.out.println(DONE);
        }
    }

    static Stream<Arguments> ways() {
        Stream.Builder<Arguments> ways = Stream.builder();
        for (Object antialiasing : new Object[] {"none", RenderingHints.VALUE_TEXT_ANTIALIAS_OFF, RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB}) {
            for (boolean printed : new boolean[] {false, true}) {
                for (double scale : new double[] {1.0, 1.5}) {
                    ways.add(Arguments.of(printed, scale, antialiasing));
                }
            }
        }
        return ways.build();
    }

    @ParameterizedTest(name = "printed {0}, scale {1}, text antialiasing of the component {2}")
    @MethodSource("ways")
    @DisplayName("la partie entière finit sur la colonne des virgules, peinte ou imprimée, à 100 % ou 150 %, quel que soit le lissage")
    void theIntegerPartEndsOnTheColumnOfTheCommas(boolean printed, double scale, Object antialiasing) {
        NotebookRowView.AlignedResult result = result();
        if (!"none".equals(antialiasing)) {
            // A look and feel gives its components the text hints of the desktop; the result must not depend on them.
            result.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING, antialiasing);
        }
        int anchor = (int) Math.round(result.anchor() * scale);
        double cell = result.getFontMetrics(result.getFont()).charWidth('0') * scale;
        for (String integer : INTEGERS) {
            int end = lastInk(render(result, integer, integer, printed, scale)) + 1;
            assertTrue(end >= anchor - cell / 2 && end <= anchor + Math.ceil(scale),
                    integer + " ends at " + end + ", the column of the commas is at " + anchor);
        }
        for (String decimal : DECIMALS) {
            String integer = decimal.substring(0, decimal.indexOf(','));
            boolean[] alone = render(result, integer, integer, printed, scale);
            boolean[] followed = render(result, decimal, integer, printed, scale);
            int before = anchor - (int) Math.ceil(scale);
            for (int column = 0; column < before; column++) {
                assertEquals(alone[column], followed[column],
                        decimal + ": its integer part is drawn where " + integer + " alone is drawn (column " + column + ")");
            }
            assertTrue(lastInk(followed) + 1 >= anchor + cell, decimal + ": its decimals come after the column of the commas");
        }
    }

    private static NotebookRowView.AlignedResult result() {
        NotebookRowView.AlignedResult result = new NotebookRowView.AlignedResult();
        result.setFont(new Font(DesignTokens.load().fontFamily("mono"), Font.PLAIN, DesignTokens.load().size("font.size.result")));
        result.setForeground(Color.BLACK);
        result.setSize(330, 32);
        return result;
    }

    // The columns of the picture that hold ink.
    private static boolean[] render(NotebookRowView.AlignedResult result, String text, String integer, boolean printed,
                                    double scale) {
        result.show(text, integer);
        BufferedImage image = new BufferedImage((int) Math.ceil(result.getWidth() * scale), (int) Math.ceil(result.getHeight() * scale),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.scale(scale, scale);
            if (printed) {
                result.printAll(graphics);
            } else {
                result.paint(graphics);
            }
        } finally {
            graphics.dispose();
        }
        boolean[] ink = new boolean[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int darkness = 3 * 255 - ((rgb >> 16) & 0xFF) - ((rgb >> 8) & 0xFF) - (rgb & 0xFF);
                if (darkness > 96) {
                    ink[x] = true;
                }
            }
        }
        return ink;
    }

    private static int lastInk(boolean[] ink) {
        for (int column = ink.length - 1; column >= 0; column--) {
            if (ink[column]) {
                return column;
            }
        }
        return -1;
    }
}
