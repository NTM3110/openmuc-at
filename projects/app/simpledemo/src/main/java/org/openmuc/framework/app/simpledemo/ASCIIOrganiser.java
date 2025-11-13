package org.openmuc.framework.app.simpledemo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.openmuc.framework.data.DoubleValue;
import org.openmuc.framework.data.Flag;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openmuc.framework.dataaccess.DataAccessService;
import org.openmuc.framework.data.DoubleValue;
import org.openmuc.framework.data.Record;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ASCIIOrganiser {

    @Reference
    private DataAccessService dataAccessService;
    private static final Logger logger = LoggerFactory.getLogger(ASCIIOrganiser.class);

    // ---- CONFIG ----
    private static final String ASCII_DIR    = "/var/lib/openmuc/data/ascii";
    private static final String DELIMITER    = ",";       // ";" or "\t" if needed
    private static final int    TIME_COLUMNS = 1;         // how many leading columns to skip (time)
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Activate
    void activate() {
        try {
            Path dir = Paths.get(ASCII_DIR);
            if (!Files.isDirectory(dir)) {
                logger.info("[AsciiRestore] ASCII directory does not exist: {}", dir);
                return;
            }

            // 1) Scan all files and group by date prefix
            Map<LocalDate, List<Path>> byDate = groupFilesByDate(dir);

            if (byDate.isEmpty()) {
                logger.info("[AsciiRestore] No ASCII files found.");
                return;
            }

            LocalDate today = LocalDate.now();
            LocalDate yesterday = today.minusDays(1);

            Path todaysFile    = newestForDate(byDate, today);
            Path yesterdaysFile = newestForDate(byDate, yesterday);

            // 2) Choose restore source: today if exists else yesterday
            Path restoreSource = (todaysFile != null) ? todaysFile : yesterdaysFile;

            if (restoreSource != null) {
                logger.info("[AsciiRestore] Restoring values from: {}",restoreSource.getFileName());
                restoreLatestValues(restoreSource);
            } else {
                logger.info("[AsciiRestore] No file for today or yesterday to restore from.");
            }

            // 3) Cleanup according to your rule
            if (todaysFile != null) {
                // We have opened OpenMUC today -> keep ONLY today's files, delete all earlier
                logger.info("[AsciiRestore] Today file exists. Deleting all files older than today.");
                cleanupOldFiles(byDate, keepDate(today));
            } else if (yesterdaysFile != null) {
                // First time launching today, but no today file yet -> keep only yesterday, drop older
                logger.info("[AsciiRestore] No today file. Keeping only yesterday's files.");
                cleanupOldFiles(byDate, keepDate(yesterday));
            } else {
                // No today or yesterday; keep newest date only
                LocalDate newest = byDate.keySet().stream().max(LocalDate::compareTo).get();
                logger.info("[AsciiRestore] Keeping only newest date: {}" ,newest);
                cleanupOldFiles(byDate, keepDate(newest));
            }

        } catch (Exception e) {
            logger.error("[AsciiRestore] Error during restore/cleanup: " + e);
        }
    }

    // ---- RESTORE LATEST VALUES (NO TIME, VALUE ONLY) ----

    private void restoreLatestValues(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return;

        // Find header (first non-empty, non-comment line)
        String headerLine = null;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            headerLine = line;
            break;
        }

        // Find last data line (last non-empty, non-comment line)
        String lastLine = null;
        for (int i = lines.size() - 1; i >= 0; i--) {
            String t = lines.get(i).trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            lastLine = lines.get(i);
            break;
        }

        if (headerLine == null || lastLine == null) {
            logger.info("[AsciiRestore] No header or data in " + file);
            return;
        }

        String[] headers = headerLine.split(DELIMITER, -1);
        String[] values  = lastLine.split(DELIMITER, -1);

        int restored = 0;

        for (int i = TIME_COLUMNS; i < headers.length && i < values.length; i++) {
            String channelId = headers[i].trim();
            String raw       = values[i].trim();

            if (channelId.isEmpty() || raw.isEmpty() || raw.equalsIgnoreCase("NaN")) continue;

            try {
                double v = Double.parseDouble(raw);
                long now = System.currentTimeMillis();
                Record r = new Record(new DoubleValue(v),now, Flag.VALID);  // no time, no flag
                dataAccessService.getChannel(channelId).setLatestRecord(r);
                restored++;
                System.out.printf("[AsciiRestore] %s = %s%n", channelId, raw);
            } catch (NumberFormatException ignore) {
                // not a number -> ignore
            } catch (Exception ex) {
                System.err.printf("[AsciiRestore] setLatestRecord failed for %s: %s%n",
                        channelId, ex.toString());
            }
        }

        logger.info("[AsciiRestore] Restored " + restored + " channel values from last line.");
    }

    // ---- FILE GROUPING / CLEANUP ----

    private Map<LocalDate, List<Path>> groupFilesByDate(Path dir) throws IOException {
        Map<LocalDate, List<Path>> map = new HashMap<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                LocalDate d = extractDatePrefix(p.getFileName().toString());
                if (d != null) {
                    map.computeIfAbsent(d, k -> new ArrayList<>()).add(p);
                }
            });
        }
        return map;
    }

    private LocalDate extractDatePrefix(String filename) {
        if (filename.length() < 8) return null;
        String prefix = filename.substring(0, 8); // yyyyMMdd
        try {
            return LocalDate.parse(prefix, DATE_FMT);
        } catch (Exception e) {
            return null;
        }
    }

    private Path newestForDate(Map<LocalDate, List<Path>> byDate, LocalDate date) {
        List<Path> paths = byDate.get(date);
        if (paths == null || paths.isEmpty()) return null;
        return paths.stream()
                .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                .orElse(null);
    }

    private Set<LocalDate> keepDate(LocalDate d) {
        return Collections.singleton(d);
    }

    private void cleanupOldFiles(Map<LocalDate, List<Path>> byDate, Set<LocalDate> keepDates) {
        byDate.forEach((date, paths) -> {
            if (keepDates.contains(date)) {
                // keep these
                return;
            }
            for (Path p : paths) {
                try {
                    Files.deleteIfExists(p);
                    logger.info("[AsciiRestore] Deleted old ASCII file: " + p.getFileName());
                } catch (IOException e) {
                    logger.error("[AsciiRestore] Could not delete " + p + ": " + e);
                }
            }
        });
    }
    
}
