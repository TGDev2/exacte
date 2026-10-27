package calc.ui;

import calc.format.FormattedValue;

/** The sentences that explain the exactness of a formatted result; shared by every window that shows results. */
public final class ResultDescription {
    private ResultDescription() {
    }

    /** Explains the exactness mark: the error bound of an approximate value, the exact value behind a cut text. */
    public static String describe(FormattedValue result) {
        if (!result.exact()) {
            return "Erreur maximale : " + result.errorBound().orElseThrow();
        }
        if (result.truncated()) {
            return "Valeur exacte : " + result.exactFraction().orElse(result.canonical()) + " (affichage arrondi)";
        }
        return "";
    }

    /** The exact fraction is secondary information when the main text cuts the value and does not already show it. */
    public static String secondaryFraction(FormattedValue result) {
        return result.exactFraction()
                .filter(fraction -> result.truncated() && !fraction.equals(result.text()))
                .orElse("");
    }
}
