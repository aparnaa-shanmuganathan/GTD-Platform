package com.gtd.platform.service.geo;

import com.gtd.platform.service.agg.RankingAndBreakdownService;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.MapResponse;
import com.gtd.platform.shared.api.MapResponse.ChoroplethEntry;
import com.gtd.platform.shared.api.MapResponse.IncidentPoint;
import com.gtd.platform.shared.api.RankingResponse;
import com.gtd.platform.shared.db.FactIncidentFilterSqlBuilder;
import com.gtd.platform.shared.db.FactIncidentFilterSqlBuilder.BuiltFilter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
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

    /**
     * Raw bytes of the configured boundary GeoJSON, for {@code GET /api/geospatial/boundaries}
     * to stream back to the client. One code path regardless of whether {@code
     * boundaryGeojsonPath} resolves to a vendored classpath resource (the default, a leading
     * {@code "/"} under {@code static/}) or an admin-supplied absolute filesystem path (a
     * richer file dropped in at deployment time, per this class's Javadoc) - the client never
     * needs to know which.
     */
    public Optional<byte[]> readBoundaryGeojson() {
        return resolveBoundaryResource().filter(Resource::isReadable).map(resource -> {
            try {
                return resource.getContentAsByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read configured boundary GeoJSON: " + boundaryGeojsonPath, e);
            }
        });
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
        return resolveBoundaryResource().filter(Resource::isReadable).isPresent();
    }

    /**
     * {@code boundaryGeojsonPath} starting with {@code "/"} is treated as a classpath resource
     * under {@code static/} (the vendored default, {@code /geo/world-countries.geojson}, served
     * automatically as a static asset too - this method just needs to confirm it's actually
     * there). Anything else is treated as an absolute filesystem path, unchanged from this
     * class's original design, so an admin can still swap in a different/richer boundary file
     * at deployment time without a rebuild.
     */
    private Optional<Resource> resolveBoundaryResource() {
        if (boundaryGeojsonPath == null || boundaryGeojsonPath.isBlank()) {
            return Optional.empty();
        }
        if (boundaryGeojsonPath.startsWith("/")) {
            return Optional.of(new ClassPathResource("static" + boundaryGeojsonPath));
        }
        Path path = Path.of(boundaryGeojsonPath);
        if (!Files.isReadable(path) || Files.isDirectory(path)) {
            return Optional.empty();
        }
        return Optional.of(new FileSystemResource(path));
    }
}
