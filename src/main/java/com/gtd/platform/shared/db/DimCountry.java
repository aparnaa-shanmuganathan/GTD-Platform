package com.gtd.platform.shared.db;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Maps to {@code dim_country} (CLAUDE.md section 7). */
@Entity
@Table(name = "dim_country")
public class DimCountry extends AbstractDimensionEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "region_id", nullable = false)
    private DimRegion region;

    public DimRegion getRegion() {
        return region;
    }

    public void setRegion(DimRegion region) {
        this.region = region;
    }
}
