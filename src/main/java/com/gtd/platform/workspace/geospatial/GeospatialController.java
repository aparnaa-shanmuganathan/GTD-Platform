package com.gtd.platform.workspace.geospatial;

import com.gtd.platform.service.geo.GeospatialPointsRequest;
import com.gtd.platform.service.geo.GeospatialQueryService;
import com.gtd.platform.shared.api.AnalyticalFilterRequest;
import com.gtd.platform.shared.api.ApiResponse;
import com.gtd.platform.shared.api.MapResponse;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
import com.gtd.platform.shared.db.DimRegionRepository;
import java.security.Principal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * ADC-MAP-001: the Geospatial Workspace (Phase 9, Part B) - marker
 * clustering, heatmap, choropleth, drawn-region spatial filtering, and
 * timeline animation, all backed by SVC-GEO ({@link GeospatialQueryService})
 * and vendored Leaflet (CLAUDE.md section 2, no CDN).
 *
 * <p>{@code GET /workspace/geospatial} server-renders the initial map
 * against the unfiltered dataset; the filter bar (and the draw-a-region
 * tool) re-query {@code POST /api/geospatial/points} client-side - the same
 * single-combined-endpoint shape every Phase 8/9 workspace controller
 * already established.
 *
 * <p>Publicly viewable per the access model amendment (CLAUDE.md section
 * 12) - {@code principal} is {@code null} for an anonymous visitor.
 */
@Controller
public class GeospatialController {

    private final GeospatialQueryService geospatialQueryService;
    private final JdbcTemplate jdbcTemplate;
    private final DimRegionRepository regionRepository;
    private final DimAttackTypeRepository attackTypeRepository;
    private final String tileUrlTemplate;
    private final List<String> boundaryNameProperties;

    public GeospatialController(GeospatialQueryService geospatialQueryService, JdbcTemplate jdbcTemplate,
            DimRegionRepository regionRepository, DimAttackTypeRepository attackTypeRepository,
            @Value("${gtd.geospatial.tile-url-template}") String tileUrlTemplate,
            @Value("${gtd.geospatial.boundary-name-property}") String boundaryNameProperty) {
        this.geospatialQueryService = geospatialQueryService;
        this.jdbcTemplate = jdbcTemplate;
        this.regionRepository = regionRepository;
        this.attackTypeRepository = attackTypeRepository;
        this.tileUrlTemplate = tileUrlTemplate;
        this.boundaryNameProperties = Arrays.stream(boundaryNameProperty.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @GetMapping("/workspace/geospatial")
    public String geospatialWorkspace(Model model, Principal principal) {
        GeospatialPointsRequest unfiltered = new GeospatialPointsRequest();
        MapResponse map = geospatialQueryService.queryPoints(unfiltered);
        YearBounds yearBounds = queryYearBounds();

        model.addAttribute("username", principal != null ? principal.getName() : null);
        model.addAttribute("minYear", yearBounds.minYear());
        model.addAttribute("maxYear", yearBounds.maxYear());
        model.addAttribute("tileUrlTemplate", tileUrlTemplate);
        model.addAttribute("boundaryNameProperties", boundaryNameProperties);
        model.addAttribute("regions", regionRepository.findAll().stream()
                .filter(r -> !r.isUnknown())
                .sorted(Comparator.comparing(r -> r.getName()))
                .toList());
        model.addAttribute("attackTypes", attackTypeRepository.findAll().stream()
                .filter(a -> !a.isUnknown())
                .sorted(Comparator.comparing(a -> a.getName()))
                .toList());
        model.addAttribute("map", map);
        return "geospatial";
    }

    @PostMapping("/api/geospatial/points")
    @ResponseBody
    public ApiResponse<MapResponse> points(@RequestBody(required = false) GeospatialPointsRequest request) {
        GeospatialPointsRequest effectiveRequest = (request != null) ? request : new GeospatialPointsRequest();
        if (effectiveRequest.getFilter() == null) {
            effectiveRequest.setFilter(new AnalyticalFilterRequest());
        }
        return ApiResponse.success(geospatialQueryService.queryPoints(effectiveRequest));
    }

    /**
     * Raw boundary GeoJSON bytes for the client's choropleth layer - the one other REPORT-style
     * exemption from section 9's "all endpoints are POST, standard envelope" convention (same
     * reasoning as {@code POST /api/report/download}, Phase 6): no filter parameters apply (a
     * country's polygon doesn't change per canonical filter, only its choropleth *count* does,
     * which stays on {@link #points}), and the payload is raw GeoJSON, not the {@code
     * success}/{@code data}/{@code meta}/{@code error} envelope. Only called by the client when
     * {@link MapResponse#boundariesAvailable()} is {@code true}.
     */
    @GetMapping(value = "/api/geospatial/boundaries", produces = "application/geo+json")
    @ResponseBody
    public ResponseEntity<byte[]> boundaries() {
        return geospatialQueryService.readBoundaryGeojson()
                .map(bytes -> ResponseEntity.ok().contentType(MediaType.valueOf("application/geo+json")).body(bytes))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * Powers the filter bar's date-range presets and the timeline's slider bounds, anchored on
     * the data's own max year rather than wall-clock time - same reasoning and same
     * one-off-query-not-a-service treatment as {@code ExecutiveController}'s identical helper
     * (Phase 4).
     */
    private YearBounds queryYearBounds() {
        return jdbcTemplate.queryForObject(
                "SELECT MIN(event_year) AS min_year, MAX(event_year) AS max_year FROM fact_incident",
                (rs, rowNum) -> {
                    int min = rs.getInt("min_year");
                    Integer minYear = rs.wasNull() ? null : min;
                    int max = rs.getInt("max_year");
                    Integer maxYear = rs.wasNull() ? null : max;
                    return new YearBounds(minYear, maxYear);
                });
    }

    private record YearBounds(Integer minYear, Integer maxYear) {
    }
}
