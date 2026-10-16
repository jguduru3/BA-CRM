package com.betteranalytics.crm.entity;

import java.util.Locale;

/**
 * Ordered forecast categories (HubSpot model). A category's meaning comes from
 * its position, not its label: NOT_FORECASTED < PIPELINE < BEST_CASE < COMMIT < CLOSED_WON.
 * There is deliberately no "closed lost" category: lost deals are NOT_FORECASTED.
 */
public enum ForecastCategory {
    NOT_FORECASTED("Not forecasted"),
    PIPELINE("Pipeline"),
    BEST_CASE("Best case"),
    COMMIT("Commit"),
    CLOSED_WON("Closed won");

    private final String label;

    ForecastCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** Parses a category key (case-insensitive); IllegalArgumentException (400) for anything else. */
    public static ForecastCategory parse(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("forecastCategory is required");
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "forecastCategory must be one of NOT_FORECASTED, PIPELINE, BEST_CASE, COMMIT, CLOSED_WON");
        }
    }
}
