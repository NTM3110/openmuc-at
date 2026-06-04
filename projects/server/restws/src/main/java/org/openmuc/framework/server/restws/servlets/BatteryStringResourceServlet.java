package org.openmuc.framework.server.restws.servlets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.openmuc.framework.config.ConfigService;
import org.openmuc.framework.config.DeviceConfig;
import org.openmuc.framework.config.DriverConfig;
import org.openmuc.framework.config.IdCollisionException;
import org.openmuc.framework.config.RootConfig;
import org.openmuc.framework.data.*;
import org.openmuc.framework.data.Record;
import org.openmuc.framework.dataaccess.Channel;
import org.openmuc.framework.dataaccess.DataAccessService;
import org.openmuc.framework.lib.rest1.Const;
import org.openmuc.framework.lib.rest1.FromJson;
import org.openmuc.framework.lib.rest1.service.impl.BatteryStringPayloadBuilder;
import org.openmuc.framework.lib.rest1.sql.LatestValueRepoImpl;
import org.openmuc.framework.lib.rest1.service.impl.BatteryStringPayloadBuilderDemo;
import org.openmuc.framework.lib.rest1.exceptions.MissingJsonObjectException;
import org.openmuc.framework.lib.rest1.exceptions.RestConfigIsNotCorrectException;
import org.openmuc.framework.lib.rest1.service.impl.BatteryStringPayloadBuilderDemo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.openmuc.framework.lib.rest1.ToJson;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class BatteryStringResourceServlet extends GenericServlet {

    private ConfigService configService;
    private RootConfig rootConfig;
    private DataAccessService dataAccess;
    private static final Logger logger = LoggerFactory.getLogger(BatteryStringResourceServlet.class);

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType(APPLICATION_JSON);

        String[] pathAndQueryString = checkIfItIsACorrectRest(request, response, logger);
        if (pathAndQueryString == null) {
            return;
        }

        // same pattern as DeviceResourceServlet_v2
        setConfigAccess();

        // 1) parse the SMALL request from FE
        FromJson smallJson = ServletLib.getFromJson(request, logger, response);
        if (smallJson == null) {
            return;
        }
        JsonObject req = smallJson.getJsonObject();
        ToJson json = new ToJson();

        int s = req.get("stringIndex").getAsInt();
        int cells = req.get("cellQty").getAsInt();
        JsonObject portConfig = req.getAsJsonObject("portConfig");

        // validate basic constraints
        if (cells <= 0 || cells > 400) {
            ServletLib.sendHTTPErrorAndLogDebug(response, HttpServletResponse.SC_NOT_ACCEPTABLE, logger,
                    "cellQty out of allowed range");
            return;
        }

        String modbusId = "str" + s + "_modbus";
        String virtualId = "str" + s + "_virtual";

        // 2) build the BIG payloads (same structure FE builds today)
        //TODO: change back to no Demo when deploy to real-world system
        JsonObject modbusPayload = BatteryStringPayloadBuilder.buildModbusPayload(s, cells, portConfig);
        JsonObject virtualPayload = BatteryStringPayloadBuilder.buildVirtualPayload(s, cells);

        // 3) apply them using the same logic as DeviceResourceServlet_v2 does in POST
        // (driverConfig.addDevice + FromJson.setDeviceConfigV2 + write config)
        boolean ok1 = createDeviceFromPayload(modbusId, modbusPayload, response);
        if (!ok1){
            json.addString("status", "NOT ok");
            json.addString("stringIndex", Integer.toString(s));
            json.addString("error", "Cannot create device from modbus payload!!!!");
            return;
        }

        boolean ok2 = createDeviceFromPayload(virtualId, virtualPayload, response);
        if (!ok2){
            json.addString("status", "NOT ok");
            json.addString("stringIndex", Integer.toString(s));
            json.addString("error", "Cannot create device from virtual payload!!!!");
            return;
        }

        if (!persistAlarmThresholds(s, req)) {
            ServletLib.sendHTTPErrorAndLogErr(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, logger,
                    "Could not persist alarm thresholds in latest_values.");
            return;
        }

//        boolean ok3 = writeOverviewValues(s, cells, req, response);
//        if(!ok3) {
//            json.addString("status", "NOT ok");
//            json.addString("stringIndex", Integer.toString(s));
//            json.addString("error", "Cannot create device from modbus payload!!!!");
//            return;
//        }

        // 4) respond success
        // JsonObject out = new JsonObject();
        json.addString("status", "ok");
        json.addString("stringIndex", Integer.toString(s));
        sendJson(json, response);
    }

    private boolean persistAlarmThresholds(int stringIndex, JsonObject request) {
        String prefix = "str" + stringIndex + "_alarm_";
        Map<String, Double> thresholds = new LinkedHashMap<>();
        thresholds.put(prefix + "high_rst", getAsDoubleOr(request, "highResistanceThreshold", 0.0));
        thresholds.put(prefix + "high_temp", getAsDoubleOr(request, "highTemperatureThreshold", 0.0));
        thresholds.put(prefix + "low_voltage", getAsDoubleOr(request, "lowVoltageThreshold", 0.0));
        thresholds.put(prefix + "high_voltage", getAsDoubleOr(request, "highVoltageThreshold", 0.0));
        return LatestValueRepoImpl.upsertDoubleValues(thresholds);
    }

    private boolean writeOverviewValues(int s, int cells, JsonObject req, HttpServletResponse response) {
        // Required channels (created by your virtual payload builder)
        String chCellQty     = "str" + s + "_cell_qty";
        String chStringName  = "str" + s + "_string_name";
        String chCellBrand   = "str" + s + "_cell_brand";
        String chCellModel   = "str" + s + "_cell_model";
        String chCnominal    = "str" + s + "_Cnominal";
        String chVcutoff     = "str" + s + "_Vcutoff";
        String chVfloat      = "str" + s + "_Vfloat";
        String chSerialPort  = "str" + s + "_serial_port_id";

        // Extract values from request (safe defaults)
        String stringName   = getAsStringOr(req, "stringName", "String " + s);
        String cellBrand    = getAsStringOr(req, "cellBrand", "");
        String cellModel    = getAsStringOr(req, "cellModel", "");
        String serialPortId = getAsStringOr(req, "serialPortId", "");

        double ratedCapacity = getAsDoubleOr(req, "ratedCapacity", 0.0);   // maps to Cnominal
        double cutoffVoltage = getAsDoubleOr(req, "cutoffVoltage", 0.0);   // maps to Vcutoff
        double floatVoltage  = getAsDoubleOr(req, "floatVoltage", 0.0);    // maps to Vfloat

        // Write values (same effect as PUT /rest/channels/{id} with a value body)
        if (!writeChannelValue(chCellQty, new IntValue(cells), response)) return false;
        if (!writeChannelValue(chStringName, new StringValue(stringName), response)) return false;
        if (!writeChannelValue(chCellBrand, new StringValue(cellBrand), response)) return false;
        if (!writeChannelValue(chCellModel, new StringValue(cellModel), response)) return false;
        if (!writeChannelValue(chSerialPort, new StringValue(serialPortId), response)) return false;

        if (ratedCapacity != 0.0 && !writeChannelValue(chCnominal, new DoubleValue(ratedCapacity), response)) return false;
        if (cutoffVoltage != 0.0 && !writeChannelValue(chVcutoff, new DoubleValue(cutoffVoltage), response)) return false;
        if (floatVoltage  != 0.0 && !writeChannelValue(chVfloat,  new DoubleValue(floatVoltage), response)) return false;

        return true;
    }
    private boolean writeChannelValue(String channelId, org.openmuc.framework.data.Value value, HttpServletResponse response) {
        Channel channel = dataAccess.getChannel(channelId);
        if (channel == null) {
            ServletLib.sendHTTPErrorAndLogErr(response, HttpServletResponse.SC_CONFLICT, logger,
                    "Channel not found (not loaded yet?): ", channelId);
            return false;
        }
        try {
//        Flag flag = channel.write(value);
            long now = System.currentTimeMillis();
            Record record = new Record(value, now, Flag.VALID);
            channel.setLatestRecord(record);
        }catch (Exception e){
            ServletLib.sendHTTPErrorAndLogErr(response, HttpServletResponse.SC_CONFLICT, logger,
                    "GEtting record and set failed", channelId);
            return false;
        }
        return true;
    }

    private static String getAsStringOr(JsonObject obj, String key, String def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try { return obj.get(key).getAsString(); }
        catch (Exception ignored) { return def; }
    }

    private static double getAsDoubleOr(JsonObject obj, String key, double def) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return def;
        try { return obj.get(key).getAsDouble(); }
        catch (Exception ignored) { return def; }
    }



    // This is basically DeviceResourceServlet_v2.setAndWriteHttpPostDeviceConfig,
    // but taking JsonObject instead of reading from HTTP.
    private synchronized boolean createDeviceFromPayload(String deviceId, JsonObject payload, HttpServletResponse response) {
        try {
            FromJson json = new FromJson(payload.toString());

            DeviceConfig existing = rootConfig.getDevice(deviceId);
            String driverId = payload.get(Const.DRIVER).getAsString();

            DriverConfig driverConfig = rootConfig.getDriver(driverId);
            if (driverConfig == null) {
                ServletLib.sendHTTPErrorAndLogErr(response, HttpServletResponse.SC_CONFLICT, logger,
                        "Driver does not exists: ", driverId);
                return false;
            }
            if (existing != null) {
                ServletLib.sendHTTPErrorAndLogErr(response, HttpServletResponse.SC_CONFLICT, logger,
                        "Device already exists: ", deviceId);
                return false;
            }

            try {
                DeviceConfig deviceConfig = driverConfig.addDevice(deviceId);
                json.setDeviceConfigV2(deviceConfig, deviceId);
            } catch (IdCollisionException ignored) {
            }

            configService.setConfig(rootConfig);
            configService.writeConfigToFile();
            return true;

        } catch (JsonSyntaxException | RestConfigIsNotCorrectException | MissingJsonObjectException e) {
            ServletLib.sendHTTPErrorAndLogDebug(response, HttpServletResponse.SC_NOT_ACCEPTABLE, logger, e.getMessage());
            return false;
        } catch (Exception e) {
            ServletLib.sendHTTPErrorAndLogErr(response, HttpServletResponse.SC_CONFLICT, logger, e.getMessage());
            return false;
        }
    }
    private void setConfigAccess() {
        this.dataAccess = handleDataAccessService(null);
        this.configService = handleConfigService(null);
        this.rootConfig = handleRootConfig(null);
    }
}
