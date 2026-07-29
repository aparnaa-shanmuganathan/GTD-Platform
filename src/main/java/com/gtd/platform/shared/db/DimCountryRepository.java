package com.gtd.platform.shared.db;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DimCountryRepository extends JpaRepository<DimCountry, Long> {

    Optional<DimCountry> findByName(String name);

    Optional<DimCountry> findByUnknownTrue();
}
