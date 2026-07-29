package com.gtd.platform.shared.db;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DimRegionRepository extends JpaRepository<DimRegion, Long> {

    Optional<DimRegion> findByName(String name);

    Optional<DimRegion> findByUnknownTrue();
}
