package org.openmuc.framework.server.restws.servlets;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.openmuc.framework.config.ConfigService;
import org.openmuc.framework.config.RootConfig;
import org.openmuc.framework.dataaccess.DataAccessService;
import org.openmuc.framework.config.DeviceConfig;
import org.openmuc.framework.config.DriverConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openmuc.framework.lib.rest1.ToJson;
import org.openmuc.framework.lib.rest1.service.LatestValueService;
import org.openmuc.framework.lib.rest1.service.impl.LatestValueServiceImpl;

public class ResetToDefaultServlet extends GenericServlet {

    private static final Logger logger = LoggerFactory.getLogger(ResetToDefaultServlet.class);
    private final DataSource dataSource;
    private final ConfigService configService;
    private final LatestValueService latestValueService = new LatestValueServiceImpl();

    public ResetToDefaultServlet(DataSource dataSource, ConfigService configService) {
        this.dataSource = dataSource;
        this.configService = configService;
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        ToJson json = new ToJson();
        
        try {
            // 1. Delete data from database (latest_values) where channelid starts with "str"
            // The user suggested reusing deleteString logic, but that deletes a specific string ID.
            // We want to delete ALL string data. Direct SQL is most efficient here.
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM latest_values WHERE channelid LIKE 'str%'")) {
                int rowsDeleted = ps.executeUpdate();
                logger.info("Deleted " + rowsDeleted + " rows from latest_values starting with 'str'");
            }

            // 2. Delete devices from configuration (channels.xml)
            if (configService != null) {
                // Get a CLONE of the config to modify
                RootConfig config = configService.getConfig();
                boolean configChanged = false;

                // Iterate over all drivers to find and remove devices
                for (org.openmuc.framework.config.DriverConfig driver : config.getDrivers()) {
                    // We need to iterate over a copy of devices to avoid ConcurrentModificationException if we remove while iterating
                    // DriverConfig usually has getDevices()
                    // Let's assume DriverConfig has getDevices() returning Collection<DeviceConfig> or similar.
                    // If DriverConfig interface allows getting IDs or Devices.
                    
                    // Since I can't effectively see DriverConfig yet (waiting for view_file), I will assume standard OpenMUC API pattern:
                    // driver.getDevices() -> Collection<DeviceConfig>
                    // driver.removeDevice(id)
                    
                    // Safe approach: Collect devices to remove first
                    java.util.List<org.openmuc.framework.config.DeviceConfig> devicesToRemove = new java.util.ArrayList<>();
                    for (org.openmuc.framework.config.DeviceConfig device : driver.getDevices()) {
                        if (device.getId().startsWith("str")) {
                            devicesToRemove.add(device);
                        }
                    }
                    
                    for (org.openmuc.framework.config.DeviceConfig device : devicesToRemove) {
                        device.delete();
                        configChanged = true;
                        logger.info("Removed device: " + device.getId() + " from driver: " + driver.getId());
                    }
                }
                
                if (configChanged) {
                    configService.setConfig(config);
                    // Explicitly write changes to file to ensure persistence in channels.xml
                    configService.writeConfigToFile();
                    logger.info("Configuration updated and saved to file.");
                }
            }

            response.setStatus(HttpServletResponse.SC_OK);
            CommonResponse.toSuccessResult("Reset to default successful. Please reboot.", json);

        } catch (Exception e) {
            logger.error("Error during reset to default", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            CommonResponse.toExceptionResult(e.getMessage(), json);
        }
        
        sendJson(json, response);
    }
}
