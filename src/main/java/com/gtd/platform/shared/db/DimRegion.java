package com.gtd.platform.shared.db;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Maps to {@code dim_region} (CLAUDE.md section 7). */
@Entity
@Table(name = "dim_region")
public class DimRegion extends AbstractDimensionEntity {
}
