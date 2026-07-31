package com.gtd.platform.service.search;

import com.gtd.platform.shared.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SEARCH's one HTTP surface (CLAUDE.md section 9: POST, standard envelope).
 * Public per the access model amendment (CLAUDE.md section 12) - a search
 * query never changes platform state.
 */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping
    public ApiResponse<List<SearchResult>> search(@RequestBody(required = false) SearchRequest request) {
        String query = request != null ? request.query() : null;
        return ApiResponse.success(searchService.search(query));
    }
}
