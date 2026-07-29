package com.gtd.platform.shared.db;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DimWeaponTypeRepository extends JpaRepository<DimWeaponType, Long> {

    Optional<DimWeaponType> findByName(String name);

    Optional<DimWeaponType> findByUnknownTrue();
}
