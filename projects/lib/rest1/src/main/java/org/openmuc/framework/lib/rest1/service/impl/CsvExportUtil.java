package org.openmuc.framework.lib.rest1.service.impl;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;

public final class CsvExportUtil {

    private CsvExportUtil() {}

    public static void exportLatestValuesCsv(HttpServletResponse resp, DataSource dataSource, String fileName) {
        if (fileName == null || fileName.isBlank()) {
            fileName = "latest_values.csv";
        } else if (!fileName.toLowerCase().endsWith(".csv")) {
            fileName = fileName + ".csv";
        }

        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.setContentType("text/csv; charset=UTF-8");
        resp.setHeader("Content-Disposition", "attachment; filename=\"" + sanitizeFileName(fileName) + "\"");
        // Optional: prevent caching
        resp.setHeader("Cache-Control", "no-store");

        try (Connection conn = dataSource.getConnection();
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery(
                "SELECT channelid, value_type, value_double, value_string, value_boolean, updated_at " +
                "FROM latest_values ORDER BY channelid");
            OutputStream out = resp.getOutputStream()) {

            out.write("channelid,value_type,value_double,value_string,value_boolean,updated_at\n"
                    .getBytes(StandardCharsets.UTF_8));

            while (rs.next()) {
                String vd = rs.getString(3);
                String vs = rs.getString(4);
                String vb = rs.getString(5);
                
                out.write((
                    rs.getString(1) + "," +
                    rs.getString(2) + "," +
                    (vd == null ? "" : vd) + "," +
                    (vs == null ? "" : vs) + "," +
                    (vb == null ? "" : vb) + "," +
                    rs.getTimestamp(6) + "\n"
                ).getBytes(StandardCharsets.UTF_8));
            }

            out.flush();
        }catch (Exception e) {
            // If we already started writing bytes, reset may fail; still try to return a clean error.
            safeWriteError(resp, e);
            System.out.println("Error at export latest value csv: " + e.getMessage());
        }
    }

    public static void importCsv(BufferedReader reader, DataSource dataSource, org.openmuc.framework.dataaccess.DataAccessService dataAccessService, org.openmuc.framework.config.ConfigService configService) throws Exception {

        String header = reader.readLine(); // skip header
        if (header == null) return;

        List<String[]> records = new java.util.ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            records.add(line.split(",", -1));
        }

