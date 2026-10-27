package calc.notebook;

import static calc.notebook.NotebookTestSupport.assertError;
import static calc.notebook.NotebookTestSupport.assertValue;
import static calc.notebook.NotebookTestSupport.evaluated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.expression.ExpressionError;
import calc.expression.ParsedLine;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The meaning of {@code ans}, {@code $n}, variables and errors in a notebook, line by line, with exact messages and
 * positions (docs/adr/0008-semantique-carnet.md, sections 2 and 3).
 */
class NotebookSemanticsTest {
    @Test
    void ansChainsThroughTheLinesThatHaveAValue() {
        EvaluatedNotebook notebook = evaluated("2", "ans * 3", "", "# note", "ans + 1", "x = ans / 7", "ans");
        assertValue("2", notebook.result(1));
        assertValue("6", notebook.result(2));
        assertEquals(ParsedLine.Kind.EMPTY, notebook.result(3).kind());
        assertFalse(notebook.result(3).hasValue() || notebook.result(3).isError());
        assertEquals(ParsedLine.Kind.COMMENT, notebook.result(4).kind());
        assertValue("7", notebook.result(5));
        assertValue("1", notebook.result(6));
        assertValue("1", notebook.result(7));
    }

    @Test
    void ansSkipsTheLinesInErrorLikeTheCalculatorKey() {
        EvaluatedNotebook notebook = evaluated("5", "1 / 0", "ans + 1");
        assertTrue(notebook.result(2).isError());
        assertValue("6", notebook.result(3));
    }

    @Test
    void ansWithoutAnyPreviousResultIsAnError() {
        String message = "Aucun résultat précédent : « ans » n'a pas de valeur.";
        assertError(ExpressionError.UNKNOWN_NAME, 0, 3, message, evaluated("ans").result(1));
        assertError(ExpressionError.UNKNOWN_NAME, 4, 7, message, evaluated("# titre", "", "1 / 0", "2 + ans").result(4));
    }

    @Test
    void aReferenceDesignatesALineAbove() {
        EvaluatedNotebook notebook = evaluated("10", "# commentaire", "$1 * 2 + $1", "$3 - $1");
        assertValue("30", notebook.result(3));
        assertValue("20", notebook.result(4));
        assertEquals(List.of(notebook.result(1).id()), notebook.result(3).references());
        assertEquals(List.of(notebook.result(3).id(), notebook.result(1).id()), notebook.result(4).references());
    }

    @Test
    void aReferenceToTheLineItselfOrBelowIsAlwaysAnError() {
        EvaluatedNotebook notebook = evaluated("$2 + 1", "3", "$3", "1 + $9");
        assertError(ExpressionError.INVALID_USE, 0, 2, "Référence vers une ligne qui n'est pas au-dessus (ligne 2).",
                notebook.result(1));
        assertError(ExpressionError.INVALID_USE, 0, 2, "Référence vers une ligne qui n'est pas au-dessus (ligne 3).",
                notebook.result(3));
        assertError(ExpressionError.INVALID_USE, 4, 6, "Référence vers une ligne qui n'est pas au-dessus (ligne 9).",
                notebook.result(4));
        assertEquals(List.of(), notebook.result(1).references());
    }

    @Test
    void aReferenceToAnEmptyOrCommentLineHasItsOwnMessage() {
        EvaluatedNotebook notebook = evaluated("", "# total", "$1", "1 + $2");
        assertError(ExpressionError.UNKNOWN_NAME, 0, 2, "La ligne 1 est vide : elle n'a pas de valeur.", notebook.result(3));
        assertError(ExpressionError.UNKNOWN_NAME, 4, 6, "La ligne 2 est un commentaire : elle n'a pas de valeur.",
                notebook.result(4));
    }

    @Test
    void aReferenceToALineInErrorIsInErrorItself() {
        EvaluatedNotebook notebook = evaluated("1 / 0", "$1 + 1", "2 * $2", "$1 +");
        assertError(ExpressionError.NUMERIC, 4, 5, "Division par zéro : le diviseur vaut 0.", notebook.result(1));
        assertError(ExpressionError.REFERENCE_IN_ERROR, 0, 2, "Dépend de la ligne 1, qui est en erreur.", notebook.result(2));
        assertError(ExpressionError.REFERENCE_IN_ERROR, 4, 6, "Dépend de la ligne 2, qui est en erreur.", notebook.result(3));
        assertError(ExpressionError.MISSING_OPERAND, 3, 4, "Opérande manquant après « + ».", notebook.result(4));
    }

    @Test
    void aVariableIsVisibleBelowItsDefinition() {
        EvaluatedNotebook notebook = evaluated("prix * 2", "prix = 12,5", "taux = 0,2", "prix * (1 + taux)  # TTC");
        assertError(ExpressionError.UNKNOWN_NAME, 0, 4, "Variable inconnue « prix ».", notebook.result(1));
        assertValue("12,5", notebook.result(2));
        assertEquals(ParsedLine.Kind.ASSIGNMENT, notebook.result(2).kind());
        assertEquals("prix", notebook.result(2).definedName());
        assertValue("15", notebook.result(4));
        assertEquals(ParsedLine.Kind.EXPRESSION, notebook.result(4).kind());
        assertTrue(notebook.result(4).assignedName().isEmpty());
    }

