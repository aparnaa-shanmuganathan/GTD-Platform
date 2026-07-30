package com.gtd.platform.service.report;

import com.gtd.platform.shared.api.ChartResponse;
import com.gtd.platform.shared.api.KpiResponse;
import com.gtd.platform.shared.api.RankingResponse.RankingEntry;
import com.gtd.platform.shared.reporting.GtdAttribution;
import com.lowagie.text.DocumentException;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.dialect.SpringStandardDialect;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import org.xhtmlrenderer.pdf.ITextRenderer;

/**
 * Renders a {@link ReportContent} snapshot to PDF bytes via Flying Saucer
 * (CLAUDE.md section 2): a dedicated Thymeleaf template
 * ({@code templates/report/content.xhtml}) is processed to a valid XHTML
 * string, then handed to Flying Saucer's {@link ITextRenderer} (OpenPDF-backed
 * as of {@code flying-saucer-pdf:9.4.0} - see CLAUDE.md section 2).
 *
 * <p>Uses its own plain {@link TemplateEngine}, entirely separate from the
 * Spring-MVC-wired one that serves {@code executive.html}/{@code country.html}:
 * those are lenient HTML-mode templates rendered through the web view
 * resolver, and this one is configured with {@code TemplateMode.XML}
 * (Thymeleaf 3.x folded XHTML support into its XML mode - well-formed,
 * case-sensitive markup, which Flying Saucer's XML/CSS engine requires) -
 * reusing or reconfiguring the web engine's template mode would break every
 * existing HTML view. {@link SpringStandardDialect}
 * is set explicitly because this project has no {@code ognl} dependency on
 * the classpath (only ever needed Spring's SpEL-based dialect via
 * {@code thymeleaf-spring6} until now) - the plain {@code TemplateEngine}'s
 * own default dialect is OGNL-based and would fail at runtime without it.
 *
 * <p>The template's data is passed as {@code Map}s, not the {@code KpiResponse}/
 * {@code ChartResponse}/{@code RankingResponse} records directly: Java records'
 * accessor methods (e.g. {@code label()}, not {@code getLabel()}) are not
 * guaranteed compatible with every expression-language property-path
 * resolution strategy, whereas a {@code Map}'s {@code ${row.key}} access is
 * unambiguous and well-supported by every Thymeleaf configuration - the
 * translation happens once, here, rather than risking it per template.
 *
 * <p>No custom webfonts: only woff2 files are vendored (Phase 2), and Flying
 * Saucer's font resolver needs TrueType/OpenType for embedding, so the
 * template uses PDF built-in fonts (Helvetica) instead. Charts are rendered
 * as CSS-only proportional bars (a {@code div} with a percentage-scaled
 * {@code width}), not as embedded images: there is no headless-browser or
 * canvas-rendering capability on the server to export the exact ECharts
 * visuals the web UI shows, and introducing a second, JVM-native charting
 * library (e.g. JFreeChart) purely to rasterize equivalent bar charts would
 * be more machinery than a simple CSS box-model bar - which Flying Saucer's
 * layout engine already renders natively - for the same visual information.
 */
@Component
public class ReportPdfRenderer {

    private static final DateTimeFormatter GENERATED_AT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final int MAX_BAR_WIDTH_PX = 100;

    private final TemplateEngine templateEngine;

    public ReportPdfRenderer() {
        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix("templates/report/");
        templateResolver.setSuffix(".xhtml");
        templateResolver.setTemplateMode(TemplateMode.XML);
        templateResolver.setCharacterEncoding("UTF-8");
        templateResolver.setCacheable(true);

        this.templateEngine = new TemplateEngine();
        this.templateEngine.setDialect(new SpringStandardDialect());
        this.templateEngine.setTemplateResolver(templateResolver);
    }

    public byte[] render(String reportName, String datasetVersionLabel, String filterSummary, ReportContent content) {
        Context context = new Context();
        context.setVariable("reportName", reportName);
        context.setVariable("generatedAtDisplay", GENERATED_AT_FORMAT.format(Instant.now()) + " UTC");
        context.setVariable("datasetVersionLabel", datasetVersionLabel);
        context.setVariable("filterSummary", filterSummary);
        context.setVariable("kpiRows", kpiRows(content.kpis()));
        context.setVariable("rankingRows", rankingRows(content.countryRanking().entries()));
        context.setVariable("trendRows", trendRows(content.trendChart()));
        context.setVariable("attackRows", breakdownRows(content.attackChart()));
        context.setVariable("weaponRows", breakdownRows(content.weaponChart()));
        context.setVariable("targetRows", breakdownRows(content.targetChart()));
        context.setVariable("insightRows", insightRows(content.insights().items()));
        context.setVariable("citation", GtdAttribution.CITATION);
        context.setVariable("copyright", GtdAttribution.COPYRIGHT);

        String xhtml = templateEngine.process("content", context);

        try {
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(xhtml);
            renderer.layout();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            renderer.createPDF(out);
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new ReportRenderingException("Failed to render report PDF", e);
        }
    }

    private List<Map<String, Object>> kpiRows(List<KpiResponse> kpis) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KpiResponse kpi : kpis) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", kpi.label());
            row.put("savId", kpi.savId());
            row.put("valueDisplay", format(kpi.value()) + (kpi.unit() != null ? kpi.unit() : ""));
            row.put("deltaLabel", kpi.trend() != null ? kpi.trend().deltaLabel() : null);
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> rankingRows(List<RankingEntry> entries) {
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal max = entries.stream().map(RankingEntry::value).reduce(BigDecimal.ZERO, BigDecimal::max);
        for (RankingEntry entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", entry.rank());
            row.put("label", entry.label());
            row.put("value", format(entry.value()));
            row.put("sharePercent", entry.shareOfTotal() != null
                    ? format(entry.shareOfTotal().multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)) + "%"
                    : "");
            row.put("barPercent", barWidth(entry.value(), max));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> trendRows(ChartResponse chart) {
        List<BigDecimal> values = chart.series().isEmpty() ? List.of() : chart.series().get(0).data();
        BigDecimal max = values.stream().reduce(BigDecimal.ZERO, BigDecimal::max);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < chart.categories().size(); i++) {
            BigDecimal value = i < values.size() ? values.get(i) : BigDecimal.ZERO;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("year", chart.categories().get(i));
            row.put("value", format(value));
            row.put("barPercent", barWidth(value, max));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> breakdownRows(ChartResponse chart) {
        List<BigDecimal> values = chart.series().isEmpty() ? List.of() : chart.series().get(0).data();
        BigDecimal max = values.stream().reduce(BigDecimal.ZERO, BigDecimal::max);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < chart.categories().size(); i++) {
            BigDecimal value = i < values.size() ? values.get(i) : BigDecimal.ZERO;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", chart.categories().get(i));
            row.put("value", format(value));
            row.put("barPercent", barWidth(value, max));
            rows.add(row);
        }
        return rows;
    }

    private List<Map<String, Object>> insightRows(List<com.gtd.platform.shared.api.InsightsResponse.InsightItem> items) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("severity", item.severity().name());
            row.put("text", item.text());
            row.put("meta", item.meta());
            rows.add(row);
        }
        return rows;
    }

    private int barWidth(BigDecimal value, BigDecimal max) {
        if (max.compareTo(BigDecimal.ZERO) == 0) {
            return 0;
        }
        return value.multiply(BigDecimal.valueOf(MAX_BAR_WIDTH_PX)).divide(max, 0, RoundingMode.HALF_UP).intValue();
    }

    private String format(BigDecimal value) {
        return value.toPlainString();
    }
}
