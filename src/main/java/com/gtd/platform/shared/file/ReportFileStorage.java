package com.gtd.platform.shared.file;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Shared Infrastructure — File Management (CLAUDE.md section 4) for the
 * REPORT service's generated PDF/CSV files. Distinct from how the IMPORT
 * service handles its own uploaded CSV (a short-lived staging file, deleted
 * once the background job finishes - see {@code ImportController}): a
 * {@code generated_report} row is immutable and permanent (V8 migration), so
 * the file it points at via {@code file_path} must be too. Files live under
 * a configurable directory ({@code gtd.reports.directory}, under the OS
 * user's home directory by default - never a temp directory - overridden to
 * a disposable temp location for the test profile).
 */
@Component
public class ReportFileStorage {

    private final Path baseDirectory;

    public ReportFileStorage(@Value("${gtd.reports.directory}") String reportsDirectory) {
        this.baseDirectory = Path.of(reportsDirectory);
        try {
            Files.createDirectories(baseDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create reports directory: " + baseDirectory, e);
        }
    }

    /**
     * @param content       the rendered report bytes (PDF or CSV)
     * @param fileExtension without the leading dot, e.g. {@code "pdf"} or {@code "csv"}
     * @return the absolute path the content was written to, suitable for {@code generated_report.file_path}
     */
    public String store(byte[] content, String fileExtension) {
        String fileName = UUID.randomUUID() + "." + fileExtension;
        Path target = baseDirectory.resolve(fileName);
        try {
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write report file: " + target, e);
        }
        return target.toAbsolutePath().toString();
    }

    /** @param filePath a path previously returned by {@link #store} */
    public byte[] read(String filePath) {
        try {
            return Files.readAllBytes(Path.of(filePath));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read report file: " + filePath, e);
        }
    }
}