    @Test
    void theClosestDefinitionAboveMasksTheOlderOnes() {
        EvaluatedNotebook notebook = evaluated("x = 1", "x * 10", "x = x + 1", "x * 10", "X = 5", "x");
        assertValue("10", notebook.result(2));
        assertValue("2", notebook.result(3));
        assertValue("20", notebook.result(4));
        assertValue("2", notebook.result(6));
    }

    @Test
    void anUnknownNameIsAnError() {
        assertError(ExpressionError.UNKNOWN_NAME, 4, 8, "Variable inconnue « taux ».", evaluated("1 + taux").result(1));
    }

    @Test
    void aReservedWordCannotBeAssigned() {
        EvaluatedNotebook notebook = evaluated("ans = 3", "Sqrt = 2", "ans");
        assertError(ExpressionError.RESERVED_NAME, 0, 3,
                "« ans » est un mot réservé : choisissez un autre nom de variable.", notebook.result(1));
        assertTrue(notebook.result(1).assignedName().isEmpty());
        assertEquals(ParsedLine.Kind.EXPRESSION, notebook.result(1).kind());
        assertError(ExpressionError.RESERVED_NAME, 0, 4,
                "« Sqrt » est un mot réservé : choisissez un autre nom de variable.", notebook.result(2));
        assertTrue(notebook.result(3).isError());
    }

    @Test
    void anAssignmentInErrorStillDefinesItsNameSoNothingFallsBackSilently() {
        EvaluatedNotebook evaluation = evaluated("x = 1", "x = 1 / 0", "x + 1");
        assertError(ExpressionError.REFERENCE_IN_ERROR, 0, 1, "Dépend de la ligne 2, qui est en erreur.",
                evaluation.result(3));

        EvaluatedNotebook syntax = evaluated("x = 1", "x = 2 +", "x * 3", "y = 1,5,3", "y");
        assertEquals(ParsedLine.Kind.ASSIGNMENT, syntax.result(2).kind());
        assertEquals("x", syntax.result(2).definedName());
        assertError(ExpressionError.REFERENCE_IN_ERROR, 0, 1, "Dépend de la ligne 2, qui est en erreur.", syntax.result(3));
        assertEquals("y", syntax.result(4).definedName());
        assertError(ExpressionError.REFERENCE_IN_ERROR, 0, 1, "Dépend de la ligne 4, qui est en erreur.", syntax.result(5));
    }

    @Test
    void onlyANameBeforeTheEqualsSignMakesALineInErrorAnAssignment() {
        assertEquals(List.of(), names("2 = 3", "ans = 1 +", "$1 = 2", "x y = 2", "# x = 2", "= 2", "x + 1 = 2"));
        assertEquals(List.of("taux_TVA", "année2", "_x"), names(" taux_TVA = 1 +", "année2=", "_x " + (char) 0x00A0 + "= $?"));
    }

    @Test
    void errorsPropagateDownAChain() {
        EvaluatedNotebook notebook = evaluated("a = √(-1)", "b = a * 2", "$2 + 1", "c = 3", "c + $4");
        assertTrue(notebook.result(1).isError());
        assertError(ExpressionError.REFERENCE_IN_ERROR, 4, 5, "Dépend de la ligne 1, qui est en erreur.", notebook.result(2));
        assertError(ExpressionError.REFERENCE_IN_ERROR, 0, 2, "Dépend de la ligne 2, qui est en erreur.", notebook.result(3));
        assertValue("6", notebook.result(5));
    }

    @Test
    void aDeletedReferenceIsAnExplicitError() {
        assertError(ExpressionError.DELETED_REFERENCE, 4, 6, "Référence vers une ligne supprimée.",
                evaluated("1", "2 + $?").result(2));
    }

    @Test
    void aLineLongerThanTheLanguageLimitIsKeptInError() {
        String text = "1" + "+1".repeat(500);
        LineResult result = evaluated(text).result(1);
        assertEquals(text, result.text());
        assertError(ExpressionError.LINE_TOO_LONG, 1000, 1001, "La ligne est trop longue (1000 caractères maximum).", result);
    }

    @Test
    void approximateValuesFlowThroughReferences() {
        EvaluatedNotebook notebook = evaluated("r = √2", "r * r", "$1 * $1");
        assertFalse(notebook.result(1).value().isExact());
        assertEquals(notebook.result(2).value(), notebook.result(3).value());
        assertTrue(notebook.result(2).value().contains(NotebookTestSupport.exact("2").lower()));
    }

    private static List<String> names(String... texts) {
        return java.util.Arrays.stream(texts)
                .map(NotebookEvaluator::attemptedAssignment)
                .flatMap(java.util.Optional::stream)
                .toList();
    }
}
