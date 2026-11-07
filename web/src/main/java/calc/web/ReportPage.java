package calc.web;

/**
 * The conformity report as a page of the site: the Markdown is shown as it is, in a {@code pre} element, with {@code <},
 * {@code >} and {@code &} escaped, so that a report can never add a tag to the page. Pure Java, no file access: the files
 * are read and written by {@code calc.web.tools.ReportPageMain}, on the build machine.
 */
public final class ReportPage {
    private static final String HEAD = """
            <!DOCTYPE html>
            <html lang="fr">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Rapport de conformité</title>
            <style>
            :root { color-scheme: light dark; }
            body { margin: 0 auto; max-width: 70rem; padding: 1.5rem 1rem; font-family: "Segoe UI", system-ui, sans-serif; line-height: 1.5; }
            pre { overflow-x: auto; padding: 1rem; border: 1px solid #8884; border-radius: 0.4rem; font-family: ui-monospace, Consolas, monospace; font-size: 0.9rem; }
            </style>
            </head>
            <body>
            <p><a href="index.html">← Retour à la calculatrice</a></p>
            <h1>Rapport de conformité</h1>
            <pre>""";

    private static final String TAIL = """
            </pre>
            </body>
            </html>
            """;

    private ReportPage() {
    }

    /** The page for this Markdown report; the report is the whole content of the {@code pre} element. */
    public static String render(String markdown) {
        StringBuilder page = new StringBuilder(HEAD.length() + markdown.length() + TAIL.length());
        page.append(HEAD);
        for (int i = 0; i < markdown.length(); i++) {
            char c = markdown.charAt(i);
            switch (c) {
                case '&' -> page.append("&amp;");
                case '<' -> page.append("&lt;");
                case '>' -> page.append("&gt;");
                default -> page.append(c);
            }
        }
        page.append(TAIL);
        return page.toString();
    }
}
