package org.openmuc.framework.server.restws.servlets;

import java.io.IOException;

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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;


public class NetworkRestServlet extends GenericServlet {

    private static final long serialVersionUID = 1L;

    private static final String APPLICATION_JSON = "application/json";
    private static final Logger logger = LoggerFactory.getLogger(NetworkRestServlet.class);
    private final NetworkManager networkManager = new NetworkManager();

        // ---- nmcli helper for IPv4 info ----------------------------------------

    private static class NmIpInfo {
        String mode = "unknown";          // "dhcp", "static", or "unknown"
        String ipAddress;                 // e.g. "192.168.4.50"
        String subnetMask;                // e.g. "255.255.255.0"
        String gateway;                   // e.g. "192.168.101.1"
        List<String> dns = new ArrayList<>();
    }

        /** Convert CIDR prefix (e.g. 24) to dotted mask (e.g. 255.255.255.0). */
    private static String cidrToMask(int prefix) {
        int mask = 0xffffffff << (32 - prefix);
        int b1 = (mask >>> 24) & 0xff;
        int b2 = (mask >>> 16) & 0xff;
        int b3 = (mask >>> 8)  & 0xff;
        int b4 =  mask         & 0xff;
        return b1 + "." + b2 + "." + b3 + "." + b4;
    }

