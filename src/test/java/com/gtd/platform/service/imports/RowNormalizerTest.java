package com.gtd.platform.service.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit tests of {@link RowNormalizer}'s validation rules (CLAUDE.md
 * section 12) - no Spring context, no database. {@link DimensionResolver}
 * is mocked so these tests exercise exactly the parsing/rejection logic
 * documented on {@link RowNormalizer}, independent of real dimension data.
 */
@ExtendWith(MockitoExtension.class)
class RowNormalizerTest {

    private static final DimensionResolver.CountryResolution VALID_COUNTRY =
            new DimensionResolver.CountryResolution(10L, 20L);

    @Mock
    private DimensionResolver dimensionResolver;

    private RowNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new RowNormalizer(dimensionResolver);
        lenient().when(dimensionResolver.resolveCountry(anyString(), anyString())).thenReturn(VALID_COUNTRY);
        lenient().when(dimensionResolver.resolveAttackType(any())).thenReturn(30L);
        lenient().when(dimensionResolver.resolveWeaponType(any())).thenReturn(40L);
        lenient().when(dimensionResolver.resolveTargetType(any())).thenReturn(50L);
        lenient().when(dimensionResolver.resolveOrganization(any())).thenReturn(60L);
    }

    @Test
    void validRowIsAccepted() {
        NormalizationResult result = normalizer.normalize(row("197001010001", "1970", "1", "15",
                "United States", "North America", "3", "5"), 99L);

        assertThat(result).isInstanceOf(NormalizationResult.Accepted.class);
        FactIncidentRecord record = ((NormalizationResult.Accepted) result).factIncident();
        assertThat(record.eventId()).isEqualTo("197001010001");
        assertThat(record.datasetVersionId()).isEqualTo(99L);
        assertThat(record.countryId()).isEqualTo(10L);
        assertThat(record.regionId()).isEqualTo(20L);
        assertThat(record.eventYear()).isEqualTo(1970);
        assertThat(record.eventMonth()).isEqualTo(1);
        assertThat(record.eventDay()).isEqualTo(15);
        assertThat(record.nkill()).isEqualTo(3);
        assertThat(record.nwound()).isEqualTo(5);
    }

    @Test
    void blankEventIdIsRejected() {
        NormalizationResult result = normalizer.normalize(row("", "1970", "1", "1", "US", "NA", "1", "1"), 1L);
        assertRejected(result, RejectionReason.MISSING_OR_INVALID_EVENTID);
    }

    @Test
    void eventIdLongerThan12CharsIsRejected() {
        NormalizationResult result = normalizer.normalize(
                row("1234567890123", "1970", "1", "1", "US", "NA", "1", "1"), 1L);
        assertRejected(result, RejectionReason.MISSING_OR_INVALID_EVENTID);
    }

    @Test
    void blankYearIsRejected() {
        NormalizationResult result = normalizer.normalize(row("E1", "", "1", "1", "US", "NA", "1", "1"), 1L);
        assertRejected(result, RejectionReason.MISSING_OR_INVALID_YEAR);
    }

    @Test
    void nonNumericYearIsRejected() {
        NormalizationResult result = normalizer.normalize(row("E1", "abcd", "1", "1", "US", "NA", "1", "1"), 1L);
        assertRejected(result, RejectionReason.MISSING_OR_INVALID_YEAR);
    }

    @Test
    void zeroMonthAndDayMeanUnknownPerGtdConvention() {
        NormalizationResult result = normalizer.normalize(row("E1", "1970", "0", "0", "US", "NA", "1", "1"), 1L);

        assertThat(result).isInstanceOf(NormalizationResult.Accepted.class);
        FactIncidentRecord record = ((NormalizationResult.Accepted) result).factIncident();
        assertThat(record.eventMonth()).isNull();
        assertThat(record.eventDay()).isNull();
    }

    @Test
    void outOfRangeDayDoesNotRejectTheRow() {
        NormalizationResult result = normalizer.normalize(row("E1", "1970", "1", "99", "US", "NA", "1", "1"), 1L);

        assertThat(result).isInstanceOf(NormalizationResult.Accepted.class);
        assertThat(((NormalizationResult.Accepted) result).factIncident().eventDay()).isNull();
    }

    @Test
    void nonNumericCasualtyCountIsRejected() {
        NormalizationResult result = normalizer.normalize(row("E1", "1970", "1", "1", "US", "NA", "abc", "1"), 1L);
        assertRejected(result, RejectionReason.INVALID_CASUALTY_COUNT);
    }

    @Test
    void negativeCasualtyCountIsRejected() {
        NormalizationResult result = normalizer.normalize(row("E1", "1970", "1", "1", "US", "NA", "-3", "1"), 1L);
        assertRejected(result, RejectionReason.INVALID_CASUALTY_COUNT);
    }

    @Test
    void blankCasualtyCountBecomesNullNeverZero() {
        NormalizationResult result = normalizer.normalize(row("E1", "1970", "1", "1", "US", "NA", "", ""), 1L);

        assertThat(result).isInstanceOf(NormalizationResult.Accepted.class);
        FactIncidentRecord record = ((NormalizationResult.Accepted) result).factIncident();
        assertThat(record.nkill()).isNull();
        assertThat(record.nwound()).isNull();
    }

    @Test
    void pandasStyleFloatFormattedCasualtyCountIsAccepted() {
        NormalizationResult result = normalizer.normalize(row("E1", "1970", "1", "1", "US", "NA", "5.0", "0.0"), 1L);

        assertThat(result).isInstanceOf(NormalizationResult.Accepted.class);
        FactIncidentRecord record = ((NormalizationResult.Accepted) result).factIncident();
        assertThat(record.nkill()).isEqualTo(5);
        assertThat(record.nwound()).isEqualTo(0);
    }

    @Test
    void literalNaNCasualtyCountBecomesNull() {
        NormalizationResult result = normalizer.normalize(row("E1", "1970", "1", "1", "US", "NA", "NaN", "1"), 1L);

        assertThat(result).isInstanceOf(NormalizationResult.Accepted.class);
        assertThat(((NormalizationResult.Accepted) result).factIncident().nkill()).isNull();
    }

    @Test
    void unresolvableCountryIsRejected() {
        when(dimensionResolver.resolveCountry(anyString(), anyString())).thenReturn(null);

        NormalizationResult result = normalizer.normalize(row("E1", "1970", "1", "1", "Nowhereland", "", "1", "1"), 1L);
        assertRejected(result, RejectionReason.UNRESOLVABLE_COUNTRY_OR_REGION);
    }

    @Test
    void booleanFlagsParseOneAndZero() {
        StagingRow input = new StagingRow(1L, "E1", "1970", "1", "1", "US", "NA",
                null, null, null, null, null, null, null, null, null, null, "1", "1",
                "1", "0", "1", "0", null);

        NormalizationResult result = normalizer.normalize(input, 1L);

        assertThat(result).isInstanceOf(NormalizationResult.Accepted.class);
        FactIncidentRecord record = ((NormalizationResult.Accepted) result).factIncident();
        assertThat(record.success()).isTrue();
        assertThat(record.suicide()).isFalse();
        assertThat(record.individualPerpetrator()).isTrue();
        assertThat(record.propertyDamage()).isFalse();
    }

    private void assertRejected(NormalizationResult result, RejectionReason expectedReason) {
        assertThat(result).isInstanceOf(NormalizationResult.Rejected.class);
        assertThat(((NormalizationResult.Rejected) result).reason()).isEqualTo(expectedReason);
    }

    private StagingRow row(String eventId, String iyear, String imonth, String iday,
            String countryTxt, String regionTxt, String nkill, String nwound) {
        return new StagingRow(1L, eventId, iyear, imonth, iday, countryTxt, regionTxt,
                "Some Province", "Some City", "10.0", "20.0", "Summary text", "Some target",
                "Bombing/Explosion", "Explosives", "Business", "Some Org", nkill, nwound,
                "1", "0", "0", "0", "Some motive");
    }
}
