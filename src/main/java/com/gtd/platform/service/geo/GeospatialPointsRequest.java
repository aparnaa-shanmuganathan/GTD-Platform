package com.gtd.platform.service.geo;

import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import java.util.List;

/**
 * Request body for {@code POST /api/geospatial/points}: the canonical filter
 * (CAC-UI-003) plus an optional drawn-region polygon. The polygon is
 * Geospatial-specific (a list of {@code [lat, lng]} vertex pairs, closed or
 * open), so it is not added to the shared {@link AnalyticalFilterRequest}
 * itself - the same reasoning Phase 5's {@code CompareService} already
 * established for not overloading that shared DTO with a workspace-specific
 * field. {@code polygon} is {@code null}/empty for "no spatial filter."
 */
public class GeospatialPointsRequest {

    private AnalyticalFilterRequest filter = new AnalyticalFilterRequest();
    private List<double[]> polygon;

    public AnalyticalFilterRequest getFilter() {
        return filter;
    }

    public void setFilter(AnalyticalFilterRequest filter) {
        this.filter = filter != null ? filter : new AnalyticalFilterRequest();
    }

    public List<double[]> getPolygon() {
        return polygon;
    }

    public void setPolygon(List<double[]> polygon) {
        this.polygon = polygon;
    }
}
