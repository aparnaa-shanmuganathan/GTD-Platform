package com.gtd.platform.shared.db;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DimOrganizationRepository extends JpaRepository<DimOrganization, Long> {

    Optional<DimOrganization> findByName(String name);

    Optional<DimOrganization> findByUnknownTrue();

    /** SEARCH's global command palette (Phase 10) - AWS-UNK-001 excludes the seeded is_unknown row. */
    List<DimOrganization> findTop5ByNameContainingIgnoreCaseAndUnknownFalseOrderByName(String name);
}
