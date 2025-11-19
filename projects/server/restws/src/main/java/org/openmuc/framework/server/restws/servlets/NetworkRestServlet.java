package org.openmuc.framework.server.restws.servlets;

import java.io.IOException;
import java.util.Collections;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.openmuc.framework.lib.rest1.FromJson;
import org.openmuc.framework.server.restws.NetworkManager;
import org.openmuc.framework.server.restws.NetworkManager.ExecResult;
import org.openmuc.framework.server.restws.NetworkManager.NetworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class NetworkRestServlet extends GenericServlet {

    private static final long serialVersionUID = 1L;

    private static final String APPLICATION_JSON = "application/json";
    private static final Logger logger = LoggerFactory.getLogger(NetworkRestServlet.class);
    private final NetworkManager networkManager = new NetworkManager();

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType(APPLICATION_JSON);

        String[] pathAndQueryString = checkIfItIsACorrectRest(request, response, logger);
        if (pathAndQueryString == null) {
            return;
        }

        String pathInfo = request.getPathInfo(); // may be null, "/", or "/enp0s31f6"

        try {
            if (pathInfo == null || "/".equals(pathInfo)) {
                // LIST all interfaces: GET /rest/network
                listInterfaces(response);
            }
            else {
                // SHOW single interface: GET /rest/network/{iface}
                String iface = pathInfo.substring(1); // strip '/'
                showInterface(response, iface);
            }
        }
        catch (Exception e) {
            logger.error("Error reading network interfaces", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"error\":\"" + escapeJson(e.toString()) + "\"}");
        }
    }

    private void listInterfaces(HttpServletResponse response) throws Exception {
        java.util.Enumeration<java.net.NetworkInterface> ifaces =
                java.net.NetworkInterface.getNetworkInterfaces();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"interfaces\":[");

        boolean first = true;
        while (ifaces.hasMoreElements()) {
            java.net.NetworkInterface ni = ifaces.nextElement();

            if (ni.isLoopback() || !ni.supportsMulticast()) {
                // skip lo / weird virtuals if you like
                continue;
            }

            if (!first) {
                sb.append(",");
            }
            first = false;

            sb.append("{");
            sb.append("\"name\":\"").append(escapeJson(ni.getName())).append("\",");
            sb.append("\"displayName\":\"").append(escapeJson(String.valueOf(ni.getDisplayName()))).append("\",");
            sb.append("\"up\":").append(ni.isUp()).append(",");
            sb.append("\"mac\":\"").append(escapeJson(formatMac(ni.getHardwareAddress()))).append("\",");

            // addresses
            sb.append("\"addresses\":[");
            boolean firstAddr = true;
            java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
            while (addrs.hasMoreElements()) {
                if (!firstAddr) sb.append(",");
                firstAddr = false;
                sb.append("\"").append(escapeJson(addrs.nextElement().getHostAddress())).append("\"");
            }
            sb.append("]");

            sb.append("}");
        }

        sb.append("]}");

        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().write(sb.toString());
    }

    private void showInterface(HttpServletResponse response, String ifaceName) throws Exception {
        java.net.NetworkInterface ni = java.net.NetworkInterface.getByName(ifaceName);
        if (ni == null) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.getWriter().write("{\"error\":\"Interface not found: " + escapeJson(ifaceName) + "\"}");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"name\":\"").append(escapeJson(ni.getName())).append("\",");
        sb.append("\"displayName\":\"").append(escapeJson(String.valueOf(ni.getDisplayName()))).append("\",");
        sb.append("\"up\":").append(ni.isUp()).append(",");
        sb.append("\"mac\":\"").append(escapeJson(formatMac(ni.getHardwareAddress()))).append("\",");

        sb.append("\"addresses\":[");
        boolean firstAddr = true;
        java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
        while (addrs.hasMoreElements()) {
            if (!firstAddr) sb.append(",");
            firstAddr = false;
            sb.append("\"").append(escapeJson(addrs.nextElement().getHostAddress())).append("\"");
        }
        sb.append("]");

        sb.append("}");

        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().write(sb.toString());
    }

    private static String formatMac(byte[] mac) {
        if (mac == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            if (i > 0) sb.append(":");
            sb.append(String.format("%02x", mac[i]));
        }
        return sb.toString();
    }

    @Override
    public void doPut(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        logger.info("NetworkRestServlet: doPut called");
        response.setContentType(APPLICATION_JSON);

        // Common OpenMUC REST path validation
        String[] pathAndQueryString = checkIfItIsACorrectRest(request, response, logger);
        if (pathAndQueryString == null) {
            return; // error already written by helper
        }

        // /rest/network/{iface}
        String pathInfo = request.getPathInfo();       // e.g. "/enp0s31f6"
        String iface = null;
        if (pathInfo != null && pathInfo.length() > 1) {
            iface = pathInfo.substring(1);             // remove leading '/'
        }

        // Parse JSON body
        FromJson json = ServletLib.getFromJson(request, logger, response);
        if (json == null) {
            return; // helper already sent an error
        }
        JsonObject obj = json.getJsonObject();

        // If iface not in path, allow { "iface": "enp0s31f6", ... } in JSON
        if (iface == null || iface.isEmpty()) {
            iface = getOptString(obj, "iface");
        }
        if (iface == null || iface.isEmpty()) {
            ServletLib.sendHTTPErrorAndLogDebug(
                    response,
                    HttpServletResponse.SC_NOT_FOUND,
                    logger,
                    "Missing interface. Use /rest/network/{iface} or include 'iface' in JSON.",
                    "",
                    "");
            return;
        }

        // Required: mode = "dhcp" or "static"
        String mode = getOptString(obj, "mode");
        if (mode == null) {
            ServletLib.sendHTTPErrorAndLogDebug(
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    logger,
                    "'mode' is required ('dhcp' or 'static')",
                    "",
                    "");
            return;
        }

        // Collect fields for static config
        String ip = null;
        String mask = null;
        String gw = null;

        if ("static".equalsIgnoreCase(mode)) {
            ip   = getOptString(obj, "ipAddress");
            mask = getOptString(obj, "subnetMask");
            gw   = getOptString(obj, "gateway");

            if (ip == null || mask == null || gw == null) {
                ServletLib.sendHTTPErrorAndLogDebug(
                        response,
                        HttpServletResponse.SC_BAD_REQUEST,
                        logger,
                        "For static mode, 'ipAddress', 'subnetMask', and 'gateway' are required.",
                        "",
                        "");
                return;
            }
        }
        else if (!"dhcp".equalsIgnoreCase(mode)) {
            ServletLib.sendHTTPErrorAndLogDebug(
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    logger,
                    "mode must be 'dhcp' or 'static'",
                    "",
                    "");
            return;
        }

        // Build config for NetworkManager
        NetworkConfig cfg = new NetworkConfig(
                iface,
                mode,
                ip,
                mask,
                gw // DNS list – extend if your script supports it
        );

        try {
            ExecResult exec = networkManager.apply(cfg);

            String body = "{"
                    + "\"ok\":" + (exec.exitCode == 0 ? "true" : "false") + ","
                    + "\"interface\":\"" + escapeJson(iface) + "\","
                    + "\"mode\":\"" + escapeJson(mode) + "\","
                    + "\"applied\":{"
                        + "\"ipAddress\":" + (ip == null ? "null" : "\"" + escapeJson(ip) + "\"") + ","
                        + "\"subnetMask\":" + (mask == null ? "null" : "\"" + escapeJson(mask) + "\"") + ","
                        + "\"gateway\":" + (gw == null ? "null" : "\"" + escapeJson(gw) + "\"")
                    + "},"
                    + "\"exitCode\":" + exec.exitCode + ","
                    + "\"stdout\":\"" + escapeJson(exec.stdout) + "\","
                    + "\"stderr\":\"" + escapeJson(exec.stderr) + "\""
                    + "}";

            response.setStatus(exec.exitCode == 0
                    ? HttpServletResponse.SC_OK
                    : HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write(body);
        }
        catch (IllegalArgumentException iae) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.getWriter().write("{\"error\":\"" + escapeJson(iae.getMessage()) + "\"}");
        }
        catch (Exception e) {
            logger.error("Error applying network configuration for {}", iface, e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"error\":\"Execution failed: " + escapeJson(e.toString()) + "\"}");
        }
    }

    // ---- helpers -------------------------------------------------------

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
