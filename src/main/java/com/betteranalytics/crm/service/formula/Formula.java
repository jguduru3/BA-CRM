package com.betteranalytics.crm.service.formula;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Function;

/**
 * A small, sandboxed formula language for custom-database "formula" properties.
 *
 * It is a hand-written tokenizer + recursive-descent parser that builds an AST,
 * and a tree-walking evaluator over it — there is no script engine, no
 * reflection, no method lookup by name and no access to anything but the
 * record's own field values handed in by the caller. Inputs are bounded
 * (expression length, token count, nesting depth, string size), so hostile
 * input can at worst produce a parse error or an error value.
 *
 * Grammar (lowest to highest precedence):
 * <pre>
 *   expr       := comparison
 *   comparison := concat ( ( "=" | "==" | "!=" | "<>" | "<" | "<=" | ">" | ">=" ) concat )*
 *   concat     := additive ( "&" additive )*
 *   additive   := term ( ( "+" | "-" ) term )*          — "+" concatenates when either side is text
 *   term       := unary ( ( "*" | "/" ) unary )*
 *   unary      := ( "-" | "+" ) unary | primary
 *   primary    := NUMBER | STRING | TRUE | FALSE | "{" field name "}"
 *               | IDENT "(" [ expr ( "," expr )* ] ")" | "(" expr ")"
 * </pre>
 * Functions: IF(cond, a, b), AND(a, b, ...), OR(a, b, ...), NOT(a), ROUND(n[, digits]),
 * ABS(n), LEN(text), CONCAT(a, b, ...), and prop("Field name") as an alternative
 * to {Field name}.
 *
 * Values are BigDecimal, String, Boolean or null (an empty field). Runtime
 * problems never throw out of {@link #evaluate}: they produce a
 * {@link Result} carrying an error code such as {@code #DIV/0!}.
 */
public final class Formula {

    public static final int MAX_LENGTH = 1000;
    public static final int MAX_TOKENS = 400;
    public static final int MAX_DEPTH = 32;
    public static final int MAX_STRING = 10_000;
    private static final MathContext MC = MathContext.DECIMAL64;

    public static final String DIV_ZERO = "#DIV/0!";
    public static final String VALUE = "#VALUE!";
    public static final String REF = "#REF!";
    public static final String NUM = "#NUM!";
    public static final String CYCLE = "#CYCLE!";

    private final Node root;
    private final Set<String> references;

    private Formula(Node root, Set<String> references) {
        this.root = root;
        this.references = references;
    }

    /** Field names (case-insensitive, as written) the formula reads. */
    public Set<String> references() {
        return references;
    }

    /**
     * Parses an expression.
     * @throws FormulaSyntaxException for malformed or oversized input
     */
    public static Formula parse(String expression) {
        if (expression == null || expression.isBlank()) throw new FormulaSyntaxException("formula is empty");
        if (expression.length() > MAX_LENGTH) throw new FormulaSyntaxException("formula is longer than " + MAX_LENGTH + " characters");
        List<Token> tokens = new Tokenizer(expression).tokenize();
        Parser p = new Parser(tokens);
        Node root = p.parseExpr(0);
        p.expect(TokenType.EOF, "unexpected " + p.peek().text);
        return new Formula(root, Collections.unmodifiableSet(p.references));
    }

    /** Evaluates with a field resolver (field name → value). Never throws. */
    public Result evaluate(Function<String, Object> fields) {
        try {
            return Result.ok(normalize(root.eval(new Ctx(fields))));
        } catch (EvalError e) {
            return Result.error(e.code);
        } catch (ArithmeticException e) {
            return Result.error(NUM);
        } catch (StackOverflowError e) {
            return Result.error(NUM);
        }
    }

    private static Object normalize(Object v) {
        if (v instanceof BigDecimal d) {
            BigDecimal s = d.stripTrailingZeros();
            return s.scale() < 0 ? s.setScale(0, RoundingMode.UNNECESSARY) : s;
        }
        return v;
    }

    // ── Result ────────────────────────────────────────────────────────────────

    /** Either a value (BigDecimal / String / Boolean / null) or an error code. */
    public record Result(Object value, String error) {
        static Result ok(Object v) { return new Result(v, null); }
        static Result error(String code) { return new Result(null, code); }
        public boolean isError() { return error != null; }
    }

