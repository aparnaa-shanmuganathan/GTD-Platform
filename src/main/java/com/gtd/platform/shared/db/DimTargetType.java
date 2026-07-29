package com.gtd.platform.shared.db;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Maps to {@code dim_target_type} (CLAUDE.md section 7). */
@Entity
@Table(name = "dim_target_type")
public class DimTargetType extends AbstractDimensionEntity {
}
