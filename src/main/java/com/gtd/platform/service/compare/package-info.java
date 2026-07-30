/**
 * SVC-COMPARE — side-by-side comparison of up to 4 countries at once, and
 * (as a degenerate 1-country case of the same computation) a single
 * country's time-trend drill-down.
 *
 * <p>Named explicitly as its own analytical service in CLAUDE.md section 4
 * (not folded into SVC-AGG, per the user's explicit instruction for Phase 5)
 * because it composes SVC-AGG's KPIs and SVC-TREND's trend series
 * per-country rather than computing anything new itself - see
 * {@link com.gtd.platform.service.compare.CompareService}'s Javadoc.
 */
package com.gtd.platform.service.compare;
