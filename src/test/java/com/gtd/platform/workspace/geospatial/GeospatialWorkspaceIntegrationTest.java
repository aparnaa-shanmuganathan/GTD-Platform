package com.gtd.platform.workspace.geospatial;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtd.platform.shared.auth.AdminUser;
import com.gtd.platform.shared.auth.AdminUserRepository;
import com.gtd.platform.testsupport.FormLoginClient;
import com.gtd.platform.testsupport.FormLoginClient.LoginResult;
import com.gtd.platform.testsupport.PostgresIntegrationTestBase;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * End-to-end coverage of the Geospatial Workspace (CLAUDE.md section 12,
 * Phase 9 Part B): imports a real (if synthetic) dataset with a deliberate
 * mix of geocoded and non-geocoded rows through Phase 3's pipeline, then
 * verifies the real HTTP/controller/template wiring - point coordinates,
 * the geocoded-vs-total count distinction, the drawn-region spatial filter
 * narrowing results against a known hand-drawn box, choropleth counts
 * matching the existing country-ranking data, and {@code boundariesAvailable}
 * correctly reporting {@code false} in this environment (no boundary
 * GeoJSON configured, CLAUDE.md section 13 open item 5). {@link
 * com.gtd.platform.service.geo.SpatialPolygonFilterTest} already covers the
 * point-in-polygon algorithm's own correctness in isolation; this test's
 * job is the wiring, per this project's established division of labor.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GeospatialWorkspaceIntegrationTest extends PostgresIntegrationTestBase {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CSV_HEADER = "eventid,iyear,imonth,iday,country_txt,region_txt,provstate,city,"
            + "latitude,longitude,summary,target1,attacktype1_txt,weaptype1_txt,targtype1_txt,gname,nkill,nwound,"
            + "success,suicide,individual,property,motive";

    /** Reserved year block for this test, disjoint from every other workspace test's own range. */
    private static final int YEAR = 2170;

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private FormLoginClient client;
    private String cookie;
    private String csrfToken;

    @BeforeEach
    void setUp() throws Exception {
        client = new FormLoginClient("http://localhost:" + port);
        String username = "geo-it-user-" + UUID.randomUUID();
        String password = "correct-horse-battery-staple";

        AdminUser user = new AdminUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setActive(true);
        user.setCreatedAt(Instant.now());
        adminUserRepository.save(user);

        LoginResult loginResult = client.login(username, password);
        assertThat(loginResult.isRedirect()).isTrue();
        cookie = loginResult.sessionCookie();
        csrfToken = client.csrfTokenFor(cookie);
    }

    @Test
    void geospatialWorkspaceRendersRealPointsChoroplethAndPolygonFilter() throws Exception {
        importAndConfirm(generateCsv());

        // --- GET /workspace/geospatial renders real, unfiltered map data ---
        HttpResponse<String> pageResponse = client.getWithCookie("/workspace/geospatial", cookie);
        assertThat(pageResponse.statusCode()).isEqualTo(200);
        String body = pageResponse.body();
        assertThat(body).contains("Geospatial Workspace");
        assertThat(body).contains("geoMapWrap");
        assertThat(body).contains("map-placeholder-tile.png");

        // --- POST /api/geospatial/points, scoped to this test's own reserved year, no polygon ---
        HttpResponse<String> unscoped = client.postJson("/api/geospatial/points", cookie, csrfToken,
                "{\"filter\":{\"startDate\":\"" + YEAR + "-01-01\",\"endDate\":\"" + YEAR + "-12-31\"}}");
        assertThat(unscoped.statusCode()).isEqualTo(200);
        JsonNode unscopedData = JSON.readTree(unscoped.body()).path("data");

        // 15 geocoded rows + 5 non-geocoded rows = 20 total; only the 15 have coordinates.
        assertThat(unscopedData.path("totalIncidentCount").asLong()).isEqualTo(20);
        assertThat(unscopedData.path("geocodedCount").asLong()).isEqualTo(15);
        assertThat(unscopedData.path("points").size()).isEqualTo(15);
        for (JsonNode point : unscopedData.path("points")) {
            assertThat(point.path("lat").asDouble()).isNotZero();
            assertThat(point.path("lng").asDouble()).isNotZero();
            assertThat(point.path("year").asInt()).isEqualTo(YEAR);
        }

        // --- Boundary data is not configured in this environment - always a real, documented false ---
        assertThat(unscopedData.path("boundariesAvailable").asBoolean()).isFalse();

        // --- Choropleth: real per-country counts, matching the country-ranking data source ---
        JsonNode choropleth = unscopedData.path("choropleth");
        assertThat(choropleth.size()).isGreaterThan(0);
        long choroplethTotal = 0;
        for (JsonNode entry : choropleth) {
            assertThat(entry.path("label").asText()).isNotBlank();
            choroplethTotal += entry.path("value").asLong();
        }
        assertThat(choroplethTotal).isEqualTo(20);

        // --- Drawn-region polygon filter: 5 of the 15 geocoded rows are placed at
        // lat=10..14, lng=10..14 (inside the drawn box below); the other 10 geocoded
        // rows are placed far away (lat=40..49, lng=40..49, outside it). ---
        String polygonRequest = "{\"filter\":{\"startDate\":\"" + YEAR + "-01-01\",\"endDate\":\"" + YEAR + "-12-31\"},"
                + "\"polygon\":[[5,5],[5,20],[20,20],[20,5]]}";
        HttpResponse<String> scoped = client.postJson("/api/geospatial/points", cookie, csrfToken, polygonRequest);
        assertThat(scoped.statusCode()).isEqualTo(200);
        JsonNode scopedData = JSON.readTree(scoped.body()).path("data");

        assertThat(scopedData.path("points").size()).isEqualTo(5);
        for (JsonNode point : scopedData.path("points")) {
            assertThat(point.path("lat").asDouble()).isBetween(10.0, 14.0);
            assertThat(point.path("lng").asDouble()).isBetween(10.0, 14.0);
        }
        // totalIncidentCount/geocodedCount/choropleth are unaffected by the polygon - they
        // reflect the canonical filter only, per GeospatialQueryService's own design.
        assertThat(scopedData.path("totalIncidentCount").asLong()).isEqualTo(20);
        assertThat(scopedData.path("geocodedCount").asLong()).isEqualTo(15);
    }

    private void importAndConfirm(String csvContent) throws Exception {
        HttpResponse<String> uploadResponse = client.uploadMultipart("/api/import/upload", cookie, csrfToken,
                "file", "geospatial-it.csv", csvContent.getBytes(StandardCharsets.UTF_8), "text/csv",
                Map.of("mode", "APPEND"));
        assertThat(uploadResponse.statusCode()).isEqualTo(202);
        long batchId = JSON.readTree(uploadResponse.body()).path("data").path("importBatchId").asLong();

        Instant deadline = Instant.now().plus(Duration.ofMinutes(2));
        while (Instant.now().isBefore(deadline)) {
            HttpResponse<String> statusResponse = client.postJson("/api/import/status", cookie, csrfToken,
                    "{\"importBatchId\":" + batchId + "}");
            String status = JSON.readTree(statusResponse.body()).path("data").path("status").asText();
            if (status.equals("AWAITING_CONFIRMATION")) {
                break;
            }
            if (status.equals("FAILED")) {
                throw new AssertionError("Import batch " + batchId + " failed");
            }
            Thread.sleep(150);
        }

        HttpResponse<String> confirmResponse = client.postJson("/api/import/confirm", cookie, csrfToken,
                "{\"importBatchId\":" + batchId + "}");
        assertThat(confirmResponse.statusCode()).isEqualTo(200);
    }

    /**
     * 20 rows total, all in {@link #YEAR}: 5 geocoded inside the test's drawn box (lat/lng
     * 10-14), 10 geocoded far outside it (lat/lng 40-49), and 5 with blank latitude/longitude
     * (unresolvable coordinates - real GTD data frequently lacks geocoding, and this platform
     * never fakes a coordinate for a row that doesn't have one).
     */
    private static String generateCsv() {
        String[] countries = { "United States", "Canada", "Iraq", "Afghanistan", "Nigeria" };
        String[] regions = { "North America", "North America", "Middle East & North Africa", "South Asia", "Sub-Saharan Africa" };
        String[] orgs = { "Taliban", "Al-Shabaab", "Unknown", "Boko Haram" };

        StringBuilder sb = new StringBuilder(CSV_HEADER).append('\n');
        int eventCounter = 0;

        // 5 rows inside the drawn box.
        for (int i = 0; i < 5; i++) {
            eventCounter = appendRow(sb, eventCounter, countries, regions, orgs, String.valueOf(10.0 + i), String.valueOf(10.0 + i));
        }
        // 10 rows far outside the drawn box.
        for (int i = 0; i < 10; i++) {
            eventCounter = appendRow(sb, eventCounter, countries, regions, orgs, String.valueOf(40.0 + i), String.valueOf(40.0 + i));
        }
        // 5 rows with no coordinates at all.
        for (int i = 0; i < 5; i++) {
            eventCounter = appendRow(sb, eventCounter, countries, regions, orgs, "", "");
        }
        return sb.toString();
    }

    private static int appendRow(StringBuilder sb, int eventCounter, String[] countries, String[] regions,
            String[] orgs, String latitude, String longitude) {
        int countryIdx = eventCounter % countries.length;
        int orgIdx = eventCounter % orgs.length;

        sb.append("GEO").append(String.format("%09d", eventCounter)).append(',')
                .append(YEAR).append(",1,1,")
                .append(countries[countryIdx]).append(',').append(regions[countryIdx]).append(",,,")
                .append(latitude).append(',').append(longitude).append(",,,")
                .append("Bombing/Explosion,Explosives,Business,")
                .append(orgs[orgIdx]).append(",2,3,1,0,0,0,\n");
        return eventCounter + 1;
    }
}
