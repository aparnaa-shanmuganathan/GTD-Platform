package com.gtd.platform.shared.db;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DimAttackTypeRepository extends JpaRepository<DimAttackType, Long> {

    Optional<DimAttackType> findByName(String name);

    Optional<DimAttackType> findByUnknownTrue();
}
