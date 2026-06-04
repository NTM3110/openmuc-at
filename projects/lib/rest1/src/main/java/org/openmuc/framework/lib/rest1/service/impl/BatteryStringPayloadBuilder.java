package org.openmuc.framework.lib.rest1.service.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Objects;

public final class BatteryStringPayloadBuilder {

    private static final int CELL_SAMPLING_INTERVAL_MS = 75000;
    private static final int CELL_POLL_STEP_MS = 600;
    private static final int MODBUS_RTU_TIMEOUT_MS = 500;
    private static final int MODBUS_RTU_UNIT_BACKOFF_MS = 5000;
    private static final int CONNECT_RETRY_INTERVAL_MS = 1000;
    private static final int SAMPLING_TIMEOUT_MIN_MS = 1000;

    private BatteryStringPayloadBuilder() {}

    private static String buildModbusSettings(JsonObject portConfig) {
        // Build same format as FE used (your TS returns a string like RTU:SERIAL_ENCODING_RTU:... )
        // Example placeholder:
        int baudRate = portConfig.get("baudRate").getAsInt();
        int dataBits = portConfig.get("dataBits").getAsInt();
        String parity = portConfig.get("parity").getAsString();
        String stopBits = portConfig.get("stopBits").getAsString();
        int timeoutMs = getInt(portConfig, "timeout", MODBUS_RTU_TIMEOUT_MS);
        int unitBackoffMs = getInt(portConfig, "unitBackoff", MODBUS_RTU_UNIT_BACKOFF_MS);
        return "RTU:SERIAL_ENCODING_RTU:" + baudRate + ":DATABITS_" + dataBits +
                ":" + parity + ":STOPBITS_" + stopBits + ":ECHO_FALSE:FLOWCONTROL_NONE:FLOWCONTROL_NONE" +
                ":timeout=" + timeoutMs + ":unitBackoff=" + unitBackoffMs;
    }

    private static int getInt(JsonObject config, String key, int defaultValue) {
        if (config.has(key) && !config.get(key).isJsonNull()) {
            return config.get(key).getAsInt();
        }
        return defaultValue;
    }