        /**
     * Query NetworkManager (nmcli) for IPv4 configuration of a device.
     * Requires 'nmcli' to be installed and the interface managed by NetworkManager.
     */
    private NmIpInfo getNmIpInfo(String ifaceName) {
        NmIpInfo info = new NmIpInfo();

        String connectionName = null;

        // 1) Find the active connection for this device
        try {
            ProcessBuilder pbConn = new ProcessBuilder(
                    "nmcli", "-t",
                    "-f", "GENERAL.CONNECTION",
                    "dev", "show", ifaceName
            );

            Process pConn = pbConn.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(pConn.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("GENERAL.CONNECTION:")) {
                        String v = line.substring("GENERAL.CONNECTION:".length()).trim();
                        if (!v.isEmpty() && !"--".equals(v)) {
                            connectionName = v;
                        }
                    }
                }
            }
            pConn.waitFor();
        }
        catch (Exception e) {
            logger.warn("Failed to query nmcli dev show for {}: {}", ifaceName, e.toString());
        }

        // 2) If we have a connection name, get ipv4.method from the connection
        if (connectionName != null) {
            try {
                ProcessBuilder pbMethod = new ProcessBuilder(
                        "nmcli", "-t",
                        "-f", "ipv4.method",
                        "connection", "show", connectionName
                );
                Process pMethod = pbMethod.start();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(pMethod.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (line.startsWith("ipv4.method:")) {
                            String v = line.substring("ipv4.method:".length()).trim();
                            if ("auto".equalsIgnoreCase(v)) {
                                info.mode = "dhcp";
                            }
                            else if ("manual".equalsIgnoreCase(v)) {
                                info.mode = "static";
                            }
                            else {
                                info.mode = v;  // some other NM mode
                            }
                        }
                    }
                }
                pMethod.waitFor();
            }
            catch (Exception e) {
                logger.warn("Failed to query nmcli connection show for {}: {}", connectionName, e.toString());
            }
        }

        // 3) Now get IP, gateway and DNS from the device
        try {
            ProcessBuilder pbDev = new ProcessBuilder(
                    "nmcli", "-t",
                    "-f", "IP4.ADDRESS,IP4.GATEWAY,IP4.DNS",
                    "dev", "show", ifaceName
            );
            Process pDev = pbDev.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(pDev.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    // IP4.ADDRESS[1]:192.168.4.50/24
                    if (line.startsWith("IP4.ADDRESS")) {
                        String v = line.substring(line.indexOf(':') + 1).trim();
                        if (info.ipAddress == null && !v.isEmpty()) {
                            int slash = v.indexOf('/');
                            if (slash > 0) {
                                info.ipAddress = v.substring(0, slash);
                                String prefStr = v.substring(slash + 1);
                                try {
                                    int prefix = Integer.parseInt(prefStr);
                                    info.subnetMask = cidrToMask(prefix);
                                }
                                catch (NumberFormatException ignore) {
                                    // leave subnetMask null
                                }
                            }
                            else {
                                info.ipAddress = v;
                            }
                        }
                    }
                    // IP4.GATEWAY:192.168.101.1
                    else if (line.startsWith("IP4.GATEWAY:")) {
                        String v = line.substring("IP4.GATEWAY:".length()).trim();
                        if (!v.isEmpty()) {
                            info.gateway = v;
                        }
                    }
                    // IP4.DNS[1]:8.8.8.8
                    else if (line.startsWith("IP4.DNS")) {
                        String v = line.substring(line.indexOf(':') + 1).trim();
                        if (!v.isEmpty()) {
                            info.dns.add(v);
                        }
                    }
                }
            }
            pDev.waitFor();
        }
        catch (Exception e) {
            logger.warn("Failed to query nmcli IP4.* for {}: {}", ifaceName, e.toString());
        }

        return info;
    }

    private String readSysfs(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path))).trim();
        }
        catch (Exception e) {
            return "unknown";
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

   private String buildInterfaceJson(java.net.NetworkInterface ni) throws Exception {
        String ifaceName = ni.getName();
        NmIpInfo nmInfo = getNmIpInfo(ifaceName);

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"name\":\"").append(escapeJson(ni.getName())).append("\",");
        sb.append("\"displayName\":\"").append(escapeJson(String.valueOf(ni.getDisplayName()))).append("\",");
        sb.append("\"up\":").append(ni.isUp()).append(",");
        // operstate (similar to `state DOWN`)
        String operState = readSysfs("/sys/class/net/" + ni.getName() + "/operstate");

            // carrier (0 = no link, 1 = link)
        String carrier = readSysfs("/sys/class/net/" + ni.getName() + "/carrier");

        sb.append(",\"state\":\"").append(escapeJson(operState)).append("\"");
        sb.append(",\"carrier\":").append("\"").append(escapeJson(carrier)).append("\",");

        sb.append("\"mac\":\"").append(escapeJson(formatMac(ni.getHardwareAddress()))).append("\",");

        // From nmcli
        sb.append("\"mode\":\"").append(escapeJson(nmInfo.mode)).append("\",");
        sb.append("\"ipAddress\":").append(
                nmInfo.ipAddress == null ? "null" : "\"" + escapeJson(nmInfo.ipAddress) + "\""
        ).append(",");
        sb.append("\"subnetMask\":").append(
                nmInfo.subnetMask == null ? "null" : "\"" + escapeJson(nmInfo.subnetMask) + "\""
        ).append(",");
        sb.append("\"gateway\":").append(
                nmInfo.gateway == null ? "null" : "\"" + escapeJson(nmInfo.gateway) + "\""
        ).append(",");

        sb.append("\"dns\":[");
        for (int i = 0; i < nmInfo.dns.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(escapeJson(nmInfo.dns.get(i))).append("\"");
        }
        sb.append("],");

        // Original addresses[] from java.net.NetworkInterface (IPv4 + IPv6)
        sb.append("\"addresses\":[");
        boolean firstAddr = true;
        java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
        while (addrs.hasMoreElements()) {
            if (!firstAddr) {
                sb.append(",");
            }
            firstAddr = false;
            sb.append("\"").append(escapeJson(addrs.nextElement().getHostAddress())).append("\"");
        }
        sb.append("]");

        sb.append("}");
        return sb.toString();
    }

    private void listInterfaces(HttpServletResponse response) throws Exception {
        java.util.Enumeration<java.net.NetworkInterface> ifaces =
                java.net.NetworkInterface.getNetworkInterfaces();

        StringBuilder sb = new StringBuilder();
        sb.append("{\"interfaces\":[");

        boolean first = true;
        while (ifaces.hasMoreElements()) {
            java.net.NetworkInterface ni = ifaces.nextElement();

            // Skip loopback or others if you want
            if (ni.isLoopback()) {
                continue;
            }

            if (!first) {
                sb.append(",");
            }
            first = false;

            sb.append(buildInterfaceJson(ni));
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

        String json = buildInterfaceJson(ni);

        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().write(json);
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

        String[] pathAndQueryString = checkIfItIsACorrectRest(request, response, logger);
        if (pathAndQueryString == null) {
            return; // error already written
        }

        // /rest/network/{iface}
        String pathInfo = request.getPathInfo();       // e.g. "/wlp0s20f3"
        String iface = null;
        if (pathInfo != null && pathInfo.length() > 1) {
            iface = pathInfo.substring(1);             // remove leading '/'
        }

        // Parse JSON body
        FromJson json = ServletLib.getFromJson(request, logger, response);
        if (json == null) {
            return;
        }
        JsonObject obj = json.getJsonObject();

        // If iface not in path, allow { "iface": "wlp0s20f3", ... } in JSON
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

        // raw DNS string from JSON (for echoing back in response)
        String dnsRaw = null;
        // split DNS entries (for script args)
        String dns1 = null;
        String dns2 = null;

        if ("static".equalsIgnoreCase(mode)) {
            ip   = getOptString(obj, "ipAddress");
            mask = getOptString(obj, "subnetMask");
            gw   = getOptString(obj, "gateway");
            dnsRaw = getOptString(obj, "dns");  // e.g. "8.8.8.8" or "8.8.8.8,1.1.1.1"

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

            // Parse dnsRaw into up to two entries (split by comma or whitespace)
            if (dnsRaw != null && !dnsRaw.trim().isEmpty()) {
                String[] parts = dnsRaw.trim().split("[,\\s]+");
                if (parts.length >= 1) {
                    dns1 = parts[0];
                }
                if (parts.length >= 2) {
                    dns2 = parts[1];
                }
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
                gw,
                dns1,
                dns2
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
                        + "\"gateway\":" + (gw == null ? "null" : "\"" + escapeJson(gw) + "\"") + ","
                        + "\"dns\":" + (dnsRaw == null ? "null" : "\"" + escapeJson(dnsRaw) + "\"")
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
