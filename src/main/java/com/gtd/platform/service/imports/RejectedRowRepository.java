package com.gtd.platform.service.imports;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RejectedRowRepository extends JpaRepository<RejectedRow, Long> {

    List<RejectedRow> findByImportBatchId(Long importBatchId);

    /** Data Quality Center's per-batch rejected-row list (Phase 10) - bounded, unlike the unpaginated overload above. */
    Page<RejectedRow> findByImportBatchId(Long importBatchId, Pageable pageable);

    long countByImportBatchId(Long importBatchId);
}