    // =========================
    // MODBUS (from buildModbusPayload in battery-string.service.ts)
    // =========================
    public static JsonObject buildModbusPayload(int s, int cells, JsonObject portConfig) {
        String settings = buildModbusSettings(portConfig);
        int timeoutMs = getInt(portConfig, "timeout", MODBUS_RTU_TIMEOUT_MS);
        int samplingTimeoutMs = getInt(portConfig, "samplingTimeout", Math.max(SAMPLING_TIMEOUT_MIN_MS, timeoutMs * 2));
        int connectRetryIntervalMs = getInt(portConfig, "connectRetryInterval", CONNECT_RETRY_INTERVAL_MS);
        JsonArray channels = new JsonArray();

        java.util.function.IntUnaryOperator offsetFor = (slave) -> (slave - 1) * CELL_POLL_STEP_MS;

        for (int c = 1; c <= cells; c++) {
            String base = "str" + s + "_cell" + c;
            String sg = "str" + s + "_sg_slave_" + c;
            int off = offsetFor.applyAsInt(c);

            // cellProps (same fields FE spreads in)
            JsonObject cellProps = new JsonObject();
            cellProps.addProperty("samplingInterval", CELL_SAMPLING_INTERVAL_MS);
            cellProps.addProperty("samplingGroup", sg);
            cellProps.addProperty("samplingTimeOffset", off);
            cellProps.addProperty("loggingInterval", 60000);
            cellProps.addProperty("loggingSettings", "mqttlogger:topic=v1/gateway/telemetry");
            cellProps.addProperty("disabled", false);

            // R
            channels.add(buildModbusChannelWithCellProps(
                    base + "_R",
                    "Cell (R) (" + base + ")",
                    c + ":HOLDING_REGISTERS:3:INT16",
                    "SHORT",
                    "INPUT_REGISTERS:" + (1000 + (s - 1) * 10000 + (c - 1)) + ":SHORT",
                    cellProps
            ));

            // V
            channels.add(buildModbusChannelWithCellProps(
                    base + "_V",
                    "Cell (V) (" + base + ")",
                    c + ":HOLDING_REGISTERS:1:INT16",
                    "SHORT",
                    "INPUT_REGISTERS:" + (1300 + (s - 1) * 10000 + (c - 1)) + ":SHORT",
                    cellProps
            ));

            // T
            channels.add(buildModbusChannelWithCellProps(
                    base + "_T",
                    "Cell (T) (" + base + ")",
                    c + ":HOLDING_REGISTERS:2:INT16",
                    "SHORT",
                    "INPUT_REGISTERS:" + (1600 + (s - 1) * 10000 + (c - 1)) + ":SHORT",
                    cellProps
            ));
        }

        // Pack channels 
        channels.add(buildPackChannel(
                "str" + s + "_total_I",
                "String " + s + " (I)",
                "206:HOLDING_REGISTERS:3:INT16",
                "SHORT",
                "INPUT_REGISTERS:" + (3000 + (s - 1) * 10000) + ":SHORT",
                "str" + s + "_sg_pack",
                offsetFor.applyAsInt(113) // same as FE
        ));

        channels.add(buildPackChannel(
                "str" + s + "_ambient_T",
                "String " + s + " (T ambient)",
                "206:HOLDING_REGISTERS:4:INT16",
                "SHORT",
                "INPUT_REGISTERS:" + (3100 + (s - 1) * 10000) + ":SHORT",
                "str" + s + "_sg_pack",
                offsetFor.applyAsInt(113) // same as FE
        ));

        JsonObject configs = new JsonObject();
        configs.addProperty("id", "str" + s + "_modbus");
        configs.addProperty("description", "String " + s + " Modbus RTU");
        configs.addProperty("deviceAddress", portConfig.get("port").getAsString());
        configs.addProperty("settings", settings);
        configs.addProperty("samplingTimeout", samplingTimeoutMs);
        configs.addProperty("connectRetryInterval", connectRetryIntervalMs);
        configs.addProperty("disabled", false);

        JsonObject payload = new JsonObject();
        payload.addProperty("driver", "modbus");
        payload.add("configs", configs);
        payload.add("channels", channels);
        return payload;
    }

    private static JsonObject buildModbusChannelWithCellProps(
            String id,
            String description,
            String channelAddress,
            String valueType,
            String serverAddress,
            JsonObject cellProps
    ) {
        JsonObject ch = new JsonObject();
        ch.addProperty("id", id);
        ch.addProperty("description", description);
        ch.addProperty("channelAddress", channelAddress);
        ch.addProperty("valueType", valueType);

        JsonArray serverMappings = new JsonArray();
        JsonObject sm = new JsonObject();
        sm.addProperty("id", "modbus");
        sm.addProperty("serverAddress", serverAddress);
        serverMappings.add(sm);
        ch.add("serverMappings", serverMappings);

        // same fields FE spreads from cellProps
        ch.addProperty("samplingInterval", cellProps.get("samplingInterval").getAsInt());
        ch.addProperty("samplingGroup", cellProps.get("samplingGroup").getAsString());
        ch.addProperty("samplingTimeOffset", cellProps.get("samplingTimeOffset").getAsInt());
        ch.addProperty("loggingInterval", cellProps.get("loggingInterval").getAsInt());
        ch.addProperty("loggingSettings", cellProps.get("loggingSettings").getAsString());
        ch.addProperty("disabled", cellProps.get("disabled").getAsBoolean());

        return ch;
    }

    private static JsonObject buildPackChannel(
            String id,
            String description,
            String channelAddress,
            String valueType,
            String serverAddress,
            String samplingGroup,
            int samplingTimeOffset
    ) {
        JsonObject ch = new JsonObject();
        ch.addProperty("id", id);
        ch.addProperty("description", description);
        ch.addProperty("channelAddress", channelAddress);
        ch.addProperty("valueType", valueType);

        JsonArray serverMappings = new JsonArray();
        JsonObject sm = new JsonObject();
        sm.addProperty("id", "modbus");
        sm.addProperty("serverAddress", serverAddress);
        serverMappings.add(sm);
        ch.add("serverMappings", serverMappings);

        ch.addProperty("samplingInterval", CELL_SAMPLING_INTERVAL_MS);
        if(!Objects.equals(samplingGroup, ""))
            ch.addProperty("samplingGroup", samplingGroup);
        ch.addProperty("samplingTimeOffset", samplingTimeOffset);
        ch.addProperty("loggingInterval", 60000);
        ch.addProperty("loggingSettings", "mqttlogger:topic=v1/gateway/telemetry");
        ch.addProperty("disabled", false);
        return ch;
    }

