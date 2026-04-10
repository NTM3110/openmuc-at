package org.openmuc.framework.server.restws.servlets;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.net.InetAddress;
import java.net.NetworkInterface;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.openmuc.framework.lib.rest1.FromJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * REST servlet for WiFi management using nmcli.
 * Provides endpoints to scan for WiFi networks and connect to them.
 */
public class WifiRestServlet extends GenericServlet {

    private static final long serialVersionUID = 1L;
    private static final String APPLICATION_JSON = "application/json";
    private static final Logger logger = LoggerFactory.getLogger(WifiRestServlet.class);

    /**
     * Represents a WiFi network from nmcli scan results.
     */
    private static class WifiNetwork {
        String ssid;
        String bssid;
        String mode;
        int channel;
        String rate;
        int signal;
        String bars;
        String security;
        boolean inUse;
        boolean known; // True if a connection profile exists

        WifiNetwork() {
        }
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType(APPLICATION_JSON);

        String[] pathAndQueryString = checkIfItIsACorrectRest(request, response, logger);
        if (pathAndQueryString == null) {
            return;
        }

        String pathInfo = request.getPathInfo(); // may be null, "/", or "/scan"

        try {
            if (pathInfo == null || "/".equals(pathInfo) || "/scan".equals(pathInfo)) {
                // Scan for WiFi networks: GET /rest/wifi/scan
                scanWifiNetworks(response);
            } else if ("/status".equals(pathInfo)) {
                // Get current WiFi status (IP address)
                getWifiStatus(response);
            } else {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.getWriter().write("{\"error\":\"Unknown endpoint\"}");
            }
        } catch (Exception e) {
            logger.error("Error scanning WiFi networks", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"error\":\"" + escapeJson(e.toString()) + "\"}");
        }
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        logger.info("WifiRestServlet: doPost called");
        response.setContentType(APPLICATION_JSON);

        String[] pathAndQueryString = checkIfItIsACorrectRest(request, response, logger);
        if (pathAndQueryString == null) {
            return;
        }

        String pathInfo = request.getPathInfo(); // e.g. "/connect"

        try {
            if ("/connect".equals(pathInfo)) {
                // Connect to WiFi: POST /rest/wifi/connect
                connectToWifi(request, response);
            } else {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                response.getWriter().write("{\"error\":\"Unknown endpoint\"}");
            }
        } catch (Exception e) {
            logger.error("Error connecting to WiFi", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"error\":\"" + escapeJson(e.toString()) + "\"}");
        }
    }

    /**
     * Get IP address of the connected WiFi interface.
     */
    private void getWifiStatus(HttpServletResponse response) throws Exception {
        String ipAddress = null;
        String interfaceName = null;

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                String name = ni.getName();
                if (ni.isUp() && !ni.isLoopback() && (name.startsWith("wlan") || name.startsWith("p2p") || name.startsWith("wl"))) {
                    Enumeration<InetAddress> addresses = ni.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        if (addr instanceof java.net.Inet4Address) {
                            ipAddress = addr.getHostAddress();
                            interfaceName = name;
                            break;
                        }
                    }
                    if (ipAddress != null) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error getting network interfaces", e);
        }