    /** Raised (and caught by evaluate) for runtime errors. */
    public static final class EvalError extends RuntimeException {
        final String code;
        public EvalError(String code) {
            super(code, null, false, false);
            this.code = code;
        }
    }

    // ── Tokenizer ─────────────────────────────────────────────────────────────

    private enum TokenType { NUMBER, STRING, IDENT, FIELD, LPAREN, RPAREN, COMMA, OP, EOF }

    private record Token(TokenType type, String text, int pos) {}

    private static final class Tokenizer {
        private final String s;
        private int i = 0;
        private final List<Token> out = new ArrayList<>();

        Tokenizer(String s) { this.s = s; }

        List<Token> tokenize() {
            while (true) {
                while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
                if (i >= s.length()) break;
                if (out.size() >= MAX_TOKENS) throw new FormulaSyntaxException("formula is too complex");
                char c = s.charAt(i);
                int start = i;
                if (Character.isDigit(c) || (c == '.' && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) {
                    while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                    if (i < s.length() && s.charAt(i) == '.') {
                        i++;
                        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
                    }
                    out.add(new Token(TokenType.NUMBER, s.substring(start, i), start));
                } else if (c == '"' || c == '\'') {
                    out.add(new Token(TokenType.STRING, readString(c), start));
                } else if (c == '{') {
                    int end = s.indexOf('}', i + 1);
                    if (end < 0) throw new FormulaSyntaxException("unclosed { at position " + start);
                    String name = s.substring(i + 1, end).trim();
                    if (name.isEmpty()) throw new FormulaSyntaxException("empty field reference at position " + start);
                    i = end + 1;
                    out.add(new Token(TokenType.FIELD, name, start));
                } else if (Character.isLetter(c) || c == '_') {
                    while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) i++;
                    out.add(new Token(TokenType.IDENT, s.substring(start, i), start));
                } else if (c == '(') { i++; out.add(new Token(TokenType.LPAREN, "(", start)); }
                else if (c == ')') { i++; out.add(new Token(TokenType.RPAREN, ")", start)); }
                else if (c == ',') { i++; out.add(new Token(TokenType.COMMA, ",", start)); }
                else {
                    String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                    if (two.equals("==") || two.equals("!=") || two.equals("<>") || two.equals("<=") || two.equals(">=")) {
                        i += 2;
                        out.add(new Token(TokenType.OP, two, start));
                    } else if ("+-*/&=<>".indexOf(c) >= 0) {
                        i++;
                        out.add(new Token(TokenType.OP, String.valueOf(c), start));
                    } else {
                        throw new FormulaSyntaxException("unexpected character '" + c + "' at position " + start);
                    }
                }
            }
            out.add(new Token(TokenType.EOF, "end of formula", s.length()));
            return out;
        }

