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
package org.openmuc.framework.server.restws;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * NetworkManager sử dụng JNA để gọi native APIs và quản lý network configuration
 */
public class NetworkManager {

    private static final Logger logger = LoggerFactory.getLogger(NetworkManager.class);

    private static final String NETWORKMANAGER_DIR = "/etc/NetworkManager/system-connections/";
    private static final String BACKUP_DIR = "/etc/NetworkManager/system-connections/.backup/";
    private static final int FILE_PERMISSIONS = 0600; // rw-------

    /**
     * Interface để gọi libc functions qua JNA
     */
    public interface CLibrary extends Library {
        CLibrary INSTANCE = Native.load("c", CLibrary.class);

        int chmod(String path, int mode);
    }

    /**
     * Network configuration data class
     */
    public static class NetworkConfig {
        public final String id;
        public final String name;
        public final String ipAddress;
        public final String subnetMask;
        public final String gateway;
        public final boolean dhcp;

        public NetworkConfig(String id, String name, String ipAddress, String subnetMask, String gateway,
                boolean dhcp) {
            this.id = id;
            this.name = name;
            this.ipAddress = ipAddress;
            this.subnetMask = subnetMask;
            this.gateway = gateway;
            this.dhcp = dhcp;
        }
    }

    /**
     * Apply network configuration to system
     * 
     * @param interfaceName Interface name (e.g., eth0, eth1)
     * @param ipAddress IP address (null if DHCP)
     * @param subnetMask Subnet mask (null if DHCP)
     * @param gateway Gateway (null if DHCP)
     * @param useDhcp true to use DHCP, false for static IP
     * @return true if successful, false otherwise
     */
    public static boolean applyNetworkConfig(String interfaceName, String ipAddress, String subnetMask,
            String gateway, boolean useDhcp) {
        try {
            String realInterface = mapInterfaceName(interfaceName);
            String connectionFile = findOrCreateConnectionFile(realInterface);

            if (connectionFile == null) {
                logger.error("Could not find or create connection file for interface: {}", realInterface);
                return false;
            }

            backupConnectionFile(connectionFile);

            boolean success;
            if (useDhcp) {
                success = writeDhcpConfig(connectionFile, realInterface);
            } else {
                if (ipAddress == null || subnetMask == null) {
                    logger.error("IP address and subnet mask are required for static configuration");
                    return false;
                }
                success = writeStaticIpConfig(connectionFile, realInterface, ipAddress, subnetMask, gateway);
            }

            if (success) {
                setFilePermissions(connectionFile);
                triggerNetworkManagerReload(connectionFile);
            }

            return success;

        } catch (Exception e) {
            logger.error("Error applying network configuration for interface: {}", interfaceName, e);
            return false;
        }
    }

    /**
     * Read current network configuration from file
     * 
     * @param interfaceName Interface name
     * @return NetworkConfig or null if error
     */
    public static NetworkConfig readNetworkConfig(String interfaceName) {
        String realInterface = mapInterfaceName(interfaceName);
        String connectionFile = findOrCreateConnectionFile(realInterface);

        if (connectionFile == null) {
            return null;
        }

        try {
            return parseConnectionFile(connectionFile, interfaceName);
        } catch (IOException e) {
            logger.error("Failed to read network configuration from: {}", connectionFile, e);
            return null;
        }
    }

    /**
     * Map interface name from config (eth1, eth2) to actual system name (eth0, eth1)
     */
    public static String mapInterfaceName(String configName) {
        if ("eth1".equals(configName)) {
            return "eth0";
        } else if ("eth2".equals(configName)) {
            return "eth1";
        }
        return configName;
    }

