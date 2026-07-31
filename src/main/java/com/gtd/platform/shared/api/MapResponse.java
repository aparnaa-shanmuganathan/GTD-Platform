package com.gtd.platform.shared.api;

import java.math.BigDecimal;
import java.util.List;

/**
 * CAC-AN-004 (CLAUDE.md section 9): the shared map component's response
 * shape - deferred since Phase 2 to whichever phase first needed it
 * (Phase 9's Geospatial Workspace, SVC-GEO).
 *
 * <p>{@code points} and {@code choropleth} are always populated from real
 * data regardless of {@code boundariesAvailable} - the choropleth's
 * per-country counts come from the same full-canonical-filter ranking query
 * every other workspace already uses; only the client's ability to *draw*
 * those counts as colored country polygons depends on a boundary GeoJSON
 * file being configured and readable. See CLAUDE.md's "Phase 9 decisions,
 * Part B" for the full rationale.
 *
 * @param id                 a stable identifier for the map widget
 * @param points             incident points with real coordinates, for marker/cluster/heat/timeline layers
 * @param totalIncidentCount incidents matching the filter, regardless of whether they have coordinates
 * @param geocodedCount      of those, how many have non-null latitude/longitude (i.e. {@code points.size()})
 * @param choropleth         per-country incident counts, always real and always populated
 * @param boundariesAvailable whether a boundary GeoJSON is configured and readable, so the client can render the choropleth as colored polygons
 */
public record MapResponse(
        String id,
        List<IncidentPoint> points,
        long totalIncidentCount,
        long geocodedCount,
        List<ChoroplethEntry> choropleth,
        boolean boundariesAvailable) {

    public record IncidentPoint(double lat, double lng, int year, Integer nkill, Integer nwound) {
    }

    public record ChoroplethEntry(String label, Long entityId, BigDecimal value) {
    }
}