    // =========================
    // VIRTUAL (from buildVirtualPayload in battery-string.service.ts)
    // =========================
    public static JsonObject buildVirtualPayload(int s, int cells) {
        JsonArray channels = new JsonArray();

        // pushOverview(...)
        java.util.function.BiConsumer<JsonObject, Integer> addLogging = (item, ignored) -> {
            item.addProperty("loggingInterval", 60000);
            item.addProperty("loggingSettings", "mqttlogger:topic=v1/gateway/telemetry");
        };

        // Overview
        channels.add(overview("str" + s + "_cell_qty", "INTEGER", "number of cells", null, null));
        channels.add(overview("str" + s + "_Cnominal", "DOUBLE", "C nominal", "Ah", null));
        channels.add(overview("str" + s + "_string_name", "STRING", "String name", null, 64));
        channels.add(overview("str" + s + "_cell_brand", "STRING", "Cell Brand", null, 64));
        channels.add(overview("str" + s + "_cell_model", "STRING", "Cell Model", null, 64));
        channels.add(overview("str" + s + "_Vcutoff", "DOUBLE", "V cutoff", "V", null));
        channels.add(overview("str" + s + "_Vfloat", "DOUBLE", "V float", "V", null));
        channels.add(overview("str" + s + "_R_new", "DOUBLE", "R new", "uOhm", null));
        channels.add(overview("str" + s + "_alarm_high_rst", "DOUBLE", "Alarm high internal resistance", "uOhm", null));
        channels.add(overview("str" + s + "_alarm_high_temp", "DOUBLE", "Alarm high temperature", "C", null));
        channels.add(overview("str" + s + "_alarm_low_voltage", "DOUBLE", "Alarm low cell voltage", "V", null));
        channels.add(overview("str" + s + "_alarm_high_voltage", "DOUBLE", "Alarm high cell voltage", "V", null));
        channels.add(overview("str" + s + "_serial_port_id", "STRING", "Serial port id", "ID", 64));

        // Add logging to all overview channels (FE does this)
        for (int i = 0; i < channels.size(); i++) {
            addLogging.accept(channels.get(i).getAsJsonObject(), 0);
        }

        // Stats list exactly like FE
        Object[][] stats = new Object[][]{
                {"str" + s + "_string_SOC", "DOUBLE", "Total SoC", "%", 3200},
                {"str" + s + "_string_SOH", "DOUBLE", "Total SoH", "%", 3300},
                {"str" + s + "_string_vol", "DOUBLE", "String Voltage", "V", 3400},

                {"str" + s + "_max_voltage_cell_id", "INTEGER", "Max V Cell ID", null, 3500},
                {"str" + s + "_min_voltage_cell_id", "INTEGER", "Min V Cell ID", null, 3600},
                {"str" + s + "_max_temp_cell_id", "INTEGER", "Max T Cell ID", null, 3700},
                {"str" + s + "_min_temp_cell_id", "INTEGER", "Min T Cell ID", null, 3800},
                {"str" + s + "_max_rst_cell_id", "INTEGER", "Max R Cell ID", null, 3900},
                {"str" + s + "_min_rst_cell_id", "INTEGER", "Min R Cell ID", null, 4000},
                {"str" + s + "_average_vol", "DOUBLE", "Average Cell Voltage", "V", 4100},
                {"str" + s + "_average_temp", "DOUBLE", "Average Cell Temperature", "C", 4200},
                {"str" + s + "_average_rst", "DOUBLE", "Average Cell R", "miliOhm", 4300},
                {"str" + s + "_max_voltage_value", "DOUBLE", "Max Cell Voltage", "V", 4400},
                {"str" + s + "_min_voltage_value", "DOUBLE", "Min Cell Voltage", "V", 4500},
                {"str" + s + "_max_temp_value", "DOUBLE", "Max Cell Temperature", "C", 4600},
                {"str" + s + "_min_temp_value", "DOUBLE", "Min Cell Temperature", "C", 4700},
                {"str" + s + "_max_rst_value", "DOUBLE", "Max Cell Internal Resistance", "miliOhm", 4800},
                {"str" + s + "_min_rst_value", "DOUBLE", "Min Cell Internal Resistance", "miliOhm", 4900},
                {"str" + s + "_string_alarm", "SHORT", "String Alarm", null, 5000},
        };

        for (Object[] row : stats) {
            String id = (String) row[0];
            String valueType = (String) row[1];
            String desc = (String) row[2];
            String unit = (String) row[3];
            Integer reg = (Integer) row[4];

            JsonObject item = new JsonObject();
            item.addProperty("id", id);
            item.addProperty("description", desc);
            item.addProperty("valueType", valueType);
            item.addProperty("disabled", false);
            if (unit != null) item.addProperty("unit", unit);

            item.addProperty("loggingInterval", 60000);
            item.addProperty("loggingSettings", "mqttlogger:topic=v1/gateway/telemetry");

            int serverRegister = reg + (s - 1) * 10000;
            JsonArray serverMappings = new JsonArray();
            JsonObject sm = new JsonObject();
            sm.addProperty("id", "modbus");
            sm.addProperty("serverAddress", "INPUT_REGISTERS:" + serverRegister + ":SHORT");
            serverMappings.add(sm);
            item.add("serverMappings", serverMappings);

            channels.add(item);
        }

        // Per-cell SOC/SOH (FE still sets loggingInterval/loggingSettings too)
        for (int c = 1; c <= cells; c++) {
            String base = "str" + s + "_cell" + c;

            channels.add(cellSocSoh(
                    base + "_SOC",
                    "DOUBLE",
                    "State of Charge",
                    "%",
                    "INPUT_REGISTERS:" + (1900 + (s - 1) * 10000 + (c - 1)) + ":SHORT"
            ));

            channels.add(cellSocSoh(
                    base + "_SOH",
                    "DOUBLE",
                    "State of Health",
                    "%",
                    "INPUT_REGISTERS:" + (2200 + (s - 1) * 10000 + (c - 1)) + ":SHORT"
            ));
        }

        JsonObject configs = new JsonObject();
        configs.addProperty("id", "str" + s + "_virtual");
        configs.addProperty("description", "String " + s + " calculated channels");
        configs.addProperty("disabled", false);

        JsonObject payload = new JsonObject();
        payload.addProperty("driver", "virtual");
        payload.add("configs", configs);
        payload.add("channels", channels);
        return payload;
    }

