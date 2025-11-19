package org.openmuc.framework.server.restws;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class NetworkManager {

    public static class NetworkConfig {
        public final String iface;
        public final String mode;          // "static" or "dhcp"
        public final String ipAddress;     // required for static
        public final String subnetMask;    // required for static
        public final String gateway;       // required for static
        public final String dns1;          // optional
        public final String dns2;          // optional

        public NetworkConfig(String iface,
                             String mode,
                             String ipAddress,
                             String subnetMask,
                             String gateway,
                             String dns1,
                             String dns2) {
            this.iface = iface;
            this.mode = mode;
            this.ipAddress = ipAddress;
            this.subnetMask = subnetMask;
            this.gateway = gateway;
            this.dns1 = dns1;
            this.dns2 = dns2;
        }
    }

    public static class ExecResult {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public ExecResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }

    // Executes /home/shou/scripts/netcfg.sh {iface} dhcp|static [ip] [mask] [gw] [dns1] [dns2]
    public ExecResult apply(NetworkConfig cfg) throws Exception {
        String script = "/home/shou/scripts/netcfg.sh";
        String command;

        if ("dhcp".equalsIgnoreCase(cfg.mode)) {
            command = String.format("sudo %s %s dhcp", script, shellEscape(cfg.iface));
        }
        else if ("static".equalsIgnoreCase(cfg.mode)) {
            StringBuilder sb = new StringBuilder();
            sb.append("sudo ").append(script).append(" ")
              .append(shellEscape(cfg.iface)).append(" ")
              .append("static").append(" ")
              .append(shellEscape(cfg.ipAddress)).append(" ")
              .append(shellEscape(cfg.subnetMask)).append(" ")
              .append(shellEscape(cfg.gateway));

            // Optional DNS 1
            if (cfg.dns1 != null && !cfg.dns1.isEmpty()) {
                sb.append(" ").append(shellEscape(cfg.dns1));
            }
            // Optional DNS 2
            if (cfg.dns2 != null && !cfg.dns2.isEmpty()) {
                sb.append(" ").append(shellEscape(cfg.dns2));
            }

            command = sb.toString();
        }
        else {
            throw new IllegalArgumentException("mode must be 'dhcp' or 'static'");
        }

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        pb.redirectErrorStream(false);
        Process p = pb.start();

        String stdout = readAll(p.getInputStream());
        String stderr = readAll(p.getErrorStream());
        int code = p.waitFor();

        return new ExecResult(code, stdout, stderr);
    }

    private static String readAll(java.io.InputStream is) throws Exception {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private static String shellEscape(String s) {
        if (s == null) {
            return "";
        }
        // minimal safe escape for simple args:
        return s.replace("'", "'\"'\"'");
    }
}
