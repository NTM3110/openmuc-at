package org.openmuc.framework.server.restws.servlets;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.openmuc.framework.dataaccess.DataAccessService;
import org.openmuc.framework.lib.rest1.ToJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class GpioAlarmServlet extends GenericServlet {

    private static final Logger logger = LoggerFactory.getLogger(GpioAlarmServlet.class);
    private static final Gson GSON = new Gson();

    private final GpioAlarmService gpioAlarmService;

    public GpioAlarmServlet(GpioAlarmService gpioAlarmService) {
        this.gpioAlarmService = gpioAlarmService;
    }

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType(APPLICATION_JSON);
        DataAccessService dataAccessService = handleDataAccessService(null);
        gpioAlarmService.updateOutputs(dataAccessService);
        sendStatus(response, HttpServletResponse.SC_OK, "GPIO alarm status");
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType(APPLICATION_JSON);

        String[] pathAndQueryString = checkIfItIsACorrectRest(request, response, logger);
        if (pathAndQueryString == null) {
            return;
        }

        try {
            String body = ServletLib.getJsonText(request);
            JsonObject json = body == null || body.trim().isEmpty() ? new JsonObject() : GSON.fromJson(body, JsonObject.class);
            Integer string1AlarmValue = getOptionalBit(json, "string1AlarmValue");
            Integer string2AlarmValue = getOptionalBit(json, "string2AlarmValue");
            Integer string1DirectionValue = getOptionalDirection(json, "string1Direction");
            Integer string2DirectionValue = getOptionalDirection(json, "string2Direction");

            if (!gpioAlarmService.saveSettings(string1AlarmValue, string2AlarmValue,
                    string1DirectionValue, string2DirectionValue)) {
                sendStatus(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Unable to save GPIO alarm settings");
                return;
            }

            DataAccessService dataAccessService = handleDataAccessService(null);
            gpioAlarmService.updateOutputs(dataAccessService);
            sendStatus(response, HttpServletResponse.SC_OK, "GPIO alarm settings saved");
        } catch (IllegalArgumentException e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            logger.error("Unable to save GPIO alarm settings", e);
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private Integer getOptionalBit(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        int value = json.get(key).getAsInt();
        if (value != 0 && value != 1) {
            throw new IllegalArgumentException(key + " must be 0 or 1");
        }
        return value;
    }

    private Integer getOptionalDirection(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        if (json.get(key).isJsonPrimitive() && json.get(key).getAsJsonPrimitive().isNumber()) {
            return getOptionalBit(json, key);
        }
        String value = json.get(key).getAsString();
        if ("input".equalsIgnoreCase(value) || "in".equalsIgnoreCase(value)) {
            return 0;
        }
        if ("output".equalsIgnoreCase(value) || "out".equalsIgnoreCase(value)) {
            return 1;
        }
        throw new IllegalArgumentException(key + " must be input or output");
    }

    private void sendStatus(HttpServletResponse response, int statusCode, String message)
            throws ServletException, IOException {
        ToJson json = new ToJson();
        CommonResponse.toSuccessResult(message, json);
        json.addObject(gpioAlarmService.getStatus());
        response.setStatus(statusCode);
        sendJson(json, response);
    }

    private void sendError(HttpServletResponse response, int statusCode, String message)
            throws ServletException, IOException {
        ToJson json = new ToJson();
        CommonResponse.toExceptionResult(message, json);
        json.addObject(gpioAlarmService.getStatus());
        response.setStatus(statusCode);
        sendJson(json, response);
    }
}
