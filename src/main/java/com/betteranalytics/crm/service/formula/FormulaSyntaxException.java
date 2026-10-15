package com.betteranalytics.crm.service.formula;

/**
 * A formula that cannot be parsed (or is too large/deep). Extends
 * IllegalArgumentException so an invalid formula sent to the API maps to 400.
 */
public class FormulaSyntaxException extends IllegalArgumentException {
    public FormulaSyntaxException(String message) {
        super("invalid formula: " + message);
    }
}
