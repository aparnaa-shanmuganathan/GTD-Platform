package com.gtd.platform.service.imports;

import com.gtd.platform.shared.db.AbstractDimensionEntity;
import com.gtd.platform.shared.db.DimAttackType;
import com.gtd.platform.shared.db.DimAttackTypeRepository;
import com.gtd.platform.shared.db.DimCountry;
import com.gtd.platform.shared.db.DimCountryRepository;
import com.gtd.platform.shared.db.DimOrganization;
import com.gtd.platform.shared.db.DimOrganizationRepository;
import com.gtd.platform.shared.db.DimRegionRepository;
import com.gtd.platform.shared.db.DimTargetType;
import com.gtd.platform.shared.db.DimTargetTypeRepository;
import com.gtd.platform.shared.db.DimWeaponType;
import com.gtd.platform.shared.db.DimWeaponTypeRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Resolves (and, when needed, creates) dimension rows for one import job, per
 * CLAUDE.md's normalization requirement. Two distinct behaviors, both
 * governed by AWS-UNK-001 (CLAUDE.md section 8):
 *
 * <ul>
 *   <li>Organization/Attack Type/Weapon Type/Target Type: a blank source
 *   value resolves to that dimension's seeded {@code is_unknown} row - never
 *   a nullable FK. A non-blank value not yet seen creates a new row.</li>
 *   <li>Country/Region: AWS-UNK-001 says these "use staging-level exclusion
 *   instead (different mechanism)" - a row whose country can't be resolved
 *   (blank, or a brand-new country whose region doesn't match one of the 12
 *   canonical seeded regions) is rejected outright by {@link RowNormalizer},
 *   not mapped to an is_unknown row. Regions are treated as a closed,
 *   well-known set (CLAUDE.md section 12) - unlike organizations/countries,
 *   new region rows are never auto-created here.</li>
 * </ul>
 *
 * <p>Not thread-safe by design: a single import job's normalization pass is
 * strictly single-threaded (see {@code ImportAsyncConfig}'s single-thread
 * executor), and the advisory lock guarantees only one job runs at a time -
 * so a plain, uncontended {@link HashMap} cache is correct and avoids
 * needless synchronization. {@link #initializeCaches()} must be called once
 * at the start of every import job to reset it.
 */
@Component
public class DimensionResolver {

    private final DimRegionRepository regionRepository;
    private final DimCountryRepository countryRepository;
    private final DimOrganizationRepository organizationRepository;
    private final DimAttackTypeRepository attackTypeRepository;
    private final DimWeaponTypeRepository weaponTypeRepository;
    private final DimTargetTypeRepository targetTypeRepository;

    private Map<String, Long> regionCache;
    private Map<String, CountryResolution> countryCache;
    private Map<String, Long> organizationCache;
    private Map<String, Long> attackTypeCache;
    private Map<String, Long> weaponTypeCache;
    private Map<String, Long> targetTypeCache;
    private long unknownOrganizationId;
    private long unknownAttackTypeId;
    private long unknownWeaponTypeId;
    private long unknownTargetTypeId;

    public DimensionResolver(DimRegionRepository regionRepository, DimCountryRepository countryRepository,
            DimOrganizationRepository organizationRepository, DimAttackTypeRepository attackTypeRepository,
            DimWeaponTypeRepository weaponTypeRepository, DimTargetTypeRepository targetTypeRepository) {
        this.regionRepository = regionRepository;
        this.countryRepository = countryRepository;
        this.organizationRepository = organizationRepository;
        this.attackTypeRepository = attackTypeRepository;
        this.weaponTypeRepository = weaponTypeRepository;
        this.targetTypeRepository = targetTypeRepository;
    }

    public void initializeCaches() {
        regionCache = new HashMap<>();
        regionRepository.findAll().forEach(r -> regionCache.put(key(r.getName()), r.getId()));

        countryCache = new HashMap<>();
        countryRepository.findAll().forEach(c ->
                countryCache.put(key(c.getName()), new CountryResolution(c.getId(), c.getRegion().getId())));

        organizationCache = loadCache(organizationRepository.findAll());
        attackTypeCache = loadCache(attackTypeRepository.findAll());
        weaponTypeCache = loadCache(weaponTypeRepository.findAll());
        targetTypeCache = loadCache(targetTypeRepository.findAll());

        unknownOrganizationId = organizationRepository.findByUnknownTrue().orElseThrow().getId();
        unknownAttackTypeId = attackTypeRepository.findByUnknownTrue().orElseThrow().getId();
        unknownWeaponTypeId = weaponTypeRepository.findByUnknownTrue().orElseThrow().getId();
        unknownTargetTypeId = targetTypeRepository.findByUnknownTrue().orElseThrow().getId();
    }

    /**
     * @return the resolved (country id, region id) pair, or {@code null} if
     *         this row's country cannot be resolved and should be rejected
     *         (AWS-UNK-001 staging-level exclusion).
     */
    public CountryResolution resolveCountry(String countryTxt, String regionTxt) {
        String countryKey = key(countryTxt);
        if (countryKey == null) {
            return null;
        }
        CountryResolution cached = countryCache.get(countryKey);
        if (cached != null) {
            // A country's region assignment, once established (seeded or
            // first-created), is authoritative - never re-derived from a
            // later row's region_txt, so fact_incident's region-consistency
            // trigger can never be violated by this resolver.
            return cached;
        }
        String regionKey = key(regionTxt);
        Long regionId = (regionKey == null) ? null : regionCache.get(regionKey);
        if (regionId == null) {
            return null;
        }
        DimCountry country = new DimCountry();
        country.setName(countryTxt.trim());
        country.setRegion(regionRepository.getReferenceById(regionId));
        country.setUnknown(false);
        country.setCreatedAt(Instant.now());
        long newId = countryRepository.save(country).getId();
        CountryResolution resolution = new CountryResolution(newId, regionId);
        countryCache.put(countryKey, resolution);
        return resolution;
    }

    public long resolveOrganization(String gname) {
        return resolveOrCreate(gname, organizationCache, unknownOrganizationId, name -> {
            DimOrganization entity = new DimOrganization();
            entity.setName(name);
            entity.setUnknown(false);
            entity.setCreatedAt(Instant.now());
            return organizationRepository.save(entity).getId();
        });
    }

    public long resolveAttackType(String attackTypeTxt) {
        return resolveOrCreate(attackTypeTxt, attackTypeCache, unknownAttackTypeId, name -> {
            DimAttackType entity = new DimAttackType();
            entity.setName(name);
            entity.setUnknown(false);
            entity.setCreatedAt(Instant.now());
            return attackTypeRepository.save(entity).getId();
        });
    }

    public long resolveWeaponType(String weaponTypeTxt) {
        return resolveOrCreate(weaponTypeTxt, weaponTypeCache, unknownWeaponTypeId, name -> {
            DimWeaponType entity = new DimWeaponType();
            entity.setName(name);
            entity.setUnknown(false);
            entity.setCreatedAt(Instant.now());
            return weaponTypeRepository.save(entity).getId();
        });
    }

    public long resolveTargetType(String targetTypeTxt) {
        return resolveOrCreate(targetTypeTxt, targetTypeCache, unknownTargetTypeId, name -> {
            DimTargetType entity = new DimTargetType();
            entity.setName(name);
            entity.setUnknown(false);
            entity.setCreatedAt(Instant.now());
            return targetTypeRepository.save(entity).getId();
        });
    }

    private long resolveOrCreate(String rawValue, Map<String, Long> cache, long unknownId, Function<String, Long> creator) {
        String cacheKey = key(rawValue);
        if (cacheKey == null) {
            return unknownId;
        }
        Long existing = cache.get(cacheKey);
        if (existing != null) {
            return existing;
        }
        long newId = creator.apply(rawValue.trim());
        cache.put(cacheKey, newId);
        return newId;
    }

    private static Map<String, Long> loadCache(List<? extends AbstractDimensionEntity> entities) {
        Map<String, Long> cache = new HashMap<>();
        entities.forEach(e -> cache.put(key(e.getName()), e.getId()));
        return cache;
    }

    private static String key(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    public record CountryResolution(long countryId, long regionId) {
    }
}
