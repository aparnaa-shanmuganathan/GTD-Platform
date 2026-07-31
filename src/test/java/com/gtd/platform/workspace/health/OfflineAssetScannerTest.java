package com.gtd.platform.workspace.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.gtd.platform.workspace.health.OfflineAssetScanner.Finding;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Hand-authored fixture content proving {@link OfflineAssetScanner#scanContent}'s detection
 * logic in isolation - {@code scanBundledAssets} itself (the real classpath scan) is proven
 * against the actual running app by {@code HealthWorkspaceIntegrationTest}, not here.
 */
class OfflineAssetScannerTest {

    @Test
    void flagsExternalScriptSrc() {
        String html = "<html><head><script src=\"https://cdn.example.com/lib.js\"></script></head></html>";
        List<Finding> findings = OfflineAssetScanner.scanContent("fixture.html", html);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).url()).isEqualTo("https://cdn.example.com/lib.js");
        assertThat(findings.get(0).lineNumber()).isEqualTo(1);
    }

    @Test
    void flagsExternalStylesheetLink() {
        String html = "<link rel=\"stylesheet\" href=\"http://fonts.example.com/style.css\">";
        List<Finding> findings = OfflineAssetScanner.scanContent("fixture.html", html);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).url()).isEqualTo("http://fonts.example.com/style.css");
    }

    @Test
    void flagsExternalCssUrlFunction() {
        String css = ".icon { background-image: url(https://cdn.example.com/icon.png); }";
        List<Finding> findings = OfflineAssetScanner.scanContent("fixture.css", css);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).url()).isEqualTo("https://cdn.example.com/icon.png");
    }

    @Test
    void flagsExternalCssUrlFunctionWithQuotes() {
        String css = ".icon { background-image: url(\"https://cdn.example.com/icon.png\"); }";
        List<Finding> findings = OfflineAssetScanner.scanContent("fixture.css", css);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).url()).isEqualTo("https://cdn.example.com/icon.png");
    }

    @Test
    void ignoresLocalScriptAndLinkReferences() {
        String html = "<script th:src=\"@{/js/echarts.min.js}\"></script>"
                + "<link rel=\"stylesheet\" href=\"/css/gtd-theme.css\">";
        assertThat(OfflineAssetScanner.scanContent("fixture.html", html)).isEmpty();
    }

    @Test
    void ignoresXmlNamespaceDeclarations() {
        // xmlns:th="http://www.thymeleaf.org" is an identifier, never fetched - and it isn't a
        // src=/href= attribute on a <script>/<link> tag to begin with.
        String html = "<html xmlns:th=\"http://www.thymeleaf.org\"><body></body></html>";
        assertThat(OfflineAssetScanner.scanContent("fixture.html", html)).isEmpty();
    }

    @Test
    void ignoresPlainAnchorLinksToExternalSites() {
        // A real <a href> is user-initiated navigation, not this platform making a request -
        // the GTD attribution citation mentions an external URL as plain text for this reason.
        String html = "<a href=\"https://www.start.umd.edu/gtd\">GTD homepage</a>";
        assertThat(OfflineAssetScanner.scanContent("fixture.html", html)).isEmpty();
    }

    @Test
    void ignoresPlainTextMentioningAUrl() {
        String html = "<p>Retrieved from https://www.start.umd.edu/gtd</p>";
        assertThat(OfflineAssetScanner.scanContent("fixture.html", html)).isEmpty();
    }

    @Test
    void reportsLineNumberOfEachFinding() {
        String html = "<html>\n<head>\n<script src=\"https://evil.example.com/x.js\"></script>\n</head>\n</html>";
        List<Finding> findings = OfflineAssetScanner.scanContent("fixture.html", html);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).lineNumber()).isEqualTo(3);
    }

    @Test
    void findsMultipleViolationsAcrossLines() {
        String html = "<script src=\"https://a.example.com/1.js\"></script>\n"
                + "<link rel=\"icon\" href=\"https://b.example.com/favicon.ico\">";
        List<Finding> findings = OfflineAssetScanner.scanContent("fixture.html", html);
        assertThat(findings).hasSize(2);
    }
}