        JsonObject status = new JsonObject();
        if (ipAddress != null) {
            status.addProperty("connected", true);
            status.addProperty("ip", ipAddress);
            status.addProperty("interface", interfaceName);
        } else {
            status.addProperty("connected", false);
        }

        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().write(status.toString());
    }

    /**
     * Scan for available WiFi networks using nmcli.
     */
    private void scanWifiNetworks(HttpServletResponse response) throws Exception {
        List<WifiNetwork> networks = new ArrayList<>();

        try {
            // Get list of known connections (saved profiles)
            java.util.Set<String> knownConnections = new java.util.HashSet<>();
            try {
                ProcessBuilder pb = new ProcessBuilder("nmcli", "-t", "-f", "NAME", "connection", "show");
                Process process = pb.start();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String name = line.trim();
                        if (!name.isEmpty()) {
                            // Unescape name if needed (nmcli -t escapes ':' as '\:')
                            name = name.replace("\\:", ":");
                            knownConnections.add(name);
                        }
                    }
                }
                process.waitFor();
            } catch (Exception e) {
                logger.warn("Failed to get known connections", e);
            }

            // Execute: nmcli device wifi list
            ProcessBuilder pb = new ProcessBuilder("nmcli", "device", "wifi", "list");
            Process process = pb.start();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                boolean firstLine = true;

                while ((line = br.readLine()) != null) {
                    // Skip header line
                    if (firstLine) {
                        firstLine = false;
                        continue;
                    }

                    WifiNetwork network = parseWifiLine(line);
                    if (network != null && network.ssid != null && !network.ssid.isEmpty()) {
                        // Check if this network is known (saved)
                        if (knownConnections.contains(network.ssid)) {
                            network.known = true;
                        }
                        networks.add(network);
                    }
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("nmcli device wifi list returned non-zero exit code: {}", exitCode);
            }
        } catch (Exception e) {
            logger.error("Failed to scan WiFi networks", e);
            throw e;
        }

        // Deduplicate networks: keep only the strongest signal for each SSID
        // This matches the behavior of Ubuntu's WiFi UI
        java.util.Map<String, WifiNetwork> uniqueNetworks = new java.util.HashMap<>();
        for (WifiNetwork network : networks) {
            String ssid = network.ssid;
            if (!uniqueNetworks.containsKey(ssid)) {
                uniqueNetworks.put(ssid, network);
            } else {
                WifiNetwork existing = uniqueNetworks.get(ssid);
                
                // If the new network is in use, always use it
                if (network.inUse) {
                    uniqueNetworks.put(ssid, network);
                }
                // If existing is in use, keep it
                else if (existing.inUse) {
                    // Keep existing
                }
                // Otherwise, keep the one with stronger signal
                else if (network.signal > existing.signal) {
                    uniqueNetworks.put(ssid, network);
                }
            }
        }

        // Build JSON response from deduplicated networks
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        int i = 0;
        for (WifiNetwork network : uniqueNetworks.values()) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(buildWifiNetworkJson(network));
            i++;
        }
        sb.append("]");

        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().write(sb.toString());
    }

    /**
     * Parse a single line from nmcli device wifi list output.
     * Format: IN-USE  BSSID              SSID               MODE   CHAN  RATE        SIGNAL  BARS  SECURITY
     */
    private WifiNetwork parseWifiLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        WifiNetwork network = new WifiNetwork();

        try {
            // Check if network is in use (starts with *)
            network.inUse = line.trim().startsWith("*");

            // Split by multiple spaces to parse columns
            String[] parts = line.trim().split("\\s{2,}");
            if (parts.length < 8) {
                return null; // Invalid line
            }

            int idx = 0;

            // IN-USE column (may be empty or *)
            if (parts[idx].equals("*") || parts[idx].isEmpty()) {
                idx++;
            }

            // BSSID
            network.bssid = parts[idx++];

            // SSID
            network.ssid = parts[idx++];

            // MODE
            network.mode = parts[idx++];

            // CHAN
            try {
                network.channel = Integer.parseInt(parts[idx++]);
            } catch (NumberFormatException e) {
                network.channel = 0;
            }

            // RATE
            network.rate = parts[idx++];

            // SIGNAL
            try {
                network.signal = Integer.parseInt(parts[idx++]);
            } catch (NumberFormatException e) {
                network.signal = 0;
            }

            // BARS
            network.bars = parts[idx++];

            // SECURITY (remaining parts)
            if (idx < parts.length) {
                network.security = parts[idx];
            } else {
                network.security = "";
            }

        } catch (Exception e) {
            logger.warn("Failed to parse WiFi line: {}", line, e);
            return null;
        }

        return network;
    }

    /**
     * Build JSON representation of a WiFi network.
     */
    private String buildWifiNetworkJson(WifiNetwork network) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"ssid\":\"").append(escapeJson(network.ssid)).append("\",");
        sb.append("\"bssid\":\"").append(escapeJson(network.bssid)).append("\",");
        sb.append("\"mode\":\"").append(escapeJson(network.mode)).append("\",");
        sb.append("\"channel\":").append(network.channel).append(",");
        sb.append("\"rate\":\"").append(escapeJson(network.rate)).append("\",");
        sb.append("\"signal\":").append(network.signal).append(",");
        sb.append("\"bars\":\"").append(escapeJson(network.bars)).append("\",");
        sb.append("\"security\":\"").append(escapeJson(network.security)).append("\",");
        sb.append("\"inUse\":").append(network.inUse).append(",");
        sb.append("\"known\":").append(network.known);
        sb.append("}");
        return sb.toString();
    }

    /**
     * Connect to a WiFi network using nmcli.
     * Expects JSON body: { "ssid": "NetworkName", "bssid": "AA:BB:CC:DD:EE:FF", "password": "optional" }
     */
    private void connectToWifi(HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        // Parse JSON body
        FromJson json = ServletLib.getFromJson(request, logger, response);
        if (json == null) {
            return;
        }
        JsonObject obj = json.getJsonObject();

        // Get SSID (for display/logging purposes)
        String ssid = getOptString(obj, "ssid");
        if (ssid == null || ssid.isEmpty()) {
            ServletLib.sendHTTPErrorAndLogDebug(
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    logger,
                    "'ssid' is required",
                    "",
                    "");
            return;
        }

        // Get BSSID (required for connection)
        String bssid = getOptString(obj, "bssid");
        if (bssid == null || bssid.isEmpty()) {
            ServletLib.sendHTTPErrorAndLogDebug(
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    logger,
                    "'bssid' is required",
                    "",
                    "");
            return;
        }

        // Get password (optional for open networks)
        String password = getOptString(obj, "password");

        try {
            // First, trigger a rescan to ensure nmcli has fresh network data
            // This helps avoid "No network with SSID found" errors
            try {
                ProcessBuilder rescanPb = new ProcessBuilder("nmcli", "device", "wifi", "rescan");
                Process rescanProcess = rescanPb.start();
                rescanProcess.waitFor();
                // Give it a moment to complete the scan
                Thread.sleep(1000);
            } catch (Exception e) {
                logger.warn("Failed to rescan WiFi networks before connecting, continuing anyway", e);
            }

            // Check if there's an existing connection profile for this SSID and get its UUID
            String existingProfileUuid = null;
            try {
                // Request NAME and UUID. nmcli -t uses ':' as separator.
                // We need to handle that SSID might contain ':' which would be escaped as '\:'
                ProcessBuilder checkPb = new ProcessBuilder("nmcli", "-t", "-f", "NAME,UUID", "connection", "show");
                Process checkProcess = checkPb.start();
                
                try (BufferedReader br = new BufferedReader(new InputStreamReader(checkProcess.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        // Line format: NAME:UUID
                        // Find the last colon to split, as UUID doesn't contain colons, but NAME might
                        int lastColonIndex = line.lastIndexOf(':');
                        if (lastColonIndex != -1) {
                            String name = line.substring(0, lastColonIndex);
                            String uuid = line.substring(lastColonIndex + 1);
                            
                            // Unescape name if needed (nmcli escapes ':' as '\:')
                            name = name.replace("\\:", ":");
                            
                            // Trim the name to handle potential trailing spaces in connection names
                            // e.g. "Phong 1 " vs "Phong 1"
                            if (name.trim().equals(ssid.trim())) {
                                existingProfileUuid = uuid;
                                break;
                            }
                        }
                    }
                }
                checkProcess.waitFor();
            } catch (Exception e) {
                logger.warn("Failed to check existing connection profile", e);
            }

            List<String> command = new ArrayList<>();
            command.add("nmcli");

            if (password != null && !password.isEmpty()) {
                // Case 1: Password provided
                // Delete existing profile (if any) to ensure we use the new password and clean state
                if (existingProfileUuid != null) {
                    try {
                        logger.info("Deleting existing connection profile to update credentials: {} (UUID: {})", ssid, existingProfileUuid);
                        ProcessBuilder deletePb = new ProcessBuilder("nmcli", "connection", "delete", existingProfileUuid);
                        Process deleteProcess = deletePb.start();
                        deleteProcess.waitFor();
                    } catch (Exception e) {
                        logger.warn("Failed to delete existing connection profile", e);
                    }
                }
                
                // Connect using BSSID and new password
                command.add("device");
                command.add("wifi");
                command.add("connect");
                command.add(bssid);
                command.add("password");
                command.add(password);
                logger.info("Connecting to WiFi '{}' (BSSID: {}) with new password", ssid, bssid);
                
            } else {
                // Case 2: No password provided
                if (existingProfileUuid != null) {
                    // Try to bring up the existing connection profile using UUID
                    command.add("connection");
                    command.add("up");
                    command.add(existingProfileUuid); // Use UUID for reliability
                    logger.info("Bringing up existing connection profile: {} (UUID: {})", ssid, existingProfileUuid);
                } else {
                    // No profile and no password -> Error
                    ServletLib.sendHTTPErrorAndLogDebug(
                            response,
                            HttpServletResponse.SC_BAD_REQUEST,
                            logger,
                            "Password required for new connection",
                            "",
                            "");
                    return;
                }
            }

            // Execute command
            ProcessBuilder pb = new ProcessBuilder(command);
            Process process = pb.start();

            // Read output
            StringBuilder output = new StringBuilder();
            StringBuilder errorOutput = new StringBuilder();

            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 BufferedReader errBr = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {

                String line;
                while ((line = br.readLine()) != null) {
                    output.append(line).append("\n");
                }

                while ((line = errBr.readLine()) != null) {
                    errorOutput.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();

            if (exitCode == 0) {
                // Success
                logger.info("Successfully connected to WiFi network: {}", ssid);
                response.setStatus(HttpServletResponse.SC_OK);
                response.getWriter().write("{\"success\":true,\"message\":\"Connected to " + escapeJson(ssid) + "\"}");
            } else {
                // Failed
                logger.warn("Failed to connect to WiFi network: {}. Exit code: {}. Error: {}",
                        ssid, exitCode, errorOutput.toString());
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.getWriter().write("{\"success\":false,\"error\":\"" +
                        escapeJson(errorOutput.toString().trim()) + "\"}");
            }

        } catch (Exception e) {
            logger.error("Error executing nmcli to connect to WiFi: {}", ssid, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"success\":false,\"error\":\"" + escapeJson(e.toString()) + "\"}");
        }
    }

    // ---- Helper methods ----

    private static String getOptString(JsonObject obj, String key) {
        if (obj == null) {
            return null;
        }
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) {
            return null;
        }
        return el.getAsString();
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
