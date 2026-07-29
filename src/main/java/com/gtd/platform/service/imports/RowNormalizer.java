package com.gtd.platform.service.imports;

import org.springframework.stereotype.Component;

/**
 * Turns one {@link StagingRow} into either a validated {@link FactIncidentRecord}
 * or a documented {@link RejectionReason}, per CLAUDE.md section 12's Phase 3
 * validation rules:
 *
 * <ul>
 *   <li>{@code eventid}: required, &le;12 chars - {@code fact_incident}'s PK.</li>
 *   <li>{@code iyear}: required, a positive year - {@code fact_incident.event_year}
 *   is {@code NOT NULL}. {@code imonth}/{@code iday} of {@code 0} is the GTD
 *   convention for "unknown," not an error - both map to {@code null}
 *   (nullable columns), same as any other unparseable/out-of-range value;
 *   only {@code iyear} failing can reject the whole row.</li>
 *   <li>{@code nkill}/{@code nwound}: blank (or GTD/pandas's {@code "NaN"}
 *   text, common when a Kaggle-style export upcasts an int column with
 *   missing values to float) means unknown -&gt; {@code null}, never
 *   coalesced to zero (CLAUDE.md sections 7/8). A negative value or genuine
 *   garbage text rejects the row. Values are parsed leniently (accepting a
 *   {@code ".0"} suffix, another common pandas-export artifact) rather than
 *   requiring a strict integer literal.</li>
 *   <li>Country/region: {@link DimensionResolver#resolveCountry} returning
 *   {@code null} means AWS-UNK-001 staging-level exclusion applies - reject.</li>
 *   <li>Organization/attack/weapon/target type: never reject - {@link DimensionResolver}
 *   always resolves these (creating a new dimension row, or falling back to
 *   the is_unknown row for a blank value).</li>
 * </ul>
 *
 * <p>Only the <em>primary</em> attack type/weapon type/target type/perpetrator
 * (the CSV's {@code attacktype1}/{@code weaptype1}/{@code targtype1}/{@code gname}
 * columns) are used - {@code fact_incident} has a single column for each,
 * not a multi-valued join table (a Phase 1 schema decision this phase
 * continues rather than silently redesigns). An incident coded with a
 * secondary attack/weapon/target type in the source data loses that
 * secondary classification here; flagged in CLAUDE.md section 12.
 */
@Component
public class RowNormalizer {

    private final DimensionResolver dimensionResolver;

    public RowNormalizer(DimensionResolver dimensionResolver) {
        this.dimensionResolver = dimensionResolver;
    }

    public NormalizationResult normalize(StagingRow row, long datasetVersionId) {
        String eventId = trimToNull(row.eventId());
        if (eventId == null || eventId.length() > 12) {
            return new NormalizationResult.Rejected(RejectionReason.MISSING_OR_INVALID_EVENTID,
                    "eventid='" + row.eventId() + "'");
        }

        Integer eventYear = parsePositiveInt(row.iyear());
        if (eventYear == null) {
            return new NormalizationResult.Rejected(RejectionReason.MISSING_OR_INVALID_YEAR,
                    "iyear='" + row.iyear() + "'");
        }
        Integer eventMonth = parseMonthOrDayOrNull(row.imonth(), 12);
        Integer eventDay = parseMonthOrDayOrNull(row.iday(), 31);

        Integer nkill;
        Integer nwound;
        try {
            nkill = parseNonNegativeIntOrNull(row.nkill());
            nwound = parseNonNegativeIntOrNull(row.nwound());
        } catch (NumberFormatException e) {
            return new NormalizationResult.Rejected(RejectionReason.INVALID_CASUALTY_COUNT,
                    "nkill='" + row.nkill() + "', nwound='" + row.nwound() + "'");
        }

        DimensionResolver.CountryResolution country = dimensionResolver.resolveCountry(row.countryTxt(), row.regionTxt());
        if (country == null) {
            return new NormalizationResult.Rejected(RejectionReason.UNRESOLVABLE_COUNTRY_OR_REGION,
                    "country_txt='" + row.countryTxt() + "', region_txt='" + row.regionTxt() + "'");
        }

        long attackTypeId = dimensionResolver.resolveAttackType(row.attackType1Txt());
        long weaponTypeId = dimensionResolver.resolveWeaponType(row.weaponType1Txt());
        long targetTypeId = dimensionResolver.resolveTargetType(row.targetType1Txt());
        long organizationId = dimensionResolver.resolveOrganization(row.gname());

        FactIncidentRecord record = new FactIncidentRecord(
                row.id(), eventId, datasetVersionId, country.countryId(), country.regionId(),
                attackTypeId, weaponTypeId, targetTypeId, organizationId,
                eventYear, eventMonth, eventDay,
                trimToNull(row.provstate()), trimToNull(row.city()),
                parseDoubleOrNull(row.latitude()), parseDoubleOrNull(row.longitude()),
                trimToNull(row.summary()), trimToNull(row.target1()),
                parseBooleanOrNull(row.success()), parseBooleanOrNull(row.suicide()),
                parseBooleanOrNull(row.individual()), parseBooleanOrNull(row.property()),
                nkill, nwound, trimToNull(row.motive())
        );
        return new NormalizationResult.Accepted(record);
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static Integer parsePositiveInt(String raw) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            return null;
        }
        try {
            Integer value = parseLenientInt(trimmed);
            return (value != null && value > 0) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseMonthOrDayOrNull(String raw, int max) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            return null;
        }
        try {
            Integer value = parseLenientInt(trimmed);
            return (value != null && value >= 1 && value <= max) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseNonNegativeIntOrNull(String raw) {
        String trimmed = trimToNull(raw);
        if (trimmed == null || trimmed.equalsIgnoreCase("nan")) {
            return null;
        }
        Integer value = parseLenientInt(trimmed);
        if (value == null) {
            return null;
        }
        if (value < 0) {
            throw new NumberFormatException("negative casualty count: " + trimmed);
        }
        return value;
    }

    /** Accepts both "5" and pandas-export-style "5.0"; "NaN" (any case) parses to {@code null}. */
    private static Integer parseLenientInt(String trimmed) {
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            double parsed = Double.parseDouble(trimmed);
            return Double.isNaN(parsed) ? null : (int) Math.round(parsed);
        }
    }

    private static Double parseDoubleOrNull(String raw) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(trimmed);
            return Double.isNaN(value) ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean parseBooleanOrNull(String raw) {
        String trimmed = trimToNull(raw);
        if (trimmed == null) {
            return null;
        }
        if (trimmed.equals("1")) {
            return Boolean.TRUE;
        }
        if (trimmed.equals("0")) {
            return Boolean.FALSE;
        }
        return null;
    }
}
