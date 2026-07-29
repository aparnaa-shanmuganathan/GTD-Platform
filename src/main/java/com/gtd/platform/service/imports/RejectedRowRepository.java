package com.gtd.platform.service.imports;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RejectedRowRepository extends JpaRepository<RejectedRow, Long> {

    List<RejectedRow> findByImportBatchId(Long importBatchId);

    long countByImportBatchId(Long importBatchId);
}
