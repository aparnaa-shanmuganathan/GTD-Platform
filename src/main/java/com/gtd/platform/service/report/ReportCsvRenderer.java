package com.gtd.platform.service.report;

import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.InsightsResponse.InsightItem;
import com.gtd.platform.shared.api.KpiResponse;
import com.gtd.platform.shared.api.RankingResponse.RankingEntry;
import com.gtd.platform.shared.reporting.GtdAttribution;
import java.io.IOException;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

/**
 * Renders a {@link ReportContent} snapshot to CSV bytes via commons-csv
 * (already a dependency, Phase 3's IMPORT service) - the same content the
 * PDF renderer produces, in the "CSV export option" the Phase 6 prompt asks
 * for, not a raw {@code fact_incident} row dump. A single CSV has no native
 * way to express multiple differently-shaped tables, so this writes one
 * section per KPI/ranking/trend/breakdown/insights group, each with its own
 * header row and separated by a blank line - a reader opening this in a
 * spreadsheet sees clearly labeled blocks rather than one confusing wide
 * table.
 *
 * <p>Insight text has its HTML emphasis tags ({@code <b>...</b>}, added by
 * {@code InsightService} for the web UI) stripped to plain text here - a CSV
 * cell showing literal {@code <b>} markup would be a clear regression
 * compared to the PDF, which renders those tags as real formatting.
 */
@Component
public class ReportCsvRenderer {

    private static final DateTimeFormatter GENERATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    public byte[] render(String reportName, String datasetVersionLabel, String filterSummary, ReportContent content) {
        StringWriter writer = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {
            printer.printRecord("Report", reportName);
            printer.printRecord("Generated At", GENERATED_AT_FORMAT.format(Instant.now()) + " UTC");
            printer.printRecord("Dataset Version", datasetVersionLabel);
            printer.printRecord("Filters", filterSummary);
            printer.println();

            printer.printRecord("Key Performance Indicators");
            printer.printRecord("Label", "SAV ID", "Value", "Unit", "Trend");
            for (KpiResponse kpi : content.kpis()) {
                printer.printRecord(kpi.label(), kpi.savId(), plain(kpi.value()), orEmpty(kpi.unit()),
                        kpi.trend() != null ? kpi.trend().deltaLabel() : "");
            }
            printer.println();

            printer.printRecord("Top Countries by Incident Count");
            printer.printRecord("Rank", "Country", "Incidents", "Share");
            for (RankingEntry entry : content.countryRanking().entries()) {
                printer.printRecord(entry.rank(), entry.label(), plain(entry.value()), sharePercent(entry.shareOfTotal()));
            }
            printer.println();

            printChartSection(printer, "Incidents by Year", "Year", content.trendChart());
            printChartSection(printer, "Attack Type Breakdown", "Attack Type", content.attackChart());
            printChartSection(printer, "Weapon Type Breakdown", "Weapon Type", content.weaponChart());
            printChartSection(printer, "Target Type Breakdown", "Target Type", content.targetChart());

            printer.printRecord("Insights");
            printer.printRecord("Severity", "Text", "Meta");
            for (InsightItem item : content.insights().items()) {
                printer.printRecord(item.severity().name(), stripHtml(item.text()), orEmpty(item.meta()));
            }
            printer.println();

            printer.printRecord("Citation", GtdAttribution.CITATION);
            printer.printRecord("Copyright", GtdAttribution.COPYRIGHT);
        } catch (IOException e) {
            throw new ReportRenderingException("Failed to render report CSV", e);
        }
        return writer.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void printChartSection(CSVPrinter printer, String title, String categoryLabel, ChartResponse chart) throws IOException {
        printer.printRecord(title);
        printer.printRecord(categoryLabel, "Incidents");
        List<BigDecimal> values = chart.series().isEmpty() ? List.of() : chart.series().get(0).data();
        for (int i = 0; i < chart.categories().size(); i++) {
            BigDecimal value = i < values.size() ? values.get(i) : BigDecimal.ZERO;
            printer.printRecord(chart.categories().get(i), plain(value));
        }
        printer.println();
    }

    private String sharePercent(BigDecimal shareOfTotal) {
        if (shareOfTotal == null) {
            return "";
        }
        return plain(shareOfTotal.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)) + "%";
    }

    private String plain(BigDecimal value) {
        return value.toPlainString();
    }

    private String orEmpty(String value) {
        return value != null ? value : "";
    }

    private String stripHtml(String text) {
        return text.replaceAll("<[^>]+>", "");
    }
}