        // 1. Extract Configuration Info
        System.out.println("Starting CSV Import. Records found: " + records.size());
        if (configService != null) {
            try {
                org.openmuc.framework.config.RootConfig rootConfig = (org.openmuc.framework.config.RootConfig) configService.getConfig();
                java.util.Map<Integer, Integer> stringCellCounts = new java.util.HashMap<>();
                java.util.Map<Integer, com.google.gson.JsonObject> stringPortConfigs = new java.util.HashMap<>();
                java.util.Set<Integer> stringsFound = new java.util.HashSet<>();

                for (String[] c : records) {
                    if (c.length < 6) continue;
                    String channelId = c[0];
                    String valueString = c[3];
                    String valueDouble = c[2];

                    // Check for cell qty: strX_cell_qty
                    if (channelId.matches("str\\d+_cell_qty")) {
                        try {
                            int s = Integer.parseInt(channelId.substring(3, channelId.indexOf("_cell_qty")));
                            int qty = (int) Double.parseDouble(valueDouble);
                            stringCellCounts.put(s, qty);
                            stringsFound.add(s);
                        } catch (Exception e) {}
                    }

                    // Check for port config: dev_serial_comm_X (X seems to be 0-based index?)
                    // Assumption: dev_serial_comm_0 corresponds to str1, dev_serial_comm_1 to str2?
                    // Or maybe channelId is strX_serial_port_id? No, that just holds "serial0".
                    // The screenshot shows "dev_serial_comm_0".
                    // Let's look for "dev_serial_comm_" prefix.
                    if (channelId.startsWith("dev_serial_comm_")) {
                        try {
                            int commIdx = Integer.parseInt(channelId.substring("dev_serial_comm_".length()));
                            // Map commIdx 0 -> str1, 1 -> str2 ??
                            // Ideally we need a mapping. accessible via strX_modbus device address?
                            // For now, assuming commIdx = stringIndex - 1.
                            int s = commIdx + 1;
                            if (valueString != null && !valueString.isEmpty()) {
                                com.google.gson.JsonObject pc = parsePortConfig(valueString);
                                if (pc != null) {
                                    stringPortConfigs.put(s, pc);
                                }
                            }
                        } catch (Exception e) {}
                    }
                }

                // Create missing devices
                for (Integer s : stringsFound) {
                    if (!stringCellCounts.containsKey(s)) continue;

                    int cells = stringCellCounts.get(s);
                    com.google.gson.JsonObject portConfig = stringPortConfigs.get(s);

                    // Default port config if missing (fallback)
                    if (portConfig == null) {
                        portConfig = new com.google.gson.JsonObject();
                        portConfig.addProperty("port", "/dev/ttyMAX0"); // Default common port?
                        portConfig.addProperty("baudRate", 9600);
                        portConfig.addProperty("dataBits", 8);
                        portConfig.addProperty("parity", "NONE");
                        portConfig.addProperty("stopBits", "1");
                    }

                    String modbusId = "str" + s + "_modbus";
                    String virtualId = "str" + s + "_virtual";

                    // Check if devices exist
                    if (rootConfig.getDevice(modbusId) == null) {
                        // Create Modbus Device
                        com.google.gson.JsonObject payload = BatteryStringPayloadBuilder.buildModbusPayload(s, cells, portConfig);
                        createDevice(rootConfig, modbusId, payload);
                    }

                    if (rootConfig.getDevice(virtualId) == null) {
                        // Create Virtual Device
                        com.google.gson.JsonObject payload = BatteryStringPayloadBuilder.buildVirtualPayload(s, cells);
                        createDevice(rootConfig, virtualId, payload);
                    }
                }

                // Apply config changes
                configService.setConfig(rootConfig);
                configService.writeConfigToFile(); // Persist

            } catch (Exception e) {
                System.out.println("Error creating channels during import: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // 2. Insert Values
        String sql =
                "INSERT INTO latest_values (" +
                        " channelid," +
                        " value_type," +
                        " value_double," +
                        " value_string," +
                        " value_boolean," +
                        " updated_at" +
                        ") VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT (channelid) DO UPDATE SET " +
                        " value_type = EXCLUDED.value_type," +
                        " value_double = EXCLUDED.value_double," +
                        " value_string = EXCLUDED.value_string," +
                        " value_boolean = EXCLUDED.value_boolean," +
                        " updated_at = EXCLUDED.updated_at";


        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);

            for (String[] c : records) {
                if (c.length < 6) continue;

                String channelId = c[0];
                String valueTypeStr = c[1];
                String valueDoubleStr = c[2];
                String valueStringStr = c[3];
                String valueBooleanStr = c[4];
                String updatedAtStr = c[5];

                ps.setString(1, channelId);              // channelid
                ps.setString(2, valueTypeStr);              // value_type

                // value_double
                if (valueDoubleStr.isEmpty() || valueDoubleStr.equalsIgnoreCase("null")) {
                    ps.setNull(3, Types.DOUBLE);
                } else {
                    try {
                        ps.setDouble(3, Double.parseDouble(valueDoubleStr));
                    } catch (NumberFormatException e) {
                        ps.setNull(3, Types.DOUBLE);
                    }
                }

                // value_string
                if (valueStringStr.isEmpty() || valueStringStr.equalsIgnoreCase("null")) ps.setNull(4, Types.VARCHAR);
                // Ensure we don't insert string "null" if that was in the CSV literally
                else ps.setString(4, valueStringStr);

                // value_boolean
                if (valueBooleanStr.isEmpty() || valueBooleanStr.equalsIgnoreCase("null")) ps.setNull(5, Types.BOOLEAN);
                else ps.setBoolean(5, Boolean.parseBoolean(valueBooleanStr));

                // updated_at
                try {
                     String cleanTs = updatedAtStr.replace(" ", "T");
                     if (!cleanTs.contains("+") && !cleanTs.endsWith("Z")) {
                         cleanTs = cleanTs + "Z";
                     }
                     ps.setObject(6, OffsetDateTime.parse(cleanTs));
                } catch (Exception e) {
                     ps.setObject(6, OffsetDateTime.now());
                }

                ps.addBatch();

            // Update system cache via DataAccessService if available
                if (dataAccessService != null) {
                   // System.out.println("DataAccessService is available. Updating cache for: " + channelId);
                    try {
                        org.openmuc.framework.dataaccess.Channel channel = dataAccessService.getChannel(channelId);
                        if (channel != null) {
                            org.openmuc.framework.data.Value value = null;
                            org.openmuc.framework.data.ValueType vt = channel.getValueType();

                            // Parse value based on channel's ValueType
                            if (!valueDoubleStr.isEmpty() && (vt == org.openmuc.framework.data.ValueType.DOUBLE || vt == org.openmuc.framework.data.ValueType.FLOAT)) {
                                value = new org.openmuc.framework.data.DoubleValue(Double.parseDouble(valueDoubleStr));
                            } else if (!valueDoubleStr.isEmpty() && (vt == org.openmuc.framework.data.ValueType.INTEGER || vt == org.openmuc.framework.data.ValueType.LONG || vt == org.openmuc.framework.data.ValueType.SHORT || vt == org.openmuc.framework.data.ValueType.BYTE)) {
                                try {
                                    double d = Double.parseDouble(valueDoubleStr);
                                    switch (vt) {
                                        case INTEGER: value = new org.openmuc.framework.data.IntValue((int)d); break;
                                        case LONG: value = new org.openmuc.framework.data.LongValue((long)d); break;
                                        case SHORT: value = new org.openmuc.framework.data.ShortValue((short)d); break;
                                        case BYTE: value = new org.openmuc.framework.data.ByteValue((byte)d); break;
                                        case FLOAT: value = new org.openmuc.framework.data.FloatValue((float)d); break;
                                        default: value = new org.openmuc.framework.data.DoubleValue(d); break;
                                    }
                                } catch (NumberFormatException e) {
                                    // Ignore
                                }
                            } else if (!valueBooleanStr.isEmpty() && vt == org.openmuc.framework.data.ValueType.BOOLEAN) {
                                value = new org.openmuc.framework.data.BooleanValue(Boolean.parseBoolean(valueBooleanStr));
                            } else if (vt == org.openmuc.framework.data.ValueType.STRING) {
                                value = new org.openmuc.framework.data.StringValue(valueStringStr);
                            }

                            if (value != null) {
                                // Re-parse timestamp for the record, fallback to now
                                long time = System.currentTimeMillis();
                                try {
                                    String cleanTs = updatedAtStr.replace(" ", "T");
                                    if (!cleanTs.contains("+") && !cleanTs.endsWith("Z")) {
                                         cleanTs = cleanTs + "Z";
                                    }
                                    time = OffsetDateTime.parse(cleanTs).toInstant().toEpochMilli();
                                } catch(Exception ignored){}
                                
                                org.openmuc.framework.data.Record record = new org.openmuc.framework.data.Record(value, time);
                                channel.setLatestRecord(record);
                            }
                        } else {
                            // System.out.println("Channel not found in DataAccessService: " + channelId);
                        }
                    } catch (Exception e) {
                        System.out.println("Failed to update channel cache for " + channelId + ": " + e.getMessage());
                    }
                } else {
                    System.out.println("DataAccessService is NULL. Cannot update cache.");
                }
            }

            System.out.println("Executing batch insert...");
            ps.executeBatch();
            conn.commit();
            System.out.println("Batch insert committed.");
        } catch (Exception e) {
             System.out.println("Error during DB insert/commit: " + e.getMessage());
             e.printStackTrace();
             throw e;
        }
    }


