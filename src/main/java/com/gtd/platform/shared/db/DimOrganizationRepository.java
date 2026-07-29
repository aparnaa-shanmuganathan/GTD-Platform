package com.gtd.platform.shared.db;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DimOrganizationRepository extends JpaRepository<DimOrganization, Long> {

    Optional<DimOrganization> findByName(String name);

    Optional<DimOrganization> findByUnknownTrue();
}
