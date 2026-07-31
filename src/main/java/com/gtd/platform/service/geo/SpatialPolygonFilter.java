package com.gtd.platform.service.geo;

import java.util.List;

/**
 * Standard ray-casting point-in-polygon test, applied in plain Java (not
 * PostGIS - out of the frozen stack, CLAUDE.md section 2) over the drawn
 * region a Geospatial Workspace user draws on the map ({@link
 * GeospatialPointsRequest#getPolygon()}).
 *
 * <p>Deliberately applied only in-memory, over the already
 * date/region/attack-type/etc.-filtered candidate points {@link
 * GeospatialQueryService} fetches from the database (never the whole {@code
 * fact_incident} table) - feasible at this platform's target scale (up to
 * ~250k rows, CLAUDE.md section 10) since the candidate set is always a
 * fraction of that, the same "direct fact_incident query, bounded by the
 * canonical filter" pattern SVC-AGG/SVC-TREND already established.
 *
 * <p>No DB/Spring dependency, mirroring {@code GrowthRateCalculator}'s pure-
 * function style, so its own unit test can assert against hand-computed
 * polygons independent of how a caller sources its point/polygon data.
 */
public final class SpatialPolygonFilter {

    private SpatialPolygonFilter() {
    }

    /**
     * @param lat      the point's latitude
     * @param lng      the point's longitude
     * @param polygon  vertices as {@code [lat, lng]} pairs, in order; need not be explicitly closed
     *                 (the last vertex is implicitly connected back to the first)
     * @return {@code true} if the point lies inside the polygon; a point exactly on an edge or
     *         vertex may resolve either way, a well-known characteristic of the ray-casting
     *         algorithm itself, not a bug in this implementation
     */
    public static boolean contains(double lat, double lng, List<double[]> polygon) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double latI = polygon.get(i)[0];
            double lngI = polygon.get(i)[1];
            double latJ = polygon.get(j)[0];
            double lngJ = polygon.get(j)[1];

            boolean edgeCrossesRay = ((latI > lat) != (latJ > lat))
                    && (lng < (lngJ - lngI) * (lat - latI) / (latJ - latI) + lngI);
            if (edgeCrossesRay) {
                inside = !inside;
            }
        }
        return inside;
    }
}
