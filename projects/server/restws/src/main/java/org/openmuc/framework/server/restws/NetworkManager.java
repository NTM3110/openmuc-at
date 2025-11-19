package org.openmuc.framework.server.restws;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

public class NetworkManager {

    public static class NetworkConfig {
        public final String iface;
        public final String mode;          // "static" or "dhcp"
        public final String ipAddress;     // required for static
        public final String subnetMask;    // required for static
        public final String gateway;       // required for static   // optional

        public NetworkConfig(String iface, String mode,
                             String ipAddress, String subnetMask, String gateway
                             ) {
            this.iface = iface;
            this.mode = mode;
            this.ipAddress = ipAddress;
            this.subnetMask = subnetMask;
            this.gateway = gateway;
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

    // Executes /home/shou/scripts/netcfg.sh {iface} dhcp|static [ip] [mask] [gw]
    public ExecResult apply(NetworkConfig cfg) throws Exception {
        String script = "/home/shou/scripts/netcfg.sh";
        String command;

        if ("dhcp".equalsIgnoreCase(cfg.mode)) {
            command = String.format("sudo %s %s dhcp", script, shellEscape(cfg.iface));
        } else if ("static".equalsIgnoreCase(cfg.mode)) {
            command = String.format(
                "sudo %s %s static %s %s %s",
                script,
                shellEscape(cfg.iface),
                shellEscape(cfg.ipAddress),
                shellEscape(cfg.subnetMask),
                shellEscape(cfg.gateway)
            );
        } else {
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
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    private static String shellEscape(String s) {
        if (s == null) return "";
        // minimal safe escape for simple args:
        return s.replace("'", "'\"'\"'");
    }
}
