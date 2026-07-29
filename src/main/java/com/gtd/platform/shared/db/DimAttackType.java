package com.gtd.platform.shared.db;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Maps to {@code dim_attack_type} (CLAUDE.md section 7). */
@Entity
@Table(name = "dim_attack_type")
public class DimAttackType extends AbstractDimensionEntity {
}
