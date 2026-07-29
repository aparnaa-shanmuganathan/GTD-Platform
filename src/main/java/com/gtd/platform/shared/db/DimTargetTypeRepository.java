package com.gtd.platform.shared.db;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DimTargetTypeRepository extends JpaRepository<DimTargetType, Long> {

    Optional<DimTargetType> findByName(String name);

    Optional<DimTargetType> findByUnknownTrue();
}