    /**
     * Find existing connection file or create new one
     */
    private static String findOrCreateConnectionFile(String interfaceName) {
        File nmDir = new File(NETWORKMANAGER_DIR);
        if (!nmDir.exists() || !nmDir.isDirectory()) {
            logger.error("NetworkManager directory does not exist: {}", NETWORKMANAGER_DIR);
            return null;
        }

        // Search for existing connection file
        File[] files = nmDir.listFiles((dir, name) -> name.endsWith(".nmconnection"));
        if (files != null) {
            for (File file : files) {
                if (isConnectionForInterface(file, interfaceName)) {
                    return file.getAbsolutePath();
                }
            }
        }

        // Create new connection file
        String newFileName = interfaceName + ".nmconnection";
        File newFile = new File(nmDir, newFileName);

        try {
            if (!newFile.createNewFile()) {
                logger.warn("Connection file already exists: {}", newFile.getAbsolutePath());
            }
            return newFile.getAbsolutePath();
        } catch (IOException e) {
            logger.error("Failed to create connection file: {}", newFile.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * Check if connection file is for specified interface
     */
    private static boolean isConnectionForInterface(File file, String interfaceName) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("interface-name=")) {
                    String value = line.substring("interface-name=".length()).trim();
                    return value.equals(interfaceName);
                }
            }
        } catch (IOException e) {
            logger.debug("Error reading connection file: {}", file.getName(), e);
        }
        return false;
    }

    /**
     * Backup connection file before modification
     */
    private static void backupConnectionFile(String filePath) {
        try {
            File backupDir = new File(BACKUP_DIR);
            if (!backupDir.exists()) {
                backupDir.mkdirs();
            }

            File sourceFile = new File(filePath);
            String fileName = sourceFile.getName();
            File backupFile = new File(backupDir, fileName + ".backup." + System.currentTimeMillis());

            Files.copy(sourceFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            logger.info("Backed up connection file to: {}", backupFile.getAbsolutePath());
        } catch (IOException e) {
            logger.warn("Failed to backup connection file: {}", filePath, e);
        }
    }

    /**
     * Write DHCP configuration to file
     */
    private static boolean writeDhcpConfig(String filePath, String interfaceName) {
        logger.info("Writing DHCP configuration for interface: {}", interfaceName);

        List<String> lines = new ArrayList<>();
        lines.add("[connection]");
        lines.add("id=" + interfaceName);
        lines.add("type=ethernet");
        lines.add("interface-name=" + interfaceName);
        lines.add("");
        lines.add("[ethernet]");
        lines.add("");
        lines.add("[ipv4]");
        lines.add("method=auto");
        lines.add("");
        lines.add("[ipv6]");
        lines.add("method=auto");
        lines.add("");

        return writeLinesToFile(filePath, lines);
    }

    /**
     * Write static IP configuration to file
     */
    private static boolean writeStaticIpConfig(String filePath, String interfaceName, String ipAddress,
            String subnetMask, String gateway) {
        logger.info("Writing static IP configuration for interface: {} - IP: {}/{}, Gateway: {}", interfaceName,
                ipAddress, subnetMask, gateway);

        String cidr = subnetMaskToCidr(subnetMask);
        String ipWithCidr = ipAddress + "/" + cidr;

        List<String> lines = new ArrayList<>();
        lines.add("[connection]");
        lines.add("id=" + interfaceName);
        lines.add("type=ethernet");
        lines.add("interface-name=" + interfaceName);
        lines.add("");
        lines.add("[ethernet]");
        lines.add("");
        lines.add("[ipv4]");
        lines.add("method=manual");
        lines.add("addresses=" + ipWithCidr);
        if (gateway != null && !gateway.isEmpty()) {
            lines.add("gateway=" + gateway);
        }
        lines.add("dns=8.8.8.8;8.8.4.4;");
        lines.add("");
        lines.add("[ipv6]");
        lines.add("method=auto");
        lines.add("");

        return writeLinesToFile(filePath, lines);
    }

    /**
     * Write lines to file
     */
    private static boolean writeLinesToFile(String filePath, List<String> lines) {
        try (FileWriter writer = new FileWriter(filePath)) {
            for (String line : lines) {
                writer.write(line);
                writer.write("\n");
            }
            logger.info("Successfully wrote network configuration to: {}", filePath);
            return true;
        } catch (IOException e) {
            logger.error("Failed to write network configuration file: {}", filePath, e);
            return false;
        }
    }

    /**
     * Set file permissions using native chmod
     */
    private static void setFilePermissions(String filePath) {
        try {
            int result = CLibrary.INSTANCE.chmod(filePath, FILE_PERMISSIONS);
            if (result == 0) {
                logger.debug("Set permissions on {} to {}", filePath, Integer.toOctalString(FILE_PERMISSIONS));
            } else {
                logger.warn("Failed to set permissions on {} (may need root)", filePath);
            }
        } catch (Exception e) {
            logger.warn("Error setting file permissions: {}", e.getMessage());
        }
    }

    /**
     * Trigger NetworkManager reload by touching the file
     */
    private static void triggerNetworkManagerReload(String filePath) {
        try {
            File file = new File(filePath);
            if (file.exists()) {
                file.setLastModified(System.currentTimeMillis());
                logger.info("Triggered NetworkManager reload for file: {}", filePath);
            }
        } catch (Exception e) {
            logger.warn("Could not trigger NetworkManager reload: {}", e.getMessage());
        }
    }

    /**
     * Parse connection file to extract network configuration
     */
    private static NetworkConfig parseConnectionFile(String filePath, String originalInterfaceName)
            throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(filePath));
        boolean inIpv4Section = false;
        String method = null;
        String addresses = null;
        String gateway = null;

        for (String line : lines) {
            line = line.trim();

            if ("[ipv4]".equals(line)) {
                inIpv4Section = true;
                continue;
            }

            if (line.startsWith("[") && !line.startsWith("[ipv4")) {
                inIpv4Section = false;
                continue;
            }

            if (inIpv4Section) {
                if (line.startsWith("method=")) {
                    method = line.substring("method=".length()).trim();
                } else if (line.startsWith("addresses=")) {
                    addresses = line.substring("addresses=".length()).trim();
                } else if (line.startsWith("gateway=")) {
                    gateway = line.substring("gateway=".length()).trim();
                }
            }
        }

        String ipAddress = null;
        String subnetMask = null;
        if (addresses != null && !addresses.isEmpty()) {
            String[] parts = addresses.split("/");
            if (parts.length == 2) {
                ipAddress = parts[0];
                int cidr = Integer.parseInt(parts[1]);
                subnetMask = cidrToSubnetMask(cidr);
            }
        }

        boolean dhcp = "auto".equals(method);
        String displayName = "Eth" + originalInterfaceName.substring(originalInterfaceName.length() - 1);

        return new NetworkConfig(originalInterfaceName, displayName, ipAddress, subnetMask, gateway, dhcp);
    }

    /**
     * Convert subnet mask to CIDR notation
     */
    private static String subnetMaskToCidr(String subnetMask) {
        String[] parts = subnetMask.split("\\.");
        if (parts.length != 4) {
            logger.warn("Invalid subnet mask format: {}", subnetMask);
            return "24";
        }

        int cidr = 0;
        for (String part : parts) {
            int octet = Integer.parseInt(part);
            cidr += Integer.bitCount(octet);
        }
        return String.valueOf(cidr);
    }

    /**
     * Convert CIDR to subnet mask
     */
    private static String cidrToSubnetMask(int cidr) {
        int mask = 0xffffffff << (32 - cidr);
        return String.format("%d.%d.%d.%d", (mask >>> 24) & 0xff, (mask >>> 16) & 0xff, (mask >>> 8) & 0xff,
                mask & 0xff);
    }
}

