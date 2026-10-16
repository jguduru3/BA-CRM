package com.betteranalytics.crm.workspace;

import com.betteranalytics.crm.service.formula.Formula;
import com.betteranalytics.crm.service.formula.FormulaSyntaxException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests — no Spring context. */
class FormulaTest {

    private static Object eval(String expr) {
        return eval(expr, Map.of());
    }

    private static Object eval(String expr, Map<String, Object> fields) {
        Map<String, Object> lower = new HashMap<>();
        fields.forEach((k, v) -> lower.put(k.toLowerCase(), v));
        Formula.Result r = Formula.parse(expr).evaluate(name -> {
            if (!lower.containsKey(name.toLowerCase())) throw new Formula.EvalError(Formula.REF);
            return lower.get(name.toLowerCase());
        });
        return r.isError() ? r.error() : r.value();
    }

    private static BigDecimal n(String s) {
        return new BigDecimal(s);
    }

    @Test
    void arithmeticFollowsPrecedenceAndParentheses() {
        assertThat(eval("1 + 2 * 3")).isEqualTo(n("7"));
        assertThat(eval("(1 + 2) * 3")).isEqualTo(n("9"));
        assertThat(eval("10 - 4 - 3")).isEqualTo(n("3"));
        assertThat(eval("100 / 10 / 5")).isEqualTo(n("2"));
        assertThat(eval("-2 * -3 + +1")).isEqualTo(n("7"));
        assertThat(eval("0.1 + 0.2")).isEqualTo(n("0.3"));
        assertThat(eval("7 / 2")).isEqualTo(n("3.5"));
        assertThat(eval("2 * (3 + (4 - 1)) / 3")).isEqualTo(n("4"));
    }

    @Test
    void fieldReferencesWorkWithBracesAndProp() {
        Map<String, Object> f = Map.of("Price", n("12.5"), "Qty", n("4"), "Name", "Widget");
        assertThat(eval("{Price} * {Qty}", f)).isEqualTo(n("50"));
        assertThat(eval("prop(\"Price\") * prop('Qty')", f)).isEqualTo(n("50"));
        assertThat(eval("{ price } + 0.5", f)).isEqualTo(n("13"));
        assertThat(eval("{Missing} + 1", f)).isEqualTo(Formula.REF);
        assertThat(Formula.parse("{A} + prop(\"B\")").references()).containsExactly("A", "B");
    }

    @Test
    void emptyFieldsActAsZeroOrEmptyText() {
        Map<String, Object> f = new HashMap<>();
        f.put("Empty", null);
        assertThat(eval("{Empty} + 5", f)).isEqualTo(n("5"));
        assertThat(eval("{Empty} & \"x\"", f)).isEqualTo("x");
    }

    @Test
    void stringConcatenationWithAmpersandOrPlus() {
        Map<String, Object> f = Map.of("First", "Ada", "Last", "Lovelace", "Age", n("36"));
        assertThat(eval("{First} & \" \" & {Last}", f)).isEqualTo("Ada Lovelace");
        assertThat(eval("{First} + \" (\" + {Age} + \")\"", f)).isEqualTo("Ada (36)");
        assertThat(eval("\"Total: \" & 2 * 3")).isEqualTo("Total: 6");
        assertThat(eval("CONCAT(\"a\", 1, TRUE)")).isEqualTo("a1true");
        assertThat(eval("LEN(\"hello\")")).isEqualTo(n("5"));
        assertThat(eval("'it\\'s'")).isEqualTo("it's");
    }

    @Test
    void comparisonsAndIf() {
        Map<String, Object> f = Map.of("Score", n("72"), "Done", true, "Stage", "Won");
        assertThat(eval("IF({Score} >= 70, \"pass\", \"fail\")", f)).isEqualTo("pass");
        assertThat(eval("IF({Score} < 70, \"pass\", \"fail\")", f)).isEqualTo("fail");
        assertThat(eval("IF({Done}, 1, 0)", f)).isEqualTo(n("1"));
        assertThat(eval("{Stage} = \"Won\"", f)).isEqualTo(true);
        assertThat(eval("{Stage} != \"Won\"", f)).isEqualTo(false);
        assertThat(eval("{Stage} <> \"Lost\"", f)).isEqualTo(true);
        assertThat(eval("2 == 2.0")).isEqualTo(true);
        assertThat(eval("1 + 1 > 1 * 1")).isEqualTo(true);
        assertThat(eval("IF(AND({Score} > 50, NOT(FALSE)), ROUND({Score} / 7, 2), 0)", f)).isEqualTo(n("10.29"));
        assertThat(eval("IF(OR(FALSE, 0, \"\"), 1, 2)")).isEqualTo(n("2"));
        assertThat(eval("ABS(-3)")).isEqualTo(n("3"));
        // IF only evaluates the chosen branch
        assertThat(eval("IF(TRUE, 1, 1 / 0)")).isEqualTo(n("1"));
    }

    @Test
    void runtimeErrorsBecomeErrorValues() {
        assertThat(eval("1 / 0")).isEqualTo(Formula.DIV_ZERO);
        assertThat(eval("5 / (2 - 2)")).isEqualTo(Formula.DIV_ZERO);
        assertThat(eval("\"abc\" * 2")).isEqualTo(Formula.VALUE);
        assertThat(eval("ROUND(1, 500)")).isEqualTo(Formula.NUM);
        Map<String, Object> f = Map.of("X", Formula.parse("1/0").evaluate(n -> null));
        assertThat(eval("{X} + 1", f)).isEqualTo(Formula.DIV_ZERO);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "1 +", "(1 + 2", "1 + 2)", "IF(1, 2)", "foo(1)", "{unclosed", "{}", "\"open",
            "1 $ 2", "java.lang.Runtime.getRuntime().exec(\"calc\")", "T(java.lang.System).exit(0)",
            "#{1+1}", "${7*7}", "prop(Price)", "1 2", "System.exit(0)", "eval(\"1\")", "a = b"
    })
    void maliciousOrMalformedInputIsRejected(String expr) {
        assertThatThrownBy(() -> Formula.parse(expr)).isInstanceOf(FormulaSyntaxException.class);
    }

    @Test
    void inputSizeAndNestingAreBounded() {
        assertThatThrownBy(() -> Formula.parse("1+".repeat(600) + "1")).isInstanceOf(FormulaSyntaxException.class);
        assertThatThrownBy(() -> Formula.parse("(".repeat(100) + "1" + ")".repeat(100))).isInstanceOf(FormulaSyntaxException.class);
        assertThatThrownBy(() -> Formula.parse("-".repeat(200) + "1")).isInstanceOf(FormulaSyntaxException.class);
        assertThatThrownBy(() -> Formula.parse("1+".repeat(300) + "1")).isInstanceOf(FormulaSyntaxException.class);
        // huge string growth is cut off at evaluation time
        String big = "\"" + "x".repeat(900) + "\"";
        String expr = "CONCAT(" + String.join(",", java.util.Collections.nCopies(12, big)) + ")";
        assertThatThrownBy(() -> Formula.parse(expr)).isInstanceOf(FormulaSyntaxException.class); // too long to parse
        Map<String, Object> f = Map.of("T", "y".repeat(6000));
        assertThat(eval("{T} & {T}", f)).isEqualTo(Formula.VALUE);
    }
}
