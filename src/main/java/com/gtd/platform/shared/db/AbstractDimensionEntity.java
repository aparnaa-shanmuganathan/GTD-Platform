package com.gtd.platform.shared.db;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;

/**
 * Common shape shared by all six dim_* tables (CLAUDE.md section 7): a
 * surrogate id, a unique name, and the {@code is_unknown} flag row used by
 * AWS-UNK-001 instead of a nullable FK. Each concrete dimension is its own
 * {@code @Entity}/table, per the frozen schema shape - this only removes the
 * identical id/name/isUnknown/createdAt boilerplate repeated six times.
 */
@MappedSuperclass
public abstract class AbstractDimensionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 150)
    private String name;

    @Column(name = "is_unknown", nullable = false)
    private boolean unknown;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isUnknown() {
        return unknown;
    }

    public void setUnknown(boolean unknown) {
        this.unknown = unknown;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
