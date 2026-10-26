package calc.expression;

import static calc.expression.ExpressionTestSupport.assertRefusal;
import static calc.expression.ExpressionTestSupport.evaluate;
import static calc.expression.ExpressionTestSupport.exact;
import static calc.expression.ExpressionTestSupport.line;
import static calc.expression.ExpressionTestSupport.refusal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.number.NumericError;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Every message of the language, with its code and its exact span (start included, end excluded). Lines and messages are
 * written with the placeholders of {@link ExpressionTestSupport#line(String)}.
 */
class ExpressionErrorsTest {
    private static final Resolver NAMES = TableResolver.empty()
            .withVariable("prix", exact("100"))
            .withVariable("Prix", exact("7"))
            .withVariable("faux", Resolution.inError())
            .withVariable("futur", Resolution.invalid())
            .withLine(1, exact("10"))
            .withLine(5, Resolution.inError())
            .withLine(6, Resolution.invalid());

    @ParameterizedTest(name = "{0} -> {1} [{2}, {3})")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            // Lexical errors
            "2 @ 3           | UNEXPECTED_CHARACTER | 2 | 3 | Caractère inattendu « @ ».",
            "2 + 3 !         | UNEXPECTED_CHARACTER | 6 | 7 | Caractère inattendu « ! ».",
            "2 , 3           | UNEXPECTED_CHARACTER | 2 | 3 | Caractère inattendu « , ».",
            "1,5,3 + 1       | INVALID_NUMBER | 0 | 5 | Nombre invalide « 1,5,3 ».",
            "1.000.000       | INVALID_NUMBER | 0 | 9 | Nombre invalide « 1.000.000 ».",
            "12{nb}34        | INVALID_NUMBER | 0 | 5 | Nombre invalide « 12{nb}34 ».",
            "2 + 1e10000     | SCIENTIFIC_EXPONENT_OUT_OF_RANGE | 4 | 11 | L'exposant de la notation scientifique doit être compris entre -9999 et 9999.",
            "1e1300          | NUMBER_TOO_LARGE | 0 | 6 | Nombre trop grand ou avec trop de décimales pour un calcul exact.",
            "$ + 1           | INVALID_REFERENCE | 0 | 1 | Numéro de ligne attendu après « $ » (par exemple $3).",
            "1 + $0          | INVALID_REFERENCE | 4 | 6 | Référence invalide « $0 » : les lignes sont numérotées à partir de 1.",
            "$99999999999    | INVALID_REFERENCE | 0 | 12 | Référence invalide « $99999999999 » : numéro de ligne trop grand.",
            "2 * $?          | DELETED_REFERENCE | 4 | 6 | Référence vers une ligne supprimée.",
            // Syntax errors
            "2 {x}           | MISSING_OPERAND | 2 | 3 | Opérande manquant après « {x} ».",
            "{x} 2           | MISSING_OPERAND | 0 | 1 | Opérande manquant avant « {x} ».",
            "2 + )           | MISSING_OPERAND | 2 | 3 | Opérande manquant après « + ».",
            "2 ^             | MISSING_OPERAND | 2 | 3 | Opérande manquant après « ^ ».",
            "-               | MISSING_OPERAND | 0 | 1 | Opérande manquant après « - ».",
            "3 {:} * 2       | MISSING_OPERAND | 2 | 3 | Opérande manquant après « {:} ».",
            "( )             | MISSING_OPERAND | 0 | 1 | Expression manquante après « ( ».",
            "sqrt()          | MISSING_OPERAND | 4 | 5 | Expression manquante après « ( ».",
            "x =             | MISSING_OPERAND | 2 | 3 | Expression manquante après « = ».",
            "x = # rien      | MISSING_OPERAND | 2 | 3 | Expression manquante après « = ».",
            "{v}             | MISSING_OPERAND | 0 | 1 | Opérande manquant après « {v} ».",
            "{v}-2           | MISSING_OPERAND | 0 | 1 | Opérande manquant après « {v} » : un nombre signé se met entre parenthèses, par exemple « {v}(-2) ».",
            "2 3             | MISSING_OPERATOR | 2 | 3 | Opérateur manquant (pour séparer les milliers, utilisez une espace insécable).",
            "2x              | MISSING_OPERATOR | 1 | 2 | Opérateur manquant.",
            "2e              | MISSING_OPERATOR | 1 | 2 | Opérateur manquant.",
            "2(3)            | MISSING_OPERATOR | 1 | 2 | Opérateur manquant.",
            "(1)(2)          | MISSING_OPERATOR | 3 | 4 | Opérateur manquant.",
            "ans 2           | MISSING_OPERATOR | 4 | 5 | Opérateur manquant.",
            "$1$2            | MISSING_OPERATOR | 2 | 4 | Opérateur manquant.",
            "(2 3)           | MISSING_OPERATOR | 3 | 4 | Opérateur manquant (pour séparer les milliers, utilisez une espace insécable).",
            "(2 + 3          | MISSING_CLOSING_PARENTHESIS | 0 | 1 | Parenthèse fermante manquante.",
            "((2) + 3        | MISSING_CLOSING_PARENTHESIS | 0 | 1 | Parenthèse fermante manquante.",
            "sqrt(2          | MISSING_CLOSING_PARENTHESIS | 4 | 5 | Parenthèse fermante manquante.",
            "2 + 3)          | UNEXPECTED_CLOSING_PARENTHESIS | 5 | 6 | Parenthèse fermante « ) » sans parenthèse ouvrante.",
            ")               | UNEXPECTED_CLOSING_PARENTHESIS | 0 | 1 | Parenthèse fermante « ) » sans parenthèse ouvrante.",
            "sqrt 4          | MISSING_OPENING_PARENTHESIS | 0 | 4 | Parenthèse ouvrante attendue après « sqrt ».",
            "Racine          | MISSING_OPENING_PARENTHESIS | 0 | 6 | Parenthèse ouvrante attendue après « Racine ».",
            "2 = 3           | MISPLACED_EQUALS | 2 | 3 | Le signe « = » ne peut suivre qu'un nom de variable en début de ligne.",
            "x = y = 1       | MISPLACED_EQUALS | 6 | 7 | Le signe « = » ne peut suivre qu'un nom de variable en début de ligne.",
            "= 3             | MISPLACED_EQUALS | 0 | 1 | Le signe « = » ne peut suivre qu'un nom de variable en début de ligne.",
            "(x = 1)         | MISPLACED_EQUALS | 3 | 4 | Le signe « = » ne peut suivre qu'un nom de variable en début de ligne.",
            "ans = 1         | RESERVED_NAME | 0 | 3 | « ans » est un mot réservé : choisissez un autre nom de variable.",
            "RACINE = 1      | RESERVED_NAME | 0 | 6 | « RACINE » est un mot réservé : choisissez un autre nom de variable.",
            // Evaluation errors: the span is the faulty subtree
            "1 / 0           | NUMERIC | 4 | 5 | Division par zéro : le diviseur vaut 0.",
            "1 / (2 - 2)     | NUMERIC | 4 | 11 | Division par zéro : le diviseur vaut 0.",
            "1 {:} 0,000     | NUMERIC | 4 | 9 | Division par zéro : le diviseur vaut 0.",
            "1/0 + inconnu   | NUMERIC | 2 | 3 | Division par zéro : le diviseur vaut 0.",
            "0^-1            | NUMERIC | 0 | 4 | 0 ne peut pas être élevé à une puissance négative.",
            "2^0,5           | NUMERIC | 2 | 5 | L'exposant doit être un entier.",
            "2^{v}2          | NUMERIC | 2 | 4 | L'exposant est une valeur approchée : il doit être un entier exact.",
            "2^5000          | NUMERIC | 2 | 6 | L'exposant doit être compris entre -4096 et 4096.",
            "{v}(-4)         | NUMERIC | 1 | 5 | La racine carrée d'un nombre négatif n'est pas définie en réel.",
            "sqrt(1 - 2)     | NUMERIC | 5 | 10 | La racine carrée d'un nombre négatif n'est pas définie en réel.",
            "{v}({v}2 - {v}2) | NUMERIC | 1 | 10 | La valeur sous la racine est approchée et peut être négative : sa racine carrée n'est pas définie.",
            "1 / ({v}2 - {v}2) | NUMERIC | 4 | 13 | Le diviseur est une valeur approchée qui peut valoir 0 : la division est impossible.",
            "({v}2 - {v}2)^-1 | NUMERIC | 0 | 9 | La base est une valeur approchée qui peut valoir 0 : elle ne peut pas être élevée à une puissance négative.",
            "1e1000 * 1e1000 | NUMERIC | 0 | 15 | Le résultat dépasse la taille maximale d'un calcul exact (réduisez les nombres saisis).",
            "3^4000          | NUMERIC | 0 | 6 | Le résultat dépasse la taille maximale d'un calcul exact (réduisez l'exposant ou la base).",
            "inconnu + 1     | UNKNOWN_NAME | 0 | 7 | Variable inconnue « inconnu ».",
            "PRIX            | UNKNOWN_NAME | 0 | 4 | Variable inconnue « PRIX ».",
            "2 * $4          | UNKNOWN_NAME | 4 | 6 | La ligne 4 n'existe pas.",
            "ans + 1         | UNKNOWN_NAME | 0 | 3 | « ans » n'a pas de valeur : aucune ligne précédente n'a de résultat.",
            "faux * 2        | REFERENCE_IN_ERROR | 0 | 4 | La variable « faux » est en erreur.",
            "1 + $5          | REFERENCE_IN_ERROR | 4 | 6 | La ligne 5 est en erreur.",
            "futur           | INVALID_USE | 0 | 5 | La variable « futur » ne peut pas être utilisée ici.",
            "$6 - 1          | INVALID_USE | 0 | 2 | La ligne 6 ne peut pas être utilisée ici.",
    })
    void everyMessageWithItsPosition(String text, ExpressionError error, int start, int end, String message) {
        ExpressionException refusal = refusal(text, NAMES);
        assertRefusal(refusal, error, start, end, line(message));
        assertEquals(line(text).substring(start, end), line(text).substring(refusal.span().start(), refusal.span().end()));
    }

    @Test
    void anInvisibleCharacterIsNamedByItsCode() {
        ExpressionException refusal = refusal("2 +" + (char) 7, NAMES);
        assertRefusal(refusal, ExpressionError.UNEXPECTED_CHARACTER, 3, 4, "Caractère inattendu « U+0007 ».");

        ExpressionException space = refusal("2 +" + (char) 0x2003 + "3", NAMES);
        assertRefusal(space, ExpressionError.UNEXPECTED_CHARACTER, 3, 4, "Caractère inattendu « U+2003 ».");
    }

    @Test
    void aCharacterOutsideTheBasicPlaneSpansTwoIndexes() {
        String emoji = new String(Character.toChars(0x1F600));
        ExpressionException refusal = refusal("1 + " + emoji, NAMES);
        assertRefusal(refusal, ExpressionError.UNEXPECTED_CHARACTER, 4, 6, "Caractère inattendu « " + emoji + " ».");
    }

    @Test
    void ansInErrorOrNotAllowed() {
        assertRefusal(refusal("ans", TableResolver.empty().withAns(Resolution.inError())),
                ExpressionError.REFERENCE_IN_ERROR, 0, 3, "« ans » désigne une ligne en erreur.");
        assertRefusal(refusal("1 + ans", TableResolver.empty().withAns(Resolution.invalid())),
                ExpressionError.INVALID_USE, 4, 7, "« ans » ne peut pas être utilisé ici.");
    }

    @Test
    void theLineLengthIsCheckedFirst() throws ExpressionException {
        String longest = "#" + "x".repeat(999);
        assertEquals(ParsedLine.Kind.COMMENT, ExpressionParser.parseLine(longest).kind());

        ExpressionException refusal = refusal("@" + "1".repeat(1000), NAMES);
        assertRefusal(refusal, ExpressionError.LINE_TOO_LONG, 1000, 1001, "La ligne est trop longue (1000 caractères maximum).");
    }

    @Test
    void theNestingDepthIsChecked() throws ExpressionException {
        assertEquals(exact("1"), evaluate("(".repeat(100) + "1" + ")".repeat(100), NAMES));
        assertEquals(exact("1"), evaluate("-".repeat(100) + "1", NAMES));

        String message = "Trop d'imbrications (100 niveaux maximum).";
        assertRefusal(refusal("(".repeat(101) + "1" + ")".repeat(101), NAMES), ExpressionError.TOO_DEEP, 100, 101, message);
        assertRefusal(refusal("-".repeat(101) + "1", NAMES), ExpressionError.TOO_DEEP, 100, 101, message);
        assertRefusal(refusal(line("{v}").repeat(101) + "4", NAMES), ExpressionError.TOO_DEEP, 100, 101, message);
        // 2^2^...: the 101st '^' is at index 2 * 101 - 1.
        assertRefusal(refusal("2^".repeat(101) + "1", NAMES), ExpressionError.TOO_DEEP, 201, 202, message);
        assertRefusal(refusal("sqrt(".repeat(101) + "1" + ")".repeat(101), NAMES), ExpressionError.TOO_DEEP, 504, 505,
                message);
    }

    @Test
    void theNumberOfNodesIsChecked() throws ExpressionException {
        // 250 numbers and 249 additions: 499 nodes.
        assertEquals(exact("250"), evaluate("1+".repeat(249) + "1", NAMES));
        // 251 numbers: the 251st addition is node 501, built just after the number at index 500.
        assertRefusal(refusal("1+".repeat(250) + "1", NAMES), ExpressionError.TOO_MANY_NODES, 500, 501,
                "Expression trop longue (500 éléments maximum).");
    }

    @Test
    void numericErrorsCarryTheCodeOfTheCore() {
        assertEquals(Optional.of(NumericError.DIVISION_BY_ZERO), refusal("1 / 0", NAMES).numericError());
        assertEquals(Optional.of(NumericError.ROOT_OF_NEGATIVE), refusal(line("{v}(-1)"), NAMES).numericError());
        assertEquals(Optional.of(NumericError.SIZE_LIMIT_EXCEEDED), refusal("3^4000", NAMES).numericError());
        assertEquals(Optional.empty(), refusal("2 @", NAMES).numericError());
        assertEquals(Optional.empty(), refusal("inconnu", NAMES).numericError());
    }

    @Test
    void everyCodeBelongsToItsStage() {
        Set<ExpressionError> lexical = EnumSet.of(ExpressionError.LINE_TOO_LONG, ExpressionError.UNEXPECTED_CHARACTER,
                ExpressionError.INVALID_NUMBER, ExpressionError.SCIENTIFIC_EXPONENT_OUT_OF_RANGE,
                ExpressionError.NUMBER_TOO_LARGE, ExpressionError.INVALID_REFERENCE, ExpressionError.DELETED_REFERENCE);
        Set<ExpressionError> evaluation = EnumSet.of(ExpressionError.NUMERIC, ExpressionError.UNKNOWN_NAME,
                ExpressionError.REFERENCE_IN_ERROR, ExpressionError.INVALID_USE);
        for (ExpressionError error : ExpressionError.values()) {
            ExpressionError.Stage expected = lexical.contains(error) ? ExpressionError.Stage.LEXICAL
                    : evaluation.contains(error) ? ExpressionError.Stage.EVALUATION : ExpressionError.Stage.SYNTAX;
            assertEquals(expected, error.stage(), error.name());
        }
        assertTrue(ExpressionError.values().length >= 20);
    }
}
