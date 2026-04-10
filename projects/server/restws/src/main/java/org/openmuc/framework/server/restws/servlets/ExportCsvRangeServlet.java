package org.openmuc.framework.server.restws.servlets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ExportCsvRangeServlet extends GenericServlet {

    private static final Logger logger = LoggerFactory.getLogger(ExportCsvRangeServlet.class);
    private static final String CSV_LOG_DIR = "./log/csv";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("ddMMyy");

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String stringId = request.getParameter("stringId");
        String startStr = request.getParameter("start");
        String endStr = request.getParameter("end");

        if (stringId == null || startStr == null || endStr == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing parameters: stringId, start, end are required.");
            return;
        }

        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(startStr, DATE_FORMATTER);
            endDate = LocalDate.parse(endStr, DATE_FORMATTER);
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid date format. Use ddMMyy.");
            return;
        }

        if (endDate.isBefore(startDate)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "End date must be after or equal to start date.");
            return;
        }

        List<File> filesToExport = new ArrayList<>();
        List<String> missingDates = new ArrayList<>();
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            String dateFormatted = current.format(DATE_FORMATTER);
            String filename = dateFormatted + "_str" + stringId + ".csv";
            File file = new File(CSV_LOG_DIR, filename);
            if (file.exists()) {
                filesToExport.add(file);
            } else {
                missingDates.add(dateFormatted);
            }
            current = current.plusDays(1);
        }

        if (filesToExport.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("text/plain");
            response.getWriter().write("No CSV files found for the given range and string ID.");
            return;
        }

        if (filesToExport.size() == 1 && missingDates.isEmpty()) {
            // Single file export - only if nothing is missing
            File file = filesToExport.get(0);
            response.setContentType("text/csv");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
            try (InputStream is = new FileInputStream(file);
                 OutputStream os = response.getOutputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    os.write(buffer, 0, bytesRead);
                }
            }
        } else {
            // Multiple files or partial missing - ZIP export
            String zipName = "export_str" + stringId + "_" + startStr + "_to_" + endStr + ".zip";
            response.setContentType("application/zip");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + zipName + "\"");
            try (ZipOutputStream zos = new ZipOutputStream(response.getOutputStream())) {
                // Add found files
                for (File file : filesToExport) {
                    ZipEntry zipEntry = new ZipEntry(file.getName());
                    zos.putNextEntry(zipEntry);
                    Files.copy(file.toPath(), zos);
                    zos.closeEntry();
                }
                
                // Add missing files notification if any
                if (!missingDates.isEmpty()) {
                    ZipEntry missingEntry = new ZipEntry("missing_files.txt");
                    zos.putNextEntry(missingEntry);
                    OutputStreamWriter writer = new OutputStreamWriter(zos);
                    writer.write("The following dates for string " + stringId + " were missing and not included in this export:\n");
                    for (String date : missingDates) {
                        writer.write("- " + date + "\n");
                    }
                    writer.flush();
                    zos.closeEntry();
                }
            } catch (Exception e) {
                logger.error("Error creating ZIP archive", e);
                if (!response.isCommitted()) {
                    response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error creating ZIP archive: " + e.getMessage());
                }
            }
        }
    }
}
