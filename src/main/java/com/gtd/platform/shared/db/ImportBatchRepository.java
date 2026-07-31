package com.gtd.platform.shared.db;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    /** Administration Workspace's import history panel (Phase 10) - most recent batches first. */
    List<ImportBatch> findTop20ByOrderByCreatedAtDesc();
}
