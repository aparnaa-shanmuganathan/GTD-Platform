package com.gtd.platform.service.imports;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Streams the raw CSV into {@code staging_incident} via batched JDBC inserts
 * (never JPA entity-by-entity - see {@code service.imports}' package-info -
 * required to have any chance of the &le;5 min/155MB/~180k row NFR).
 *
 * <p>{@link #STAGING_COLUMNS} is the exact TEXT column list from
 * {@code V1__staging_and_import_batch.sql}, which CLAUDE.md section 12
 * already flags as a best-effort reconstruction of the real GTD Codebook,
 * not verified against an actual file. Rather than assume it's a perfect
 * match, this loader intersects it against the real CSV's header at import
 * time: columns present in both are staged; columns only in the CSV are
 * dropped (logged once); columns only in {@code staging_incident} are left
 * {@code NULL} for every row. A perfect match stages everything: a mismatch
 * degrades gracefully instead of failing the whole import.
 */
@Component
public class CsvStagingLoader {

    private static final Logger log = LoggerFactory.getLogger(CsvStagingLoader.class);

    private static final int BATCH_SIZE = 2000;

    static final List<String> STAGING_COLUMNS = List.of(
            "eventid", "iyear", "imonth", "iday", "approxdate", "extended", "resolution", "country", "country_txt",
            "region", "region_txt", "provstate", "city", "latitude", "longitude", "specificity", "vicinity",
            "location", "summary", "crit1", "crit2", "crit3", "doubtterr", "alternative", "alternative_txt",
            "multiple", "success", "suicide", "attacktype1", "attacktype1_txt", "attacktype2", "attacktype2_txt",
            "attacktype3", "attacktype3_txt", "targtype1", "targtype1_txt", "targsubtype1", "targsubtype1_txt",
            "corp1", "target1", "natlty1", "natlty1_txt", "targtype2", "targtype2_txt", "targsubtype2",
            "targsubtype2_txt", "corp2", "target2", "natlty2", "natlty2_txt", "targtype3", "targtype3_txt",
            "targsubtype3", "targsubtype3_txt", "corp3", "target3", "natlty3", "natlty3_txt", "gname", "gsubname",
            "gname2", "gsubname2", "gname3", "gsubname3", "motive", "guncertain1", "guncertain2", "guncertain3",
            "individual", "nperps", "nperpcap", "claimed", "claimmode", "claimmode_txt", "claim2", "claimmode2",
            "claimmode2_txt", "claim3", "claimmode3", "claimmode3_txt", "compclaim", "weaptype1", "weaptype1_txt",
            "weapsubtype1", "weapsubtype1_txt", "weaptype2", "weaptype2_txt", "weapsubtype2", "weapsubtype2_txt",
            "weaptype3", "weaptype3_txt", "weapsubtype3", "weapsubtype3_txt", "weaptype4", "weaptype4_txt",
            "weapsubtype4", "weapsubtype4_txt", "weapdetail", "nkill", "nkillus", "nkillter", "nwound", "nwoundus",
            "nwoundte", "property", "propextent", "propextent_txt", "propvalue", "propcomment", "ishostkid",
            "nhostkid", "nhostkidus", "nhours", "ndays", "divert", "kidhijcountry", "ransom", "ransomamt",
            "ransomamtus", "ransompaid", "ransompaidus", "ransomnote", "hostkidoutcome", "hostkidoutcome_txt",
            "nreleased", "addnotes", "scite1", "scite2", "scite3", "dbsource", "int_log", "int_ideo", "int_misc",
            "int_any", "related"
    );

    private final JdbcTemplate jdbcTemplate;

    public CsvStagingLoader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * @param csvPath       a stable local path (already fully written to disk - never the transient multipart temp file)
     * @param importBatchId the owning import_batch id
     * @param progress      invoked with the cumulative row count after each batch, for progress reporting
     */
    public StagingResult stage(Path csvPath, long importBatchId, IntConsumer progress) throws IOException {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreHeaderCase(true)
                .setTrim(true)
                .setIgnoreSurroundingSpaces(true)
                .build();

        try (Reader reader = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8);
                CSVParser parser = format.parse(reader)) {

            List<String> csvHeaders = parser.getHeaderNames();
            Set<String> csvHeadersLower = csvHeaders.stream()
                    .map(h -> h.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());

            List<String> matchedColumns = STAGING_COLUMNS.stream()
                    .filter(csvHeadersLower::contains)
                    .toList();
            List<String> unmatchedStagingColumns = STAGING_COLUMNS.stream()
                    .filter(col -> !csvHeadersLower.contains(col))
                    .toList();
            List<String> unmatchedCsvHeaders = csvHeaders.stream()
                    .filter(h -> !STAGING_COLUMNS.contains(h.toLowerCase(Locale.ROOT)))
                    .toList();

            if (!unmatchedStagingColumns.isEmpty()) {
                log.warn("{} staging_incident columns not present in this CSV's header, will be left NULL: {}",
                        unmatchedStagingColumns.size(), unmatchedStagingColumns);
            }
            if (!unmatchedCsvHeaders.isEmpty()) {
                log.warn("{} CSV columns have no matching staging_incident column, will be dropped: {}",
                        unmatchedCsvHeaders.size(), unmatchedCsvHeaders);
            }
            if (matchedColumns.isEmpty()) {
                throw new IllegalStateException(
                        "None of this CSV's header columns match staging_incident - refusing to stage a file "
                                + "that would produce entirely empty rows. CSV header: " + csvHeaders);
            }

            String sql = buildInsertSql(matchedColumns);

            List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
            int totalStaged = 0;
            for (CSVRecord record : parser) {
                Object[] args = new Object[2 + matchedColumns.size()];
                args[0] = importBatchId;
                args[1] = (int) record.getRecordNumber();
                for (int i = 0; i < matchedColumns.size(); i++) {
                    String value = record.get(matchedColumns.get(i));
                    args[2 + i] = (value == null || value.isBlank()) ? null : value;
                }
                batch.add(args);
                totalStaged++;

                if (batch.size() >= BATCH_SIZE) {
                    jdbcTemplate.batchUpdate(sql, batch);
                    batch.clear();
                    progress.accept(totalStaged);
                }
            }
            if (!batch.isEmpty()) {
                jdbcTemplate.batchUpdate(sql, batch);
                progress.accept(totalStaged);
            }

            return new StagingResult(totalStaged, matchedColumns, unmatchedStagingColumns, unmatchedCsvHeaders);
        }
    }

    private String buildInsertSql(List<String> matchedColumns) {
        StringBuilder columnsSql = new StringBuilder("import_batch_id, source_row_number");
        StringBuilder placeholdersSql = new StringBuilder("?, ?");
        for (String column : matchedColumns) {
            columnsSql.append(", ").append(column);
            placeholdersSql.append(", ?");
        }
        return "INSERT INTO staging_incident (" + columnsSql + ") VALUES (" + placeholdersSql + ")";
    }

    public record StagingResult(
            int totalRowsStaged,
            List<String> matchedColumns,
            List<String> unmatchedStagingColumns,
            List<String> unmatchedCsvHeaders
    ) {
    }
}