    private static com.google.gson.JsonObject parsePortConfig(String configStr) {
        // Format: /dev/ttyS1:RTU:SERIAL_ENCODING_RTU:9600:DATABITS_8:PARITY_NONE:STOPBITS_1:...
        try {
            String[] parts = configStr.split(":");
            if (parts.length < 7) return null;

            com.google.gson.JsonObject pc = new com.google.gson.JsonObject();
            pc.addProperty("port", parts[0]); // /dev/ttyS1

            // Search for baudrate (numeric)
            for (String p : parts) {
                if (p.matches("\\d+")) {
                    pc.addProperty("baudRate", Integer.parseInt(p));
                    break;
                }
            }
            if (!pc.has("baudRate")) pc.addProperty("baudRate", 9600); // default

            // DataBits
            if (configStr.contains("DATABITS_8")) pc.addProperty("dataBits", 8);
            else if (configStr.contains("DATABITS_7")) pc.addProperty("dataBits", 7);
            else pc.addProperty("dataBits", 8);

            // Parity
            if (configStr.contains("PARITY_NONE")) pc.addProperty("parity", "PARITY_NONE");
            else if (configStr.contains("PARITY_EVEN")) pc.addProperty("parity", "PARITY_EVEN");
            else if (configStr.contains("PARITY_ODD")) pc.addProperty("parity", "PARITY_ODD");
            else pc.addProperty("parity", "PARITY_NONE");

            // StopBits
            if (configStr.contains("STOPBITS_1")) pc.addProperty("stopBits", "1");
            else if (configStr.contains("STOPBITS_2")) pc.addProperty("stopBits", "2");
            else pc.addProperty("stopBits", "1"); // default

            return pc;
        } catch (Exception e) {
            return null;
        }
    }

    private static void createDevice(org.openmuc.framework.config.RootConfig rootConfig, String deviceId, com.google.gson.JsonObject payload) throws Exception {
        org.openmuc.framework.lib.rest1.FromJson json = new org.openmuc.framework.lib.rest1.FromJson(payload.toString());
        String driverId = payload.get("driver").getAsString();
        org.openmuc.framework.config.DriverConfig driverConfig = rootConfig.getDriver(driverId);
        
        if (driverConfig != null) {
             org.openmuc.framework.config.DeviceConfig deviceConfig = driverConfig.addDevice(deviceId);
             json.setDeviceConfigV2(deviceConfig, deviceId);
        }
    }


    private static String sanitizeFileName(String name) {
        // keep it simple: remove path separators and quotes
        return name.replace("\\", "_")
                .replace("/", "_")
                .replace("\"", "_")
                .replace("\n", "_")
                .replace("\r", "_");
    }

    private static void safeWriteError(HttpServletResponse resp, Exception e) {
        try {
            if (!resp.isCommitted()) {
                resp.reset();
                resp.setStatus(500);
                resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
                resp.setContentType("text/plain; charset=UTF-8");
                resp.getWriter().write("Export failed: " + e.getMessage());
            }
        } catch (Exception ignored) {
            // nothing else we can do
        }
    }
}

