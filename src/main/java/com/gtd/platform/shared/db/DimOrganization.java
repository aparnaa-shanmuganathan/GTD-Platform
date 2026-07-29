package com.gtd.platform.shared.db;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Maps to {@code dim_organization} (CLAUDE.md section 7). */
@Entity
@Table(name = "dim_organization")
@AttributeOverride(name = "name", column = @Column(name = "name", nullable = false, unique = true, length = 255))
public class DimOrganization extends AbstractDimensionEntity {
}
