package com.gtd.platform.shared.db;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DimCountryRepository extends JpaRepository<DimCountry, Long> {

    Optional<DimCountry> findByName(String name);

    Optional<DimCountry> findByUnknownTrue();

    /** SEARCH's global command palette (Phase 10) - AWS-UNK-001 excludes the seeded is_unknown row. */
    List<DimCountry> findTop5ByNameContainingIgnoreCaseAndUnknownFalseOrderByName(String name);
}
