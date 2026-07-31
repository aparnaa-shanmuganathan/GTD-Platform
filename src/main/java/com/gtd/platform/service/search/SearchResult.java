package com.gtd.platform.service.search;

/**
 * One global search result (Phase 10). {@code url} is {@code null} for the one client-handled
 * quick action ("Toggle theme") - {@code search.js} special-cases {@code key == "__theme"}
 * rather than navigating.
 */
public record SearchResult(String key, String label, String group, String url) {
}
