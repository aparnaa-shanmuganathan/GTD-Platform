package com.gtd.platform.service.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gtd.platform.shared.db.DimAttackType;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
import com.gtd.platform.shared.db.DimCountry;
import com.gtd.platform.shared.db.DimCountryRepository;
import com.gtd.platform.shared.db.DimOrganization;
import com.gtd.platform.shared.db.DimOrganizationRepository;
import com.gtd.platform.shared.db.DimRegion;
import com.gtd.platform.shared.db.DimRegionRepository;
import com.gtd.platform.shared.db.DimTargetType;
import com.gtd.platform.shared.db.DimTargetTypeRepository;
import com.gtd.platform.shared.db.DimWeaponType;
import com.gtd.platform.shared.db.DimWeaponTypeRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit tests of {@link DimensionResolver}'s resolution algorithm - no
 * Spring context, no database. All six repositories are mocked so these
 * tests exercise exactly the caching/fallback/creation logic documented on
 * the class, independent of real seed data.
 */
@ExtendWith(MockitoExtension.class)
class DimensionResolverTest {

    @Mock
    private DimRegionRepository regionRepository;
    @Mock
    private DimCountryRepository countryRepository;
    @Mock
    private DimOrganizationRepository organizationRepository;
    @Mock
    private DimAttackTypeRepository attackTypeRepository;
    @Mock
    private DimWeaponTypeRepository weaponTypeRepository;
    @Mock
    private DimTargetTypeRepository targetTypeRepository;

    private DimensionResolver resolver;

    private DimRegion northAmerica;
    private DimRegion southAmerica;
    private DimCountry unitedStates;
    private DimOrganization unknownOrg;

    @BeforeEach
    void setUp() {
        resolver = new DimensionResolver(regionRepository, countryRepository, organizationRepository,
                attackTypeRepository, weaponTypeRepository, targetTypeRepository);

        northAmerica = dimension(DimRegion::new, 1L, "North America");
        southAmerica = dimension(DimRegion::new, 2L, "South America");
        when(regionRepository.findAll()).thenReturn(List.of(northAmerica, southAmerica));

        unitedStates = dimension(DimCountry::new, 10L, "United States");
        unitedStates.setRegion(northAmerica);
        when(countryRepository.findAll()).thenReturn(List.of(unitedStates));

        unknownOrg = dimension(DimOrganization::new, 100L, "Unknown");
        unknownOrg.setUnknown(true);
        DimOrganization taliban = dimension(DimOrganization::new, 101L, "Taliban");
        when(organizationRepository.findAll()).thenReturn(List.of(unknownOrg, taliban));
        when(organizationRepository.findByUnknownTrue()).thenReturn(Optional.of(unknownOrg));

        DimAttackType unknownAttack = dimension(DimAttackType::new, 200L, "Unknown");
        unknownAttack.setUnknown(true);
        when(attackTypeRepository.findAll()).thenReturn(List.of(unknownAttack));
        when(attackTypeRepository.findByUnknownTrue()).thenReturn(Optional.of(unknownAttack));

        DimWeaponType unknownWeapon = dimension(DimWeaponType::new, 300L, "Unknown");
        unknownWeapon.setUnknown(true);
        when(weaponTypeRepository.findAll()).thenReturn(List.of(unknownWeapon));
        when(weaponTypeRepository.findByUnknownTrue()).thenReturn(Optional.of(unknownWeapon));

        DimTargetType unknownTarget = dimension(DimTargetType::new, 400L, "Unknown");
        unknownTarget.setUnknown(true);
        when(targetTypeRepository.findAll()).thenReturn(List.of(unknownTarget));
        when(targetTypeRepository.findByUnknownTrue()).thenReturn(Optional.of(unknownTarget));

        resolver.initializeCaches();
    }

    @Test
    void resolvesAnExistingCountryFromCacheWithoutCreatingARow() {
        DimensionResolver.CountryResolution resolution = resolver.resolveCountry("United States", "North America");

        assertThat(resolution.countryId()).isEqualTo(10L);
        assertThat(resolution.regionId()).isEqualTo(1L);
        verify(countryRepository, never()).save(any());
    }

    @Test
    void anExistingCountrysRegionIsNeverOverriddenByALaterRowsRegionTxt() {
        // Same country, but this row's region_txt disagrees with the seeded
        // pairing - the cached (authoritative) region must win, not this row's.
        DimensionResolver.CountryResolution resolution = resolver.resolveCountry("United States", "South America");

        assertThat(resolution.countryId()).isEqualTo(10L);
        assertThat(resolution.regionId()).isEqualTo(1L);
    }

    @Test
    void createsANewCountryPairedWithItsResolvedRegion() {
        DimCountry saved = dimension(DimCountry::new, 11L, "Canada");
        when(countryRepository.save(any())).thenReturn(saved);
        when(regionRepository.getReferenceById(1L)).thenReturn(northAmerica);

        DimensionResolver.CountryResolution resolution = resolver.resolveCountry("Canada", "North America");

        assertThat(resolution.countryId()).isEqualTo(11L);
        assertThat(resolution.regionId()).isEqualTo(1L);
        verify(countryRepository).save(any());
    }

    @Test
    void blankCountryIsUnresolvable() {
        assertThat(resolver.resolveCountry("", "North America")).isNull();
        assertThat(resolver.resolveCountry(null, "North America")).isNull();
    }

    @Test
    void newCountryWithUnresolvableRegionIsUnresolvable() {
        assertThat(resolver.resolveCountry("Atlantis", "Nowhere Region")).isNull();
        verify(countryRepository, never()).save(any());
    }

    @Test
    void blankOrganizationFallsBackToTheSeededUnknownRow() {
        long id = resolver.resolveOrganization("");
        assertThat(id).isEqualTo(100L);
        verify(organizationRepository, never()).save(any());
    }

    @Test
    void literalUnknownTextMatchesTheSeededUnknownRowRatherThanCreatingADuplicate() {
        long id = resolver.resolveOrganization("Unknown");
        assertThat(id).isEqualTo(100L);
        verify(organizationRepository, never()).save(any());
    }

    @Test
    void knownOrganizationIsCaseInsensitivelyMatchedFromCache() {
        long id = resolver.resolveOrganization("TALIBAN");
        assertThat(id).isEqualTo(101L);
        verify(organizationRepository, never()).save(any());
    }

    @Test
    void newOrganizationIsCreatedOnceAndThenCached() {
        DimOrganization saved = dimension(DimOrganization::new, 102L, "Boko Haram");
        when(organizationRepository.save(any())).thenReturn(saved);

        long firstId = resolver.resolveOrganization("Boko Haram");
        long secondId = resolver.resolveOrganization("boko haram");

        assertThat(firstId).isEqualTo(102L);
        assertThat(secondId).isEqualTo(102L);
        verify(organizationRepository, org.mockito.Mockito.times(1)).save(any());
    }

    private <T> T dimension(java.util.function.Supplier<T> factory, long id, String name) {
        T entity = factory.get();
        if (entity instanceof com.gtd.platform.shared.db.AbstractDimensionEntity dim) {
            dim.setId(id);
            dim.setName(name);
            dim.setCreatedAt(Instant.now());
        }
        return entity;
    }
}
