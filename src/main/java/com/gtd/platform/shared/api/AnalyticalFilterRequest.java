package com.gtd.platform.shared.api;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The canonical filter request DTO, CAC-UI-003 (CLAUDE.md section 9): date
 * range, region, country, attack type, weapon type, target type,
 * organization. Every workspace filter bar composes or extends this one
 * class instead of duplicating its own filter shape.
 *
 * <p>Dimension filters are lists of {@code dim_*} surrogate ids (an empty or
 * {@code null} list means "no filter on this dimension," not "match
 * nothing"). This is the shape only, per Phase 2 scope - no cross-field
 * validation (e.g. {@code endDate >= startDate}) or AWS-UNK-001 exclusion
 * logic is implemented here yet; that belongs to whichever service
 * (SVC-AGG/SVC-TREND/etc.) actually applies the filter in a later phase.
 *
 * <p>Deliberately a plain mutable class, not a record: subclasses that need
 * workspace-specific additions (e.g. a comparison workspace adding a
 * "compare against" country id) can {@code extend} it and inherit these
 * fields, which a record's canonical constructor would not allow.
 */
public class AnalyticalFilterRequest {

    private LocalDate startDate;
    private LocalDate endDate;
    private List<Long> regionIds = new ArrayList<>();
    private List<Long> countryIds = new ArrayList<>();
    private List<Long> attackTypeIds = new ArrayList<>();
    private List<Long> weaponTypeIds = new ArrayList<>();
    private List<Long> targetTypeIds = new ArrayList<>();
    private List<Long> organizationIds = new ArrayList<>();

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public List<Long> getRegionIds() {
        return regionIds;
    }

    public void setRegionIds(List<Long> regionIds) {
        this.regionIds = regionIds;
    }

    public List<Long> getCountryIds() {
        return countryIds;
    }

    public void setCountryIds(List<Long> countryIds) {
        this.countryIds = countryIds;
    }

    public List<Long> getAttackTypeIds() {
        return attackTypeIds;
    }

    public void setAttackTypeIds(List<Long> attackTypeIds) {
        this.attackTypeIds = attackTypeIds;
    }

    public List<Long> getWeaponTypeIds() {
        return weaponTypeIds;
    }

    public void setWeaponTypeIds(List<Long> weaponTypeIds) {
        this.weaponTypeIds = weaponTypeIds;
    }

    public List<Long> getTargetTypeIds() {
        return targetTypeIds;
    }

    public void setTargetTypeIds(List<Long> targetTypeIds) {
        this.targetTypeIds = targetTypeIds;
    }

    public List<Long> getOrganizationIds() {
        return organizationIds;
    }

    public void setOrganizationIds(List<Long> organizationIds) {
        this.organizationIds = organizationIds;
    }
}
