package com.gtd.platform.shared.db;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Maps to {@code dim_weapon_type} (CLAUDE.md section 7). */
@Entity
@Table(name = "dim_weapon_type")
public class DimWeaponType extends AbstractDimensionEntity {
}
