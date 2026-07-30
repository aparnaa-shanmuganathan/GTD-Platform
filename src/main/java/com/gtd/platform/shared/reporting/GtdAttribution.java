package com.gtd.platform.shared.reporting;

/**
 * The GTD attribution/citation text (CLAUDE.md section 13, open item 1 - a
 * EULA obligation, not a stylistic choice). Lives in {@code shared.reporting}
 * even though it appears on every workspace page, not just the Report
 * Workspace: it is the license notice for the underlying data every
 * workspace displays, and this package already owns {@code generated_report}
 * - the one place a PDF/CSV export of that data is produced and must carry
 * the same notice.
 *
 * <p>The single source of truth for this text: the shared footer fragment
 * ({@code templates/fragments/footer.html}) reads it via Thymeleaf's
 * {@code T(...)} class-reference expression, and {@link
 * com.gtd.platform.service.report.ReportPdfRenderer}/{@code ReportCsvRenderer}
 * read it directly in Java - one constant, never copy-pasted into either
 * place, so the two can never drift out of sync.
 */
public final class GtdAttribution {

    private GtdAttribution() {
    }

    public static final String CITATION =
            "National Consortium for the Study of Terrorism and Responses to Terrorism (START), "
                    + "University of Maryland. (2018). The Global Terrorism Database (GTD) [Data file]. "
                    + "Retrieved from https://www.start.umd.edu/gtd";

    public static final String COPYRIGHT =
            "GTD data © National Consortium for the Study of Terrorism and Responses to Terrorism (START), "
                    + "University of Maryland. All rights reserved.";
}
