/*
 * Copyright 2011-2024 Fraunhofer ISE
 *
 * This file is part of OpenMUC.
 * For more information visit http://www.openmuc.org
 *
 * OpenMUC is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenMUC is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenMUC. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openmuc.framework.server.restws.servlets;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.openmuc.framework.lib.rest1.ToJson;
import org.openmuc.framework.lib.rest1.FromJson;
import org.openmuc.framework.server.restws.NetworkManager;
import org.openmuc.framework.server.restws.NetworkManager.NetworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Servlet để xử lý REST API requests cho network configuration
 */
public class NetworkResourceServlet extends GenericServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(NetworkResourceServlet.class);

    private static final String CONFIGS = "configs";
    private static final String CONFIG = "config";
    private static final String REQUESTED_REST_PATH_IS_NOT_AVAILABLE = "Requested rest path is not available";

    // Default network configurations
    private static final List<NetworkConfig> DEFAULT_CONFIGS = new ArrayList<>();
    static {
        DEFAULT_CONFIGS.add(new NetworkConfig("eth1", "Eth1", null, null, null, true));
        DEFAULT_CONFIGS.add(new NetworkConfig("eth2", "Eth2", null, null, null, true));
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType(APPLICATION_JSON);
        String[] pathAndQueryString = checkIfItIsACorrectRest(request, response, logger);

        if (pathAndQueryString == null) {
            return;
        }

        String pathInfo = pathAndQueryString[0];
        String[] pathInfoArray = ServletLib.getPathInfoArray(pathInfo);

        if ("/configs".equals(pathInfo)) {
            doGetAllNetworkConfigs(response);
        } else if (pathInfoArray.length == 2 && CONFIG.equals(pathInfoArray[1])) {
            String configId = pathInfoArray[0].replace("/", "");
            doGetNetworkConfig(configId, response);
        } else {
            ServletLib.sendHTTPErrorAndLogDebug(response, HttpServletResponse.SC_NOT_FOUND, logger,
                    REQUESTED_REST_PATH_IS_NOT_AVAILABLE);
        }
    }

    @Override
    public void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType(APPLICATION_JSON);
        String[] pathAndQueryString = checkIfItIsACorrectRest(request, response, logger);

        if (pathAndQueryString == null) {
            return;
        }

        String pathInfo = pathAndQueryString[0];
        String[] pathInfoArray = ServletLib.getPathInfoArray(pathInfo);

        FromJson json = ServletLib.getFromJson(request, logger, response);
        if (json == null) {
            return;
        }

        if (pathInfoArray.length == 2 && CONFIG.equals(pathInfoArray[1])) {
            String configId = pathInfoArray[0].replace("/", "");
            doUpdateNetworkConfig(configId, json, response);
        } else {
            ServletLib.sendHTTPErrorAndLogDebug(response, HttpServletResponse.SC_NOT_FOUND, logger,
                    REQUESTED_REST_PATH_IS_NOT_AVAILABLE);
        }
    }

    /**
     * Get all network configurations
     */
    private void doGetAllNetworkConfigs(HttpServletResponse response) throws IOException, ServletException {
        ToJson json = new ToJson();
        JsonArray configArray = new JsonArray();

        for (NetworkConfig defaultConfig : DEFAULT_CONFIGS) {
            // Try to read actual config from file, fallback to default
            NetworkConfig actualConfig = NetworkManager.readNetworkConfig(defaultConfig.id);
            NetworkConfig configToUse = actualConfig != null ? actualConfig : defaultConfig;
            configArray.add(configToJsonObject(configToUse));
        }

        json.getJsonObject().add(CONFIGS, configArray);
        sendJson(json, response);
        response.setStatus(HttpServletResponse.SC_OK);
    }

    /**
     * Get specific network configuration
     */
    private void doGetNetworkConfig(String configId, HttpServletResponse response) throws IOException, ServletException {
        NetworkConfig config = findConfigById(configId);
        if (config == null) {
            // Try to read from file
            config = NetworkManager.readNetworkConfig(configId);
        }

        if (config == null) {
            ServletLib.sendHTTPErrorAndLogDebug(response, HttpServletResponse.SC_NOT_FOUND, logger,
                    "Network config not found: " + configId);
            return;
        }

        ToJson json = new ToJson();
        json.addJsonObject(CONFIG, configToJsonObject(config));
        sendJson(json, response);
        response.setStatus(HttpServletResponse.SC_OK);
    }

    /**
     * Update network configuration
     */
    private void doUpdateNetworkConfig(String configId, FromJson json, HttpServletResponse response)
            throws IOException, ServletException {
        NetworkConfig existingConfig = findConfigById(configId);
        if (existingConfig == null) {
            ServletLib.sendHTTPErrorAndLogDebug(response, HttpServletResponse.SC_NOT_FOUND, logger,
                    "Network config not found: " + configId);
            return;
        }

        try {
            JsonObject jsonObj = json.getJsonObject();

            // Extract values from JSON
            String ipAddress = extractStringValue(jsonObj, "ipAddress");
            String subnetMask = extractStringValue(jsonObj, "subnetMask");
            String gateway = extractStringValue(jsonObj, "gateway");
            Boolean dhcp = extractBooleanValue(jsonObj, "dhcp", "isDhcp");

            // Use existing values if not provided
            if (ipAddress == null) {
                ipAddress = existingConfig.ipAddress;
            }
            if (subnetMask == null) {
                subnetMask = existingConfig.subnetMask;
            }
            if (gateway == null) {
                gateway = existingConfig.gateway;
            }
            if (dhcp == null) {
                dhcp = existingConfig.dhcp;
            }

            // Map interface name
            String interfaceName = NetworkManager.mapInterfaceName(configId);

            // Apply network configuration
            boolean success = NetworkManager.applyNetworkConfig(interfaceName, ipAddress, subnetMask, gateway, dhcp);

            if (!success) {
                ServletLib.sendHTTPErrorAndLogErr(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, logger,
                        "Failed to apply network configuration to system");
                return;
            }

            // Create updated config object
            NetworkConfig updatedConfig = new NetworkConfig(configId, existingConfig.name, ipAddress, subnetMask,
                    gateway, dhcp);

            ToJson responseJson = new ToJson();
            responseJson.addJsonObject(CONFIG, configToJsonObject(updatedConfig));
            sendJson(responseJson, response);
            response.setStatus(HttpServletResponse.SC_OK);

            logger.info("Successfully updated network config for {}: IP={}, Subnet={}, Gateway={}, DHCP={}",
                    configId, ipAddress, subnetMask, gateway, dhcp);

        } catch (Exception e) {
            logger.error("Error updating network config", e);
            ServletLib.sendHTTPErrorAndLogErr(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, logger,
                    "Error updating network config: " + e.getMessage());
        }
    }

    /**
     * Find config by ID from default configs
     */
    private NetworkConfig findConfigById(String id) {
        return DEFAULT_CONFIGS.stream().filter(c -> c.id.equals(id)).findFirst().orElse(null);
    }

    /**
     * Convert NetworkConfig to JsonObject
     */
    private JsonObject configToJsonObject(NetworkConfig config) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", config.id);
        obj.addProperty("name", config.name);
        obj.addProperty("ipAddress", config.ipAddress);
        obj.addProperty("subnetMask", config.subnetMask);
        obj.addProperty("gateway", config.gateway);
        obj.addProperty("dhcp", config.dhcp);
        obj.addProperty("isDhcp", config.dhcp); // For frontend compatibility
        return obj;
    }

    /**
     * Extract string value from JSON object
     */
    private String extractStringValue(JsonObject jsonObj, String key) {
        if (!jsonObj.has(key)) {
            return null;
        }
        JsonElement element = jsonObj.get(key);
        if (element.isJsonNull()) {
            return null;
        }
        return element.getAsString();
    }

    /**
     * Extract boolean value from JSON object (check multiple keys)
     */
    private Boolean extractBooleanValue(JsonObject jsonObj, String... keys) {
        for (String key : keys) {
            if (jsonObj.has(key)) {
                JsonElement element = jsonObj.get(key);
                if (!element.isJsonNull()) {
                    return element.getAsBoolean();
                }
            }
        }
        return null;
    }
}

