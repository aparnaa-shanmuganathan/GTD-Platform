package com.gtd.platform.service.geo;

import com.gtd.platform.service.agg.RankingAndBreakdownService;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.MapResponse;
import com.gtd.platform.shared.api.MapResponse.ChoroplethEntry;
import com.gtd.platform.shared.api.MapResponse.IncidentPoint;
import com.gtd.platform.shared.api.RankingResponse;
import com.gtd.platform.shared.db.FactIncidentFilterSqlBuilder;
import com.gtd.platform.shared.db.FactIncidentFilterSqlBuilder.BuiltFilter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * SVC-GEO (CLAUDE.md section 12, Phase 9 Part B): the Geospatial Workspace's
 * point/choropleth data source. Queries {@code fact_incident} directly via
 * {@link FactIncidentFilterSqlBuilder}, the same full-canonical-filter
 * approach every other direct-query service (SVC-AGG, SVC-TREND, {@link
 * RankingAndBreakdownService}) already uses, plus an added {@code latitude
 * IS NOT NULL AND longitude IS NOT NULL} clause - GTD data frequently lacks
 * geocoding, and excluded points are real, filtered-out data, not a bug
 * (surfaced to the client as {@code geocodedCount} vs {@code
 * totalIncidentCount}).
 *
 * <p>An optional drawn-region polygon ({@link GeospatialPointsRequest#getPolygon()})
 * is applied in Java via {@link SpatialPolygonFilter}, over the already
 * date/region/etc.-filtered and already-geocoded candidate rows fetched from
 * the database - never over the whole table.
 *
 * <p>The choropleth's per-country counts reuse {@link
 * RankingAndBreakdownService#topCountriesByIncidentCount(AnalyticalFilterRequest, int)}
 * directly (already exists, already full-filter-correct) rather than a
 * second, parallel counting query.
 */
@Service
public class GeospatialQueryService {

    /** Effectively "every country" - the choropleth needs the complete distribution, not a compact top-N cutoff. */
    private static final int ALL_COUNTRIES_LIMIT = 1000;

    private final JdbcTemplate jdbcTemplate;
    private final RankingAndBreakdownService rankingAndBreakdownService;
    private final String boundaryGeojsonPath;

    public GeospatialQueryService(JdbcTemplate jdbcTemplate, RankingAndBreakdownService rankingAndBreakdownService,
            @Value("${gtd.geospatial.boundary-geojson-path:}") String boundaryGeojsonPath) {
        this.jdbcTemplate = jdbcTemplate;
        this.rankingAndBreakdownService = rankingAndBreakdownService;
        this.boundaryGeojsonPath = boundaryGeojsonPath;
    }

    public MapResponse queryPoints(GeospatialPointsRequest request) {
        AnalyticalFilterRequest filter = request.getFilter();
        long totalIncidentCount = countMatching(filter);
        List<IncidentPoint> geocodedPoints = queryGeocodedPoints(filter);
        long geocodedCount = geocodedPoints.size();

        List<double[]> polygon = request.getPolygon();
        List<IncidentPoint> points = (polygon == null || polygon.size() < 3)
                ? geocodedPoints
                : geocodedPoints.stream()
                        .filter(p -> SpatialPolygonFilter.contains(p.lat(), p.lng(), polygon))
                        .toList();

        List<ChoroplethEntry> choropleth = queryChoropleth(filter);
        return new MapResponse("geoMap", points, totalIncidentCount, geocodedCount, choropleth, boundariesAvailable());
    }

    private long countMatching(AnalyticalFilterRequest filter) {
        BuiltFilter built = FactIncidentFilterSqlBuilder.build(filter);
        String sql = "SELECT COUNT(*) FROM fact_incident WHERE " + built.whereClauseSql();
        Long count = jdbcTemplate.queryForObject(sql, Long.class, built.params().toArray());
        return count != null ? count : 0L;
    }

    private List<IncidentPoint> queryGeocodedPoints(AnalyticalFilterRequest filter) {
        BuiltFilter built = FactIncidentFilterSqlBuilder.build(filter);
        String sql = "SELECT latitude, longitude, event_year, nkill, nwound FROM fact_incident WHERE "
                + built.whereClauseSql() + " AND latitude IS NOT NULL AND longitude IS NOT NULL";
        return jdbcTemplate.query(sql, built.params().toArray(), (rs, rowNum) -> {
            int nkill = rs.getInt("nkill");
            Integer nkillValue = rs.wasNull() ? null : nkill;
            int nwound = rs.getInt("nwound");
            Integer nwoundValue = rs.wasNull() ? null : nwound;
            return new IncidentPoint(rs.getDouble("latitude"), rs.getDouble("longitude"),
                    rs.getInt("event_year"), nkillValue, nwoundValue);
        });
    }

    private List<ChoroplethEntry> queryChoropleth(AnalyticalFilterRequest filter) {
        RankingResponse ranking = rankingAndBreakdownService.topCountriesByIncidentCount(filter, ALL_COUNTRIES_LIMIT);
        return ranking.entries().stream()
                .map(entry -> new ChoroplethEntry(entry.label(), entry.entityId(), entry.value()))
                .toList();
    }

    private boolean boundariesAvailable() {
        if (boundaryGeojsonPath == null || boundaryGeojsonPath.isBlank()) {
            return false;
        }
        return Files.isReadable(Path.of(boundaryGeojsonPath)) && !Files.isDirectory(Path.of(boundaryGeojsonPath));
    }
}
