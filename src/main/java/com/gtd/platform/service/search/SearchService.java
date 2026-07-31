package com.gtd.platform.service.search;

import com.gtd.platform.shared.db.DimCountry;
import com.gtd.platform.shared.db.DimCountryRepository;
import com.gtd.platform.shared.db.DimOrganization;
import com.gtd.platform.shared.db.DimOrganizationRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * SEARCH (CLAUDE.md section 4): global search across workspaces,
 * organizations, countries, and quick actions - the backend for the
 * command-palette pattern shown in {@code reference/gtd-platform-demo.html}
 * (illustrative only, per CLAUDE.md section 1 - its client-side logic was
 * not copied verbatim; {@code search.js} is a fresh implementation against
 * this endpoint's own response shape).
 *
 * <p>Countries/organizations are queried live ({@link DimCountryRepository}/
 * {@link DimOrganizationRepository}, AWS-UNK-001 excluding each dimension's
 * seeded {@code is_unknown} row, top 5 each); workspaces and quick actions
 * are a small fixed list (all 15 workspaces now exist, per {@code
 * fragments/sidebar.html}'s own list) - merged into one ranked, capped
 * result list.
 */
@Service
public class SearchService {

    private static final int MAX_RESULTS = 20;

    private static final List<SearchResult> WORKSPACES = List.of(
            new SearchResult("ws-executive", "Executive", "Workspace", "/workspace/executive"),
            new SearchResult("ws-country", "Country", "Workspace", "/workspace/country"),
            new SearchResult("ws-region", "Region", "Workspace", "/workspace/region"),
            new SearchResult("ws-organization", "Organization", "Workspace", "/workspace/organization"),
            new SearchResult("ws-attack", "Attack", "Workspace", "/workspace/attack"),
            new SearchResult("ws-weapon", "Weapon", "Workspace", "/workspace/weapon"),
            new SearchResult("ws-target", "Target", "Workspace", "/workspace/target"),
            new SearchResult("ws-casualty", "Casualty", "Workspace", "/workspace/casualty"),
            new SearchResult("ws-statistical", "Statistical", "Workspace", "/workspace/statistical"),
            new SearchResult("ws-geospatial", "Geospatial", "Workspace", "/workspace/geospatial"),
            new SearchResult("ws-report", "Report", "Workspace", "/workspace/report"),
            new SearchResult("ws-administration", "Administration", "Workspace", "/workspace/administration"),
            new SearchResult("ws-dataquality", "Data Quality Center", "Workspace", "/workspace/dataquality"),
            new SearchResult("ws-audit", "Audit & Activity Log", "Workspace", "/workspace/audit"),
            new SearchResult("ws-health", "System Health Dashboard", "Workspace", "/workspace/health"));

    private static final List<SearchResult> QUICK_ACTIONS = List.of(
            new SearchResult("__theme", "Toggle light / dark theme", "Action", null),
            new SearchResult("__report", "Generate executive report", "Action", "/workspace/report"));

    private final DimCountryRepository countryRepository;
    private final DimOrganizationRepository organizationRepository;

    public SearchService(DimCountryRepository countryRepository, DimOrganizationRepository organizationRepository) {
        this.countryRepository = countryRepository;
        this.organizationRepository = organizationRepository;
    }

    public List<SearchResult> search(String query) {
        String trimmed = query == null ? "" : query.trim();
        List<SearchResult> results = new ArrayList<>();

        if (trimmed.isEmpty()) {
            results.addAll(WORKSPACES);
            results.addAll(QUICK_ACTIONS);
            return results.subList(0, Math.min(results.size(), MAX_RESULTS));
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        WORKSPACES.stream().filter(r -> r.label().toLowerCase(Locale.ROOT).contains(lower)).forEach(results::add);
        QUICK_ACTIONS.stream().filter(r -> r.label().toLowerCase(Locale.ROOT).contains(lower)).forEach(results::add);

        for (DimCountry country : countryRepository.findTop5ByNameContainingIgnoreCaseAndUnknownFalseOrderByName(trimmed)) {
            results.add(new SearchResult("country-" + country.getId(), country.getName(), "Country",
                    "/workspace/country"));
            if (results.size() >= MAX_RESULTS) {
                break;
            }
        }
        for (DimOrganization organization
                : organizationRepository.findTop5ByNameContainingIgnoreCaseAndUnknownFalseOrderByName(trimmed)) {
            results.add(new SearchResult("org-" + organization.getId(), organization.getName(), "Organization",
                    "/workspace/organization"));
            if (results.size() >= MAX_RESULTS) {
                break;
            }
        }

        return results.subList(0, Math.min(results.size(), MAX_RESULTS));
    }
}
