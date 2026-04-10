package org.openmuc.framework.server.restws.servlets;

import org.openmuc.framework.lib.rest1.service.impl.CsvExportUtil;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class ExportLatestValuesCsvServlet extends GenericServlet{
    private final DataSource dataSource;
    private final org.openmuc.framework.dataaccess.DataAccessService dataAccessService;
    private final org.openmuc.framework.config.ConfigService configService;

    public ExportLatestValuesCsvServlet(DataSource dataSource, org.openmuc.framework.dataaccess.DataAccessService dataAccessService, org.openmuc.framework.config.ConfigService configService){
        this.dataSource = dataSource;
        this.dataAccessService = dataAccessService;
        this.configService = configService;
    }
    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String fileName = request.getParameter("fileName");
        CsvExportUtil.exportLatestValuesCsv(response, dataSource, fileName);
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        Part filePart = request.getPart("file");
        if (filePart == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "CSV file missing");
            return;
        }
        try (InputStream is = filePart.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            CsvExportUtil.importCsv(reader, dataSource, dataAccessService, configService);

            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write("{\"status\":\"ok\"}");

        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }

//        response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "POST method is not supported for this endpoint.");
    }
}
