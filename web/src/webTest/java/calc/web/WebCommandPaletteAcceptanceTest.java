package calc.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import calc.web.BrowserDriver.ColorScheme;
import calc.web.BrowserDriver.Json;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The command palette of the page (Ctrl+K, Cmd+K, the « Commandes » button), in a real headless browser. The driver page
 * ({@code page-palette-driver.html}) sends real keyboard events to the focused element, types in {@code #command-search},
 * clicks the buttons by their text and reloads the iframe; it lists the fixed selectors it reads: {@code #command-palette},
 * {@code #command-search}, and the entries {@code #command-palette [role="option"]} with a {@code .command-label}, a
 * {@code .command-shortcut} when they have one, and {@code aria-selected="true"} on the selected one.
 *
 * <p>What the palette runs is compared with what the button or the selector does: the same sequence is played once through
 * the palette and once through the buttons (or {@code #display-mode}), and the notebooks must be the same.
 *
 * <p>A field is « shown » when its box has a width and a height and {@code checkVisibility()} is true, never from
 * {@code innerText}, which is always empty for an {@code <input>}; its text is read through {@code .value}.
 */
@DisplayName("La page : palette de commandes (Ctrl+K), recherche, clavier et thèmes")
class WebCommandPaletteAcceptanceTest {
    private static final int DESKTOP = 1024;
    private static final int PHONE = 360;
    private static final int MINIMUM_TOUCH_SIZE = 44;
    private static final double TOLERANCE = 1.0;

    private static final String NOTEBOOK_KEY = "calc.web.notebook.v1";
    private static final String COMMANDS_BUTTON = "Commandes";
    private static final String SEARCH_LABEL = "Rechercher une commande";
    private static final String NO_COMMAND = "Aucune commande";
    private static final String NEXT_MODE = "Mode suivant";
    private static final Path TOKENS = Path.of("web", "site", "tokens.css");

    /** The eight buttons of the toolbar, by their visible label. */
    private static final List<String> ACTIONS = List.of("Annuler", "Rétablir", "Insérer", "Monter", "Descendre", "Supprimer",
            "Effacer le carnet", "Carnet d'exemple");
    private static final List<String> MODE_OPTIONS = List.of("Auto", "Décimal", "Fraction", "Scientifique");
    private static final List<String> MODES = List.of("Mode Auto", "Mode Décimal", "Mode Fraction", "Mode Scientifique");
    private static final List<String> THEMES = List.of("Thème : Clair", "Thème : Sombre", "Thème : Système");
    private static final List<String> ALL = concat(ACTIONS, MODES, List.of(NEXT_MODE), THEMES);

    /** The part of the shortcut the page must show, in lower case, for each entry that has one (the desktop's Ctrl+Z, Ctrl+Maj+K…). */
    private static final Map<String, String> SHORTCUT_KEYS = Map.of("Annuler", "z", "Rétablir", "y", "Insérer", "entr",
            "Monter", "↑", "Descendre", "↓", "Supprimer", "k", NEXT_MODE, "m");

    private static final List<String> EXAMPLE = List.of("# Carnet d'exemple : des résultats exacts", "(1/3)*3", "√2",
            "prix = 12,5", "prix * 3", "$2 + $5", "2 +");
    private static final List<String> WITH_A_BLANK_LINE_AFTER_THE_THIRD = List.of(EXAMPLE.get(0), "(1/3)*3", "√2", "", "prix = 12,5",
            "prix * 3", "$2 + $6", "2 +");

    /** The observation of each scenario, or the failure of its driver: a scenario is played once per run of the class. */
    private static final Map<String, Object> PLAYED = new HashMap<>();

    // ---- Opening ----------------------------------------------------------------------------------------------------------

    /** Ctrl+K, Cmd+K, and Ctrl+K with nothing focused: a dialog that is not modal, with a labelled field that holds the focus. */
    @Test
    void ctrlKAndCmdKOpenANonModalDialogWithAnEmptyLabelledSearchField() throws Exception {
        Observation page = opening();

        assertFalse(page.palette("closed").get("shown").bool(), "La palette est visible avant toute commande");
        for (String snapshot : List.of("ctrlOpen", "metaOpen", "bodyOpen")) {
            assertOpenDialog(page, snapshot);
        }
        assertClean(page);
    }

    @Test
    void ctrlKAndCmdKAreTakenFromTheBrowser() throws Exception {
        Observation page = opening();

        List<Json> shortcuts = page.root().get("keys").items().stream()
                .filter(key -> List.of("ctrl+k", "meta+k").contains(key.get("name").text())).toList();
        assertEquals(4, shortcuts.size(), "Le scénario envoie Ctrl+K trois fois et Cmd+K une fois");
        for (Json key : shortcuts) {
            assertTrue(key.get("prevented").bool(), "La page n'appelle pas preventDefault pour " + key.get("name").text());
        }
    }

    @Test
    void theOtherKeysAreStillLeftToTheBrowserAndChangeNothing() throws Exception {
        Observation page = otherKeys();

        List<Json> keys = page.root().get("keys").items();
        assertEquals(List.of("ctrl+c", "ctrl+a", "ctrl+x", "ctrl+v", "Tab", "Home", "ctrl+k"),
                keys.stream().map(key -> key.get("name").text()).toList(), "Les touches du scénario");
        for (Json key : keys.subList(0, keys.size() - 1)) {
            assertFalse(key.get("prevented").bool(), "La page a pris " + key.get("name").text() + " au navigateur");
        }
        assertFalse(page.palette("others").get("shown").bool(), "Aucune de ces touches n'ouvre la palette");
        assertEquals(EXAMPLE, page.values("others"));
        assertTrue(keys.get(keys.size() - 1).get("prevented").bool(), "Ctrl+K, lui, est pris au navigateur");
        assertOpenDialog(page, "opened");
    }

    /** « Vide à l'ouverture » : what was typed before is gone, and every command is listed again. */
    @Test
    void aSecondOpeningStartsWithAnEmptyFieldAndAllTheCommands() throws Exception {
        Observation page = opening();

        assertEquals("sombre", page.palette("typed").get("search").get("value").text());
        assertEquals(List.of("Thème : Sombre"), page.labels("typed"));
        assertFalse(page.palette("afterEscape").get("shown").bool(), "Échap ferme la palette");
        assertOpenDialog(page, "reopened");
        assertEquals(ALL.size(), page.entries("reopened").size());
    }

    // ---- The list of the commands -------------------------------------------------------------------------------------------

    @Test
    void theListHoldsTheEightActionsTheFourModesNextModeAndTheThreeThemes() throws Exception {
        Observation page = opening();

        assertEquals(ACTIONS, strings(page.state("ctrlOpen").get("actionLabels")), "Les boutons data-action de la page");
        assertEquals(MODE_OPTIONS, strings(page.state("ctrlOpen").get("modeOptions")), "Les options de #display-mode");
        List<String> labels = page.labels("ctrlOpen");
        assertEquals(16, labels.size(), "8 actions, 4 modes, Mode suivant et 3 thèmes : " + labels);
        assertEquals(sorted(ALL), sorted(labels), "Les libellés de la palette");
        for (Json entry : page.entries("ctrlOpen")) {
            assertTrue(entry.get("shown").bool(), "L'entrée " + entry.get("label").text() + " n'est pas visible");
        }
    }

    /** The rule of the desktop: « Mode » then the text of the option, « Thème : » then the name, the visible text of the button. */
    @Test
    void theLabelsFollowTheRuleOfTheDesktopPalette() throws Exception {
        Observation page = opening();
        Json state = page.state("ctrlOpen");

        List<String> expected = new ArrayList<>(strings(state.get("actionLabels")));
        strings(state.get("modeOptions")).forEach(option -> expected.add("Mode " + option));
        expected.add(NEXT_MODE);
        expected.addAll(THEMES);
        assertEquals(sorted(expected), sorted(page.labels("ctrlOpen")));
    }

    @Test
    void eachEntryShowsItsShortcutWhenItHasOne() throws Exception {
        Observation page = opening();

        for (Json entry : page.entries("ctrlOpen")) {
            String label = entry.get("label").text();
            String shortcut = entry.get("shortcut").isNull() ? "" : entry.get("shortcut").text();
            String key = SHORTCUT_KEYS.get(label);
            if (key == null) {
                assertEquals("", shortcut, label + " n'a pas de raccourci");
            } else {
                assertTrue(shortcut.toLowerCase(java.util.Locale.ROOT).contains(key),
                        "Le raccourci de « " + label + " » doit contenir « " + key + " » : « " + shortcut + " »");
            }
        }
    }

    @Test
    void theFirstEntryIsSelectedAndTheOthersAreNot() throws Exception {
        Observation page = opening();

        assertEquals(List.of(0), page.selected("ctrlOpen"), "Une seule entrée sélectionnée, la première");
    }

    /** The unique labels of the buttons: the entries are options of a list, not buttons that repeat « Annuler ». */
    @Test
    void theButtonLabelsStayUniqueWhetherThePaletteIsOpenOrNot() throws Exception {
        Observation page = opening();

        for (String snapshot : List.of("closed", "ctrlOpen")) {
            List<String> labels = page.state(snapshot).get("buttons").items().stream().map(button -> button.get("text").text()).toList();
            assertEquals(labels.size(), labels.stream().distinct().count(), "Libellés de boutons en double (" + snapshot + ") : " + labels);
            assertTrue(labels.containsAll(ACTIONS), "Les huit boutons de la barre d'outils (" + snapshot + ")");
        }
    }

    // ---- The search -----------------------------------------------------------------------------------------------------------

    @Test
    void theSearchIgnoresCaseAndAccents() throws Exception {
        Observation page = search();

        assertEquals(List.of("Thème : Sombre"), page.labels("sombre"));
        assertEquals(List.of("Thème : Sombre"), page.labels("upperSombre"));
        assertEquals(List.of("Mode Décimal"), page.labels("decimal"));
        assertEquals(List.of("Mode Décimal"), page.labels("accentedDecimal"));
        assertEquals(List.of("Rétablir"), page.labels("retablir"));
        assertFalse(page.palette("sombre").get("text").text().contains(NO_COMMAND), "Il y a un résultat : pas de « Aucune commande »");
        assertEquals(List.of(0), page.selected("sombre"), "Le résultat est sélectionné");
    }

    @Test
    void withoutAnyResultThePaletteSaysNoCommandAndEnterChangesNothing() throws Exception {
        Observation page = search();

        assertEquals(List.of(), page.labels("none"));
        assertTrue(page.palette("none").get("text").text().contains(NO_COMMAND), page.palette("none").get("text").text());
        assertTrue(page.palette("none").get("shown").bool());
        assertEquals(EXAMPLE, page.values("afterEnterOnNothing"));
        assertEquals("AUTO", page.state("afterEnterOnNothing").get("mode").text());
        assertEquals(List.of(), page.state("none").get("messages").items(), "Aucun message de plus (role status ou alert)");
        assertEquals(List.of(), page.root().get("pageErrors").items());
    }

    @Test
    void escapeClosesThePaletteWithoutRunningTheSelectedCommand() throws Exception {
        Observation page = search();

        assertEquals(List.of("Effacer le carnet"), page.labels("beforeEscape"), "La commande à ne pas exécuter est bien sélectionnée");
        assertFalse(page.palette("escaped").get("shown").bool(), "Échap ferme la palette");
        assertFalse(page.palette("escaped").get("inside").bool(), "Le focus a quitté la palette");
        assertEquals(EXAMPLE, page.values("escaped"), "Le carnet n'a pas été effacé");
        assertEquals("AUTO", page.state("escaped").get("mode").text());
    }

    // ---- The keyboard -----------------------------------------------------------------------------------------------------------

    /** ↑ and ↓ move the selection; Enter runs the selected entry, closes the palette and gives the focus back to the current line. */
    @Test
    void theArrowsMoveTheSelectionAndEnterRunsTheSelectedEntry() throws Exception {
        Observation page = keyboard();

        List<String> modes = page.labels("first");
        assertTrue(modes.size() >= 3, "« mode » doit laisser au moins trois entrées : " + modes);
        assertTrue(modes.stream().allMatch(label -> label.startsWith("Mode")), modes.toString());
        assertEquals(List.of(0), page.selected("first"));
        assertEquals(List.of(1), page.selected("down1"));
        assertEquals(List.of(2), page.selected("down2"));
        assertEquals(List.of(1), page.selected("up1"));
        assertEquals(List.of(2), page.selected("down2again"));
        assertEquals(modes, page.labels("down2again"), "La sélection ne change pas la liste");

        String chosen = modes.get(2);
        assertFalse(page.palette("done").get("shown").bool(), "Entrée ferme la palette");
        assertEquals(modeAfter(chosen, "SCIENTIFIC"), page.state("done").get("mode").text(), "Entrée a exécuté « " + chosen + " »");
        assertEquals(3, page.state("done").get("activeLine").number(), "Le focus revient à la ligne courante");
        assertEquals(EXAMPLE, page.values("done"));
    }

    // ---- What the commands do ----------------------------------------------------------------------------------------------------

    /** Enter runs exactly what the button does: the same sequence through the palette and through the buttons gives the same notebook. */
    @Test
    void theEightActionsDoWhatTheirButtonsDo() throws Exception {
        Observation viaPalette = actions("palette");
        Observation viaButtons = actions("buttons");

        for (String snapshot : List.of("inserted", "deleted", "movedUp", "movedDown", "undone", "redone", "cleared", "example")) {
            assertEquals(viaButtons.fingerprint(snapshot), viaPalette.fingerprint(snapshot), "Le carnet après « " + snapshot + " »");
        }
        assertEquals(WITH_A_BLANK_LINE_AFTER_THE_THIRD, viaPalette.values("inserted"), "Insérer ajoute une ligne vide sous la ligne 3");
        assertEquals(EXAMPLE, viaPalette.values("deleted"), "Supprimer retire la ligne vide");
        assertEquals(List.of(), viaPalette.values("cleared"), "Effacer le carnet");
        assertEquals(EXAMPLE, viaPalette.values("example"), "Carnet d'exemple");
        assertNotEquals(viaPalette.fingerprint("deleted"), viaPalette.fingerprint("movedUp"), "Monter déplace une ligne");
        assertNotEquals(viaPalette.fingerprint("movedUp"), viaPalette.fingerprint("undone"), "Annuler défait le déplacement");
        assertNotEquals(viaPalette.fingerprint("undone"), viaPalette.fingerprint("redone"), "Rétablir le refait");
        assertClean(viaPalette);
    }

    @Test
    void afterAnActionThePaletteIsClosedAndALineHoldsTheFocus() throws Exception {
        Observation page = actions("palette");

        for (String snapshot : List.of("inserted", "deleted", "movedUp", "movedDown", "undone", "redone", "cleared", "example")) {
            assertFalse(page.palette(snapshot).get("shown").bool(), "La palette est restée ouverte après " + snapshot);
            assertTrue(page.state(snapshot).get("activeLine").number() >= 1, "Le focus n'est sur aucune ligne après " + snapshot);
        }
    }

    @Test
    void theModeEntriesDoWhatTheSelectorDoes() throws Exception {
        Observation viaPalette = modes(true);
        Observation viaSelector = modes(false);

        List<String> expectedModes = List.of("DECIMAL", "FRACTION", "SCIENTIFIC", "AUTO");
        List<String> snapshots = List.of("decimal", "fraction", "scientific", "auto");
        for (int index = 0; index < snapshots.size(); index++) {
            String snapshot = snapshots.get(index);
            assertEquals(expectedModes.get(index), viaPalette.state(snapshot).get("mode").text(), snapshot);
            assertEquals(viaSelector.fingerprint(snapshot), viaPalette.fingerprint(snapshot), "Le carnet en mode " + snapshot);
            assertFalse(viaPalette.palette(snapshot).get("shown").bool(), "La palette est restée ouverte (" + snapshot + ")");
            assertEquals(3, viaPalette.state(snapshot).get("activeLine").number(), "Le focus revient à la ligne courante (" + snapshot + ")");
        }
        assertNotEquals(viaPalette.fingerprint("decimal"), viaPalette.fingerprint("fraction"), "Les modes changent l'affichage");
        assertClean(viaPalette);
    }

    /** « Mode suivant » passes #display-mode to the next option, like the menu of the desktop, and comes back to the first. */
    @Test
    void nextModePassesToTheNextOptionOfTheSelector() throws Exception {
        Observation page = modes(true);

        assertEquals(List.of("DECIMAL", "FRACTION", "SCIENTIFIC", "AUTO"),
                List.of(page.state("next1").get("mode").text(), page.state("next2").get("mode").text(),
                        page.state("next3").get("mode").text(), page.state("next4").get("mode").text()));
    }

    // ---- The themes -----------------------------------------------------------------------------------------------------------------

    @Test
    void choosingTheDarkThemeSetsDataThemeOnTheRootAndSystemRemovesIt() throws Exception {
        Observation page = themes();
        String lightBase = token(":root", "--color-surface-base");
        String darkBase = token("[data-theme=\"sombre\"]", "--color-surface-base");
        String forcedLightBase = token("[data-theme=\"clair\"]", "--color-surface-base");
        assertNotEquals(lightBase, darkBase, "tokens.css donne le même fond en clair et en sombre");

        assertFalse(page.state("first").get("hasTheme").bool(), "Sans choix, la racine n'a pas de data-theme");
        assertEquals(lightBase, page.state("first").get("surfaceBase").text());
        assertEquals("sombre", page.state("dark").get("theme").text());
        assertEquals(darkBase, page.state("dark").get("surfaceBase").text(), "Les jetons sombres s'appliquent");
        assertEquals("clair", page.state("light").get("theme").text());
        assertEquals(forcedLightBase, page.state("light").get("surfaceBase").text());
        assertFalse(page.state("system").get("hasTheme").bool(), "Thème : Système retire data-theme");
        assertEquals(lightBase, page.state("system").get("surfaceBase").text());
        for (String snapshot : List.of("dark", "light", "system")) {
            assertFalse(page.palette(snapshot).get("shown").bool(), "La palette est restée ouverte (" + snapshot + ")");
        }
        assertClean(page);
    }

    @Test
    void theChosenThemeIsKeptUnderItsOwnKeyAndComesBackAfterAReload() throws Exception {
        Observation page = themes();

        assertEquals("sombre", page.state("darkAfterReload").get("theme").text(), "Le thème sombre est retrouvé");
        assertEquals("clair", page.state("lightAfterReload").get("theme").text(), "Le thème clair est retrouvé");
        assertFalse(page.state("systemAfterReload").get("hasTheme").bool(), "Le choix Système est retrouvé : pas de data-theme");
        for (String snapshot : List.of("dark", "light")) {
            List<String> keys = strings(page.state(snapshot).get("storageKeys"));
            List<String> others = keys.stream().filter(key -> !NOTEBOOK_KEY.equals(key)).toList();
            assertFalse(others.isEmpty(), "Le choix du thème doit avoir sa propre clé, hors " + NOTEBOOK_KEY + " : " + keys);
            assertEquals(page.state("first").get("notebook").text(), page.state(snapshot).get("notebook").text(),
                    "Le carnet gardé n'est pas touché par le choix du thème (" + snapshot + ")");
        }
    }

    /** Every access to the storage sits in a try/catch: the theme still applies when the storage refuses. */
    @Test
    void theThemeStillAppliesWhenTheStorageThrows() throws Exception {
        for (String kind : List.of("methods", "property")) {
            Observation page = brokenStorage(kind);

            assertEquals("sombre", page.state("dark").get("theme").text(), "Thème : Sombre avec un stockage en panne (" + kind + ")");
            assertFalse(page.palette("dark").get("shown").bool(), "La palette est restée ouverte (" + kind + ")");
            assertFalse(page.state("system").get("hasTheme").bool(), "Thème : Système avec un stockage en panne (" + kind + ")");
            assertEquals(List.of(), page.root().get("pageErrors").items(), "Une exception a échappé à la page (" + kind + ")");
        }
    }

    // ---- The button ---------------------------------------------------------------------------------------------------------------

    @Test
    void theCommandsButtonOpensThePaletteAndFitsAPhone() throws Exception {
        Observation page = phone();

        Json button = page.button("closed", COMMANDS_BUTTON);
        assertTrue(button.get("shown").bool(), "Le bouton Commandes n'est pas visible à 360 px");
        assertTrue(button.get("width").number() >= MINIMUM_TOUCH_SIZE, "Le bouton Commandes est trop étroit : " + button.get("width").number());
        assertTrue(button.get("height").number() >= MINIMUM_TOUCH_SIZE, "Le bouton Commandes est trop bas : " + button.get("height").number());
        assertTrue(button.get("right").number() <= page.state("closed").get("layout").get("clientWidth").number() + TOLERANCE,
                "Le bouton Commandes sort de l'écran");
        assertFalse(page.palette("closed").get("shown").bool());

        assertOpenDialog(page, "opened");
        assertEquals(ALL.size(), page.entries("opened").size());
        Json box = page.palette("opened").get("box");
        Json layout = page.state("opened").get("layout");
        assertTrue(box.get("left").number() >= -TOLERANCE, "La palette sort à gauche de l'écran");
        assertTrue(box.get("right").number() <= layout.get("innerWidth").number() + TOLERANCE, "La palette sort à droite de l'écran");
        for (String snapshot : List.of("closed", "opened")) {
            Json size = page.state(snapshot).get("layout");
            assertTrue(size.get("scrollWidth").number() <= size.get("clientWidth").number() + TOLERANCE,
                    "Défilement horizontal à 360 px (" + snapshot + ")");
        }
        assertFalse(page.palette("afterEscape").get("shown").bool(), "Échap ferme la palette ouverte par le bouton");
        assertClean(page);
    }

    // ---- The scenarios ---------------------------------------------------------------------------------------------------------------

    private static Observation opening() throws Exception {
        return drive("opening", DESKTOP, null, new Script().notebook("AUTO", EXAMPLE).load()
                .focus(3).snapshot("closed")
                .key("k", "ctrl").snapshot("ctrlOpen")
                .key("Escape").snapshot("ctrlClosed")
                .key("k", "meta").snapshot("metaOpen")
                .key("Escape")
                .blur().key("k", "ctrl").snapshot("bodyOpen")
                .type("sombre").snapshot("typed")
                .key("Escape").snapshot("afterEscape")
                .key("k", "ctrl").snapshot("reopened")
                .key("Escape"));
    }

    private static Observation otherKeys() throws Exception {
        return drive("otherKeys", DESKTOP, null, new Script().notebook("AUTO", EXAMPLE).load()
                .focus(3).key("c", "ctrl").key("a", "ctrl").key("x", "ctrl").key("v", "ctrl").key("Tab").key("Home")
                .snapshot("others")
                .key("k", "ctrl").snapshot("opened"));
    }

    private static Observation search() throws Exception {
        return drive("search", DESKTOP, null, new Script().notebook("AUTO", EXAMPLE).load()
                .focus(3).key("k", "ctrl")
                .type("sombre").snapshot("sombre")
                .type("SOMBRE").snapshot("upperSombre")
                .type("decimal").snapshot("decimal")
                .type("DÉCIMAL").snapshot("accentedDecimal")
                .type("retablir").snapshot("retablir")
                .type("zzzz").snapshot("none")
                .key("Enter").snapshot("afterEnterOnNothing")
                .key("Escape")
                .focus(3).key("k", "ctrl").type("Effacer le carnet").snapshot("beforeEscape")
                .key("Escape").snapshot("escaped"));
    }

    private static Observation keyboard() throws Exception {
        return drive("keyboard", DESKTOP, null, new Script().notebook("SCIENTIFIC", EXAMPLE).load()
                .focus(3).key("k", "ctrl").type("mode").snapshot("first")
                .key("ArrowDown").snapshot("down1")
                .key("ArrowDown").snapshot("down2")
                .key("ArrowUp").snapshot("up1")
                .key("ArrowDown").snapshot("down2again")
                .key("Enter").snapshot("done"));
    }

    /** The same sequence of the eight actions, through the palette or through the buttons of the toolbar. */
    private static Observation actions(String how) throws Exception {
        boolean palette = how.equals("palette");
        Script script = new Script().notebook("AUTO", EXAMPLE).load().focus(3);
        script.act(palette, "Insérer").snapshot("inserted");
        script.act(palette, "Supprimer").snapshot("deleted");
        script.focus(2).act(palette, "Monter").snapshot("movedUp");
        script.act(palette, "Descendre").snapshot("movedDown");
        script.act(palette, "Annuler").snapshot("undone");
        script.act(palette, "Rétablir").snapshot("redone");
        script.act(palette, "Effacer le carnet").snapshot("cleared");
        script.act(palette, "Carnet d'exemple").snapshot("example");
        return drive("actions-" + how, DESKTOP, null, script);
    }

    private static Observation modes(boolean palette) throws Exception {
        Script script = new Script().notebook("AUTO", EXAMPLE).load().focus(3);
        if (palette) {
            script.command("Mode Décimal").snapshot("decimal")
                    .command("Mode Fraction").snapshot("fraction")
                    .command("Mode Scientifique").snapshot("scientific")
                    .command("Mode Auto").snapshot("auto")
                    .command(NEXT_MODE).snapshot("next1")
                    .command(NEXT_MODE).snapshot("next2")
                    .command(NEXT_MODE).snapshot("next3")
                    .command(NEXT_MODE).snapshot("next4");
        } else {
            script.mode("Décimal").snapshot("decimal")
                    .mode("Fraction").snapshot("fraction")
                    .mode("Scientifique").snapshot("scientific")
                    .mode("Auto").snapshot("auto");
        }
        return drive("modes-" + (palette ? "palette" : "selector"), DESKTOP, null, script);
    }

    /** The browser prefers the light scheme here: without a choice, the root shows the light tokens. */
    private static Observation themes() throws Exception {
        return drive("themes", DESKTOP, ColorScheme.LIGHT, new Script().notebook("AUTO", EXAMPLE).load()
                .snapshot("first")
                .command("Thème : Sombre").snapshot("dark")
                .reload().snapshot("darkAfterReload")
                .command("Thème : Clair").snapshot("light")
                .reload().snapshot("lightAfterReload")
                .command("Thème : Sombre")
                .command("Thème : Système").snapshot("system")
                .reload().snapshot("systemAfterReload"));
    }

    private static Observation brokenStorage(String kind) throws Exception {
        return drive("broken-" + kind, DESKTOP, ColorScheme.LIGHT, new Script().loadBroken(kind)
                .focus(2).command("Thème : Sombre").snapshot("dark")
                .command("Thème : Système").snapshot("system"));
    }

    private static Observation phone() throws Exception {
        return drive("phone", PHONE, null, new Script().notebook("AUTO", EXAMPLE).load()
                .snapshot("closed")
                .click(COMMANDS_BUTTON).snapshot("opened")
                .key("Escape").snapshot("afterEscape"));
    }

    // ---- Assertions -----------------------------------------------------------------------------------------------------------------

    /** The dialog is shown, not modal, and its field is a labelled empty input that holds the focus; no message is added to the page. */
    private static void assertOpenDialog(Observation page, String snapshot) {
        Json palette = page.palette(snapshot);
        assertTrue(palette.get("exists").bool(), "Pas de #command-palette (" + snapshot + ")");
        assertTrue(palette.get("shown").bool(), "#command-palette n'est pas visible (" + snapshot + ")");
        assertEquals("dialog", palette.get("role").isNull() ? "" : palette.get("role").text(), "role de #command-palette (" + snapshot + ")");
        assertTrue(palette.get("ariaModal").isNull() || !"true".equals(palette.get("ariaModal").text()),
                "La palette n'est pas modale (aria-modal) (" + snapshot + ")");
        Json search = palette.get("search");
        assertFalse(search.isNull(), "Pas de #command-search (" + snapshot + ")");
        assertEquals("INPUT", search.get("tag").text(), "#command-search est un <input> (" + snapshot + ")");
        assertTrue(search.get("inPalette").bool(), "#command-search est dans #command-palette (" + snapshot + ")");
        assertTrue(search.get("shown").bool(), "#command-search n'est pas affiché (boîte non vide et checkVisibility) (" + snapshot + ")");
        assertEquals("", search.get("value").text(), "Le champ est vide à l'ouverture (" + snapshot + ")");
        assertTrue(strings(search.get("labels")).contains(SEARCH_LABEL), "Le libellé du champ : " + search.get("labels").value() + " (" + snapshot + ")");
        assertEquals("command-search", page.state(snapshot).get("activeId").isNull() ? "" : page.state(snapshot).get("activeId").text(),
                "Le champ de recherche a le focus (" + snapshot + ")");
        assertTrue(palette.get("inside").bool(), "Le focus est dans la palette (" + snapshot + ")");
        assertEquals(List.of(), page.state(snapshot).get("messages").items(), "La palette ne montre aucun message role status ou alert (" + snapshot + ")");
    }

    private static void assertClean(Observation page) {
        assertEquals(List.of(), page.root().get("pageErrors").items(), "Une exception a échappé à la page");
        assertEquals(List.of(), page.root().get("addedElements").items(), "La page n'ajoute que du texte : ni img, ni script, ni iframe");
        for (String resource : strings(page.root().get("resources"))) {
            assertTrue(resource.startsWith("/"), "Ressource externe : " + resource);
        }
    }

    /** What « Mode … » leaves in #display-mode when the page was in {@code before}. */
    private static String modeAfter(String label, String before) {
        return switch (label) {
            case "Mode Auto" -> "AUTO";
            case "Mode Décimal" -> "DECIMAL";
            case "Mode Fraction" -> "FRACTION";
            case "Mode Scientifique" -> "SCIENTIFIC";
            case NEXT_MODE -> List.of("AUTO", "DECIMAL", "FRACTION", "SCIENTIFIC")
                    .get((List.of("AUTO", "DECIMAL", "FRACTION", "SCIENTIFIC").indexOf(before) + 1) % 4);
            default -> fail("Une entrée « " + label + " » dans une recherche « mode »");
        };
    }

    /** A value of tokens.css in the block that opens with {@code opening}, found by its brace: the header comment names the selectors. */
    private static String token(String opening, String property) throws IOException {
        String css = Files.readString(TOKENS, UTF_8);
        int block = css.indexOf("\n" + opening + " {");
        assertTrue(block >= 0, "tokens.css n'a pas de bloc « " + opening + " {");
        Matcher matcher = Pattern.compile(Pattern.quote(property) + ":\\s*([^;]+);").matcher(css);
        assertTrue(matcher.find(block), "tokens.css ne définit pas " + property + " après " + opening);
        return matcher.group(1).trim();
    }

    private static List<String> strings(Json list) {
        return list.items().stream().map(Json::text).toList();
    }

    private static List<String> sorted(List<String> labels) {
        List<String> copy = new ArrayList<>(labels);
        Collections.sort(copy);
        return copy;
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> all = new ArrayList<>();
        for (List<String> list : lists) {
            all.addAll(list);
        }
        return List.copyOf(all);
    }

    // ---- The driver -------------------------------------------------------------------------------------------------------------------

    private record Observation(Json root) {
        Json state(String snapshot) {
            return root.get("snapshots").get(snapshot);
        }

        Json palette(String snapshot) {
            return state(snapshot).get("palette");
        }

        List<Json> entries(String snapshot) {
            return palette(snapshot).get("entries").items();
        }

        List<String> labels(String snapshot) {
            return entries(snapshot).stream().map(entry -> {
                assertFalse(entry.get("label").isNull(), "Une entrée sans .command-label dans " + snapshot);
                return entry.get("label").text();
            }).toList();
        }

        /** The positions of the entries that carry aria-selected="true". */
        List<Integer> selected(String snapshot) {
            List<Json> entries = entries(snapshot);
            List<Integer> positions = new ArrayList<>();
            for (int index = 0; index < entries.size(); index++) {
                if (entries.get(index).get("selected").bool()) {
                    positions.add(index);
                }
            }
            return positions;
        }

        /** The texts of the lines of the notebook: the page always ends with a blank line that is not yet in it. */
        List<String> values(String snapshot) {
            List<String> values = new ArrayList<>(state(snapshot).get("rows").items().stream().map(row -> row.get("value").text()).toList());
            assertFalse(values.isEmpty(), "Aucune ligne dans " + snapshot);
            assertEquals("", values.remove(values.size() - 1), "La page doit finir par une ligne de saisie vide (" + snapshot + ")");
            return values;
        }

        /** Everything the notebook shows: the lines with their results, the mode and the state of Annuler and Rétablir. */
        String fingerprint(String snapshot) {
            return state(snapshot).get("rows").value() + " mode=" + state(snapshot).get("mode").text()
                    + " annuler=" + button(snapshot, "Annuler").get("disabled").bool()
                    + " rétablir=" + button(snapshot, "Rétablir").get("disabled").bool();
        }

        Json button(String snapshot, String label) {
            List<Json> found = state(snapshot).get("buttons").items().stream().filter(button -> label.equals(button.get("text").text())).toList();
            assertEquals(1, found.size(), "Il faut un seul bouton « " + label + " » : "
                    + state(snapshot).get("buttons").items().stream().map(button -> button.get("text").text()).toList());
            return found.get(0);
        }
    }

    /** The steps of a scenario, in the words of {@code page-palette-driver.html}. */
    private static final class Script {
        private final List<String> steps = new ArrayList<>();

        private Script add(String step) {
            steps.add(step);
            return this;
        }

        Script notebook(String mode, List<String> lines) {
            StringJoiner joined = new StringJoiner(",", "[", "]");
            lines.forEach(line -> joined.add(quoted(line)));
            return add("{\"store\":{\"version\":1,\"mode\":" + quoted(mode) + ",\"lines\":" + joined + "}}");
        }

        Script load() {
            return add("{\"load\":\"normal\"}");
        }

        Script loadBroken(String kind) {
            return add("{\"load\":" + quoted(kind) + "}");
        }

        Script reload() {
            return add("{\"reload\":true}");
        }

        Script focus(int number) {
            return add("{\"focus\":" + number + "}");
        }

        Script blur() {
            return add("{\"blur\":true}");
        }

        Script type(String text) {
            return add("{\"type\":" + quoted(text) + "}");
        }

        Script key(String key, String... modifiers) {
            StringBuilder step = new StringBuilder("{\"key\":").append(quoted(key));
            for (String modifier : modifiers) {
                step.append(",\"").append(modifier).append("\":true");
            }
            return add(step.append('}').toString());
        }

        Script click(String label) {
            return add("{\"click\":" + quoted(label) + "}");
        }

        Script mode(String label) {
            return add("{\"mode\":" + quoted(label) + "}");
        }

        Script snapshot(String name) {
            return add("{\"snapshot\":" + quoted(name) + "}");
        }

        /** Runs a command through the palette: open it, type its label, which keeps it alone, and press Enter. */
        Script command(String label) {
            return key("k", "ctrl").type(label).key("Enter");
        }

        /** The command through the palette, or the button of the same label. */
        Script act(boolean palette, String label) {
            return palette ? command(label) : click(label);
        }

        String scenario(int width) {
            StringJoiner joined = new StringJoiner(",", "[", "]");
            steps.forEach(joined::add);
            return "{\"width\":" + width + ",\"steps\":" + joined + "}";
        }
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    /** Plays a scenario once per run of the class, whatever the number of tests that read it; a failure is kept too. */
    private static Observation drive(String name, int width, ColorScheme scheme, Script script) throws IOException, InterruptedException {
        Object known = PLAYED.get(name);
        if (known instanceof Observation observation) {
            return observation;
        }
        if (known instanceof AssertionError failure) {
            throw failure;
        }
        String query = "scenario=" + URLEncoder.encode(script.scenario(width), UTF_8);
        try {
            String text = scheme == null ? BrowserDriver.run("page-palette-driver.html", query)
                    : BrowserDriver.run("page-palette-driver.html", query, scheme);
            Json root = BrowserDriver.parseJson(text);
            if (!root.get("error").isNull()) {
                throw new AssertionError("La page pilote s'est arrêtée dans le scénario " + name + " : " + root.get("error").text());
            }
            Observation played = new Observation(root);
            PLAYED.put(name, played);
            return played;
        } catch (AssertionError failure) {
            PLAYED.put(name, failure);
            throw failure;
        }
    }
}
