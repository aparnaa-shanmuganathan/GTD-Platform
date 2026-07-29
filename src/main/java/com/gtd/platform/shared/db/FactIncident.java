package com.gtd.platform.shared.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Maps to {@code fact_incident} (CLAUDE.md section 7). {@code eventid} is the
 * real GTD event identifier (e.g. {@code "197001000001"}), assigned upstream
 * during import - never generated here. {@code nkill}/{@code nwound} are
 * boxed {@link Integer}, not {@code int}, specifically so a null casualty
 * count round-trips as {@code null} rather than silently becoming {@code 0}
 * (CLAUDE.md sections 7 and 8: never coalesced to zero, enforced here at the
 * service-query/entity level in addition to the CHECK constraint in the DB).
 * The {@code region_id} column is denormalized and trigger-enforced against
 * {@code dim_country.region_id} (V4 migration) - Hibernate does not attempt
 * to re-validate that consistency; the database trigger is authoritative.
 */
@Entity
@Table(name = "fact_incident")
public class FactIncident {

    @Id
    @Column(name = "eventid", length = 12)
    private String eventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dataset_version_id", nullable = false)
    private DatasetVersion datasetVersion;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "country_id", nullable = false)
    private DimCountry country;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "region_id", nullable = false)
    private DimRegion region;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "attack_type_id", nullable = false)
    private DimAttackType attackType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "weapon_type_id", nullable = false)
    private DimWeaponType weaponType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "target_type_id", nullable = false)
    private DimTargetType targetType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    private DimOrganization organization;

    @Column(name = "event_year", nullable = false)
    private int eventYear;

    @Column(name = "event_month")
    private Integer eventMonth;

    @Column(name = "event_day")
    private Integer eventDay;

    @Column(name = "provstate", length = 150)
    private String provstate;

    @Column(name = "city", length = 150)
    private String city;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @Column(name = "summary")
    private String summary;

    @Column(name = "target_description", length = 500)
    private String targetDescription;

    @Column(name = "is_success")
    private Boolean success;

    @Column(name = "is_suicide")
    private Boolean suicide;

    @Column(name = "is_individual_perpetrator")
    private Boolean individualPerpetrator;

    @Column(name = "is_property_damage")
    private Boolean propertyDamage;

    @Column(name = "nkill")
    private Integer nkill;

    @Column(name = "nwound")
    private Integer nwound;

    @Column(name = "motive")
    private String motive;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public DatasetVersion getDatasetVersion() {
        return datasetVersion;
    }

    public void setDatasetVersion(DatasetVersion datasetVersion) {
        this.datasetVersion = datasetVersion;
    }

    public DimCountry getCountry() {
        return country;
    }

    public void setCountry(DimCountry country) {
        this.country = country;
    }

    public DimRegion getRegion() {
        return region;
    }

    public void setRegion(DimRegion region) {
        this.region = region;
    }

    public DimAttackType getAttackType() {
        return attackType;
    }

    public void setAttackType(DimAttackType attackType) {
        this.attackType = attackType;
    }

    public DimWeaponType getWeaponType() {
        return weaponType;
    }

    public void setWeaponType(DimWeaponType weaponType) {
        this.weaponType = weaponType;
    }

    public DimTargetType getTargetType() {
        return targetType;
    }

    public void setTargetType(DimTargetType targetType) {
        this.targetType = targetType;
    }

    public DimOrganization getOrganization() {
        return organization;
    }

    public void setOrganization(DimOrganization organization) {
        this.organization = organization;
    }

    public int getEventYear() {
        return eventYear;
    }

    public void setEventYear(int eventYear) {
        this.eventYear = eventYear;
    }

    public Integer getEventMonth() {
        return eventMonth;
    }

    public void setEventMonth(Integer eventMonth) {
        this.eventMonth = eventMonth;
    }

    public Integer getEventDay() {
        return eventDay;
    }

    public void setEventDay(Integer eventDay) {
        this.eventDay = eventDay;
    }

    public String getProvstate() {
        return provstate;
    }

    public void setProvstate(String provstate) {
        this.provstate = provstate;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getTargetDescription() {
        return targetDescription;
    }

    public void setTargetDescription(String targetDescription) {
        this.targetDescription = targetDescription;
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public Boolean getSuicide() {
        return suicide;
    }

    public void setSuicide(Boolean suicide) {
        this.suicide = suicide;
    }

    public Boolean getIndividualPerpetrator() {
        return individualPerpetrator;
    }

    public void setIndividualPerpetrator(Boolean individualPerpetrator) {
        this.individualPerpetrator = individualPerpetrator;
    }

    public Boolean getPropertyDamage() {
        return propertyDamage;
    }

    public void setPropertyDamage(Boolean propertyDamage) {
        this.propertyDamage = propertyDamage;
    }

    public Integer getNkill() {
        return nkill;
    }

    public void setNkill(Integer nkill) {
        this.nkill = nkill;
    }

    public Integer getNwound() {
        return nwound;
    }

    public void setNwound(Integer nwound) {
        this.nwound = nwound;
    }

    public String getMotive() {
        return motive;
    }

    public void setMotive(String motive) {
        this.motive = motive;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