    private static JsonObject overview(String id, String valueType, String desc, String unit, Integer valueTypeLength) {
        JsonObject item = new JsonObject();
        item.addProperty("id", id);
        item.addProperty("description", desc);
        item.addProperty("valueType", valueType);
        item.addProperty("disabled", false);
        if (unit != null) item.addProperty("unit", unit);
        if ("STRING".equalsIgnoreCase(valueType)) {
            item.addProperty("valueTypeLength", valueTypeLength != null ? valueTypeLength : 64);
        }
        return item;
    }

    private static JsonObject cellSocSoh(String id, String valueType, String desc, String unit, String serverAddress) {
        JsonObject item = new JsonObject();
        item.addProperty("id", id);
        item.addProperty("description", desc);
        item.addProperty("valueType", valueType);
        item.addProperty("disabled", false);
        if (unit != null) item.addProperty("unit", unit);

        item.addProperty("loggingInterval", 60000);
        item.addProperty("loggingSettings", "mqttlogger:topic=v1/gateway/telemetry");

        JsonArray serverMappings = new JsonArray();
        JsonObject sm = new JsonObject();
        sm.addProperty("id", "modbus");
        sm.addProperty("serverAddress", serverAddress);
        serverMappings.add(sm);
        item.add("serverMappings", serverMappings);

        return item;
    }
}
