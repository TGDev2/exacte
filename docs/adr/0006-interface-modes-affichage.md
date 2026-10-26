# 0006 - Interface: display modes, visible exactness, reusable components

## Status (Statut)

Accepted. Date: 2026-10-26. Epic E08.

## Context (Contexte)

ADR 0004 built the formatting service (four modes, exactness flags, error bound) but the window only showed the Auto
text with a minimal « ≈ » marker. E08 makes the exactness visible and the mode selectable. A later Epic replaces the
two-field form by a notebook editor: what E08 builds for the result and the history must survive that change.

## Decision (Décision)

### 1. Where the logic lives

- `CalculatorPresenter` holds the display mode, the value of the result (a `CertifiedValue`, not a text) and a cache of
  the history lines of the current mode. Changing the mode formats the same values again with `ValueFormatter`;
  nothing is calculated, no history file is written, the reusable text of the result is unchanged.
- `CalculatorViewModel` carries every text ready to show: main text, exact flag, truncated flag, error bound, secondary
  fraction, the sentence of the badge tooltip, and `HistoryLine`s (expression, formatted result, exact flag, detail).
  The `CalculationRecord`s stay in the view model for identity and for the tests.
- The secondary fraction is shown when the main text leaves out part of an exact non-integer value and does not
  already read as that fraction. `0,25` shows none; `0,333333333333…` shows `1/3`; in Fraction mode `1/3` shows none.

### 2. Components: package `calc.ui.widget`

`DisplayModeSelector`, `ExactnessBadge`, `HistoryLine` + `HistoryLineRenderer`, `Palette`, `Contrast`. They import
`calc.format.DisplayMode` and Swing, never the frame, the presenter or the engine; `calc.ui` depends on `widget`, not the
other way round. A notebook editor can host them unchanged.

### 3. A drop-down list for the mode, not segmented buttons

Four modes with names of very different lengths (`Auto`, `Scientifique`) fit one compact line in a drop-down; the arrow
keys and the first letter work from the keyboard; assistive technologies announce a combo box as a choice among named
items; it matches the operation selector. Segmented buttons would show all modes at once but need more width and their
accessible role (a group of toggles) is less uniform across the three look and feels.

### 4. Colour completes the text

The badge says « Exact » or « ≈ Approché »; the history says the same under each line; the colour is never the only
signal. Every text colour reaches 4,5:1 on its background (`PaletteContrastTest` prints the ratios). Selected history
rows keep the look and feel's own selection colours.

### 5. Preference

The mode is stored with `java.util.prefs` in the node `/calc/display`, key `mode`, as the enum name. Any failure (no
access, unknown value) is logged and means Auto; the presenter also guards the store, so a failing store never reaches the
user. Tests use a node of their own, removed afterwards.

### 6. Shortcut

`Ctrl+M` ("mode") cycles Auto, Décimal, Fraction, Scientifique. Free in the window (Escape, Ctrl+R, mnemonics with Alt),
not bound by the Swing text field, combo box, list or button of the Java and Windows look and feels (a test walks the
input maps), not `Cmd+M` of macOS (the binding uses `Ctrl`), not `F4` (opens a combo box on Windows). Not checked on
macOS and Linux look and feels.

## Alternatives considered (Alternatives considérées)

- **View-side formatting** (the frame calls `ValueFormatter` itself): rejected, it would duplicate the logic in each host.
- **Replacing `historyEntries` by the lines**: rejected, the entries are needed for reuse and identity; both lists are
  exposed, same order and size.
- **Hiding the fraction label when empty**: rejected, the window did not grow back; the line is reserved with a blank text.

## Consequences (Conséquences)

- The README lists the shortcut and the components; `docs/TESTS.md` lists the E08 tests.
- Operation names, the form and the status line are still those of the two-field window.