        private String readString(char quote) {
            int start = i;
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == quote) return sb.toString();
                if (c == '\\' && i < s.length()) {
                    char n = s.charAt(i++);
                    sb.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
                } else {
                    sb.append(c);
                }
            }
            throw new FormulaSyntaxException("unclosed string at position " + start);
        }
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    private static final Set<String> FUNCTIONS = Set.of("IF", "AND", "OR", "NOT", "ROUND", "ABS", "LEN", "CONCAT", "PROP");

    private static final class Parser {
        private final List<Token> tokens;
        private int pos = 0;
        final Set<String> references = new LinkedHashSet<>();

        Parser(List<Token> tokens) { this.tokens = tokens; }

        Token peek() { return tokens.get(pos); }
        Token next() { return tokens.get(pos++); }

        boolean isOp(String... ops) {
            Token t = peek();
            if (t.type != TokenType.OP) return false;
            for (String o : ops) if (o.equals(t.text)) return true;
            return false;
        }

        Token expect(TokenType type, String message) {
            if (peek().type != type) throw new FormulaSyntaxException(message);
            return next();
        }

        void guard(int depth) {
            if (depth > MAX_DEPTH) throw new FormulaSyntaxException("formula is nested too deeply");
        }

        Node parseExpr(int depth) {
            guard(depth);
            Node left = parseConcat(depth + 1);
            while (isOp("=", "==", "!=", "<>", "<", "<=", ">", ">=")) {
                String op = next().text;
                Node right = parseConcat(depth + 1);
                Node l = left;
                left = ctx -> compare(op, l.eval(ctx), right.eval(ctx));
            }
            return left;
        }

        Node parseConcat(int depth) {
            guard(depth);
            Node left = parseAdditive(depth + 1);
            while (isOp("&")) {
                next();
                Node right = parseAdditive(depth + 1);
                Node l = left;
                left = ctx -> concat(l.eval(ctx), right.eval(ctx));
            }
            return left;
        }

        Node parseAdditive(int depth) {
            guard(depth);
            Node left = parseTerm(depth + 1);
            while (isOp("+", "-")) {
                String op = next().text;
                Node right = parseTerm(depth + 1);
                Node l = left;
                left = op.equals("+")
                        ? ctx -> plus(l.eval(ctx), right.eval(ctx))
                        : ctx -> num(l.eval(ctx)).subtract(num(right.eval(ctx)), MC);
            }
            return left;
        }

        Node parseTerm(int depth) {
            guard(depth);
            Node left = parseUnary(depth + 1);
            while (isOp("*", "/")) {
                String op = next().text;
                Node right = parseUnary(depth + 1);
                Node l = left;
                left = op.equals("*")
                        ? ctx -> num(l.eval(ctx)).multiply(num(right.eval(ctx)), MC)
                        : ctx -> divide(num(l.eval(ctx)), num(right.eval(ctx)));
            }
            return left;
        }

        Node parseUnary(int depth) {
            guard(depth);
            if (isOp("-")) {
                next();
                Node inner = parseUnary(depth + 1);
                return ctx -> num(inner.eval(ctx)).negate();
            }
            if (isOp("+")) {
                next();
                Node inner = parseUnary(depth + 1);
                return ctx -> num(inner.eval(ctx));
            }
            return parsePrimary(depth + 1);
        }

        Node parsePrimary(int depth) {
            guard(depth);
            Token t = next();
            switch (t.type) {
                case NUMBER -> {
                    BigDecimal v;
                    try {
                        v = new BigDecimal(t.text, MC);
                    } catch (NumberFormatException e) {
                        throw new FormulaSyntaxException("invalid number " + t.text);
                    }
                    return ctx -> v;
                }
                case STRING -> {
                    String v = t.text;
                    if (v.length() > MAX_STRING) throw new FormulaSyntaxException("text literal is too long");
                    return ctx -> v;
                }
                case FIELD -> {
                    references.add(t.text);
                    String name = t.text;
                    return ctx -> ctx.field(name);
                }
                case LPAREN -> {
                    Node inner = parseExpr(depth + 1);
                    expect(TokenType.RPAREN, "missing ) for the ( at position " + t.pos);
                    return inner;
                }
                case IDENT -> {
                    String upper = t.text.toUpperCase(Locale.ROOT);
                    if (upper.equals("TRUE")) return ctx -> Boolean.TRUE;
                    if (upper.equals("FALSE")) return ctx -> Boolean.FALSE;
                    if (!FUNCTIONS.contains(upper)) throw new FormulaSyntaxException("unknown function " + t.text);
                    expect(TokenType.LPAREN, "expected ( after " + t.text);
                    List<Node> args = new ArrayList<>();
                    if (peek().type != TokenType.RPAREN) {
                        if (upper.equals("PROP")) {
                            Token name = expect(TokenType.STRING, "prop() takes a field name in quotes");
                            references.add(name.text);
                            String field = name.text;
                            args.add(ctx -> ctx.field(field));
                        } else {
                            args.add(parseExpr(depth + 1));
                            while (peek().type == TokenType.COMMA) {
                                next();
                                args.add(parseExpr(depth + 1));
                            }
                        }
                    }
                    expect(TokenType.RPAREN, "missing ) after the arguments of " + t.text);
                    return function(upper, args);
                }
                default -> throw new FormulaSyntaxException("unexpected " + t.text + " at position " + t.pos);
            }
        }

        private Node function(String name, List<Node> a) {
            switch (name) {
                case "PROP" -> {
                    arity(name, a, 1, 1);
                    return a.get(0);
                }
                case "IF" -> {
                    arity(name, a, 3, 3);
                    return ctx -> truthy(a.get(0).eval(ctx)) ? a.get(1).eval(ctx) : a.get(2).eval(ctx);
                }
                case "AND" -> {
                    arity(name, a, 1, 50);
                    return ctx -> {
                        for (Node n : a) if (!truthy(n.eval(ctx))) return Boolean.FALSE;
                        return Boolean.TRUE;
                    };
                }
                case "OR" -> {
                    arity(name, a, 1, 50);
                    return ctx -> {
                        for (Node n : a) if (truthy(n.eval(ctx))) return Boolean.TRUE;
                        return Boolean.FALSE;
                    };
                }
                case "NOT" -> {
                    arity(name, a, 1, 1);
                    return ctx -> !truthy(a.get(0).eval(ctx));
                }
                case "ABS" -> {
                    arity(name, a, 1, 1);
                    return ctx -> num(a.get(0).eval(ctx)).abs();
                }
                case "ROUND" -> {
                    arity(name, a, 1, 2);
                    return ctx -> {
                        BigDecimal v = num(a.get(0).eval(ctx));
                        int digits = a.size() > 1 ? num(a.get(1).eval(ctx)).intValue() : 0;
                        if (digits < -20 || digits > 20) throw new EvalError(NUM);
                        return v.setScale(digits, RoundingMode.HALF_UP);
                    };
                }
                case "LEN" -> {
                    arity(name, a, 1, 1);
                    return ctx -> BigDecimal.valueOf(text(a.get(0).eval(ctx)).length());
                }
                case "CONCAT" -> {
                    arity(name, a, 1, 50);
                    return ctx -> {
                        Object acc = "";
                        for (Node n : a) acc = concat(acc, n.eval(ctx));
                        return acc;
                    };
                }
                default -> throw new FormulaSyntaxException("unknown function " + name);
            }
        }

        private static void arity(String name, List<Node> args, int min, int max) {
            if (args.size() < min || args.size() > max) {
                throw new FormulaSyntaxException(name + "() takes " + (min == max ? min : min + " to " + max)
                        + " argument" + (max == 1 ? "" : "s"));
            }
        }
    }

    // ── Evaluation ────────────────────────────────────────────────────────────

    @FunctionalInterface
    private interface Node {
        Object eval(Ctx ctx);
    }

    private static final class Ctx {
        private final Function<String, Object> fields;
        Ctx(Function<String, Object> fields) { this.fields = fields; }

        Object field(String name) {
            Object v = fields.apply(name);
            if (v instanceof Result r) {
                if (r.isError()) throw new EvalError(r.error());
                return r.value();
            }
            return v;
        }
    }

    static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof BigDecimal d) return d.signum() != 0;
        if (v instanceof String s) return !s.isEmpty();
        return true;
    }

    static BigDecimal num(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal d) return d;
        if (v instanceof Boolean b) return b ? BigDecimal.ONE : BigDecimal.ZERO;
        if (v instanceof Number n) return new BigDecimal(n.toString(), MC);
        if (v instanceof String s) {
            String t = s.trim();
            if (t.isEmpty()) return BigDecimal.ZERO;
            if (t.length() > 100) throw new EvalError(VALUE);
            try {
                return new BigDecimal(t, MC);
            } catch (NumberFormatException e) {
                throw new EvalError(VALUE);
            }
        }
        throw new EvalError(VALUE);
    }

    static String text(Object v) {
        if (v == null) return "";
        if (v instanceof BigDecimal d) {
            BigDecimal s = d.stripTrailingZeros();
            if (Math.abs(s.scale()) > 1000) throw new EvalError(NUM);
            return s.toPlainString();
        }
        return String.valueOf(v);
    }

    static Object concat(Object a, Object b) {
        String s = text(a) + text(b);
        if (s.length() > MAX_STRING) throw new EvalError(VALUE);
        return s;
    }

    static Object plus(Object a, Object b) {
        if (a instanceof String || b instanceof String) return concat(a, b);
        return num(a).add(num(b), MC);
    }

    static BigDecimal divide(BigDecimal a, BigDecimal b) {
        if (b.signum() == 0) throw new EvalError(DIV_ZERO);
        return a.divide(b, MC);
    }

    static Boolean compare(String op, Object a, Object b) {
        int c;
        boolean numeric = (a == null || a instanceof BigDecimal || a instanceof Boolean)
                && (b == null || b instanceof BigDecimal || b instanceof Boolean)
                && !(a instanceof Boolean && b instanceof Boolean);
        if (a instanceof Boolean x && b instanceof Boolean y) {
            c = Boolean.compare(x, y);
        } else if (numeric) {
            c = num(a).compareTo(num(b));
        } else {
            c = text(a).compareTo(text(b));
        }
        return switch (op) {
            case "=", "==" -> c == 0;
            case "!=", "<>" -> c != 0;
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            default -> throw new EvalError(VALUE);
        };
    }
}
