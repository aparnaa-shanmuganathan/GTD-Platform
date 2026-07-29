package com.gtd.platform.shared.db;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DatasetVersionRepository extends JpaRepository<DatasetVersion, Long> {

    /**
     * At most one row can ever match, enforced by the partial unique index
     * {@code ux_dataset_version_single_active} (V3 migration).
     */
    Optional<DatasetVersion> findByActiveTrue();

    Optional<DatasetVersion> findByImportBatch_Id(Long importBatchId);
}
