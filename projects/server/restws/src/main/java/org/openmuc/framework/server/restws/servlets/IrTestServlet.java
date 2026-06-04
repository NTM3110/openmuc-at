package org.openmuc.framework.server.restws.servlets;

import com.ghgande.j2mod.modbus.Modbus;
import com.ghgande.j2mod.modbus.ModbusException;
import com.ghgande.j2mod.modbus.io.ModbusSerialTransaction;
import com.ghgande.j2mod.modbus.msg.ReadMultipleRegistersRequest;
import com.ghgande.j2mod.modbus.msg.ReadMultipleRegistersResponse;
import com.ghgande.j2mod.modbus.msg.WriteSingleRegisterRequest;
import com.ghgande.j2mod.modbus.net.AbstractSerialConnection;
import com.ghgande.j2mod.modbus.net.SerialConnection;
import com.ghgande.j2mod.modbus.procimg.SimpleRegister;
import com.ghgande.j2mod.modbus.util.SerialParameters;
import com.fazecast.jSerialComm.SerialPort;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.openmuc.framework.lib.rest1.ToJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class IrTestServlet extends GenericServlet {

    private static final long serialVersionUID = -6419116382526315266L;
    private static final Logger logger = LoggerFactory.getLogger(IrTestServlet.class);
    private static final Gson GSON = new Gson();

    private static final int BAUD_RATE = 9600;
    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = AbstractSerialConnection.ONE_STOP_BIT;
    private static final int PARITY = SerialPort.NO_PARITY;
    private static final int TIMEOUT_MS = 1000;
    private static final int WRITE_TIMEOUT_MS = 5000;
    private static final int WRITE_RETRIES = 3;
    private static final int WRITE_RETRY_DELAY_MS = 500;
    private static final int MAX_LOG_LINES = 200;
    private static final int IR_REGISTER_START = 1;
    private static final int IR_REGISTER_COUNT = 4;
    private static final int IR_VALUE_INDEX = 2;
    private static final int ACTIVATE_REGISTER = 0x000C;
    private static final int ACTIVATE_VALUE = 0xF0F0;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();
    private volatile IrTaskStatus status = IrTaskStatus.idle();
    private volatile SerialConnection currentConnection;
    private volatile boolean stopRequested;

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType(APPLICATION_JSON);
        sendStatus(response, HttpServletResponse.SC_OK, "IR test status");
    }

    @Override
    public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType(APPLICATION_JSON);

        String[] pathAndQueryString = checkIfItIsACorrectRest(request, response, logger);
        if (pathAndQueryString == null) {
            return;
        }

        String[] pathInfoArray = ServletLib.getPathInfoArray(pathAndQueryString[ServletLib.PATH_ARRAY_NR]);
        String action = pathInfoArray.length == 0 ? "" : pathInfoArray[0].replace("/", "");

        try {
            if ("activate".equals(action)) {
                startTask("activate", parseRequest(request, true), false);
                sendStatus(response, HttpServletResponse.SC_ACCEPTED, "IR activation started");
            }
            else if ("scan".equals(action)) {
                startTask("scan", parseRequest(request, false), false);
                sendStatus(response, HttpServletResponse.SC_ACCEPTED, "TA address scan started");
            }
            else if ("scan-force".equals(action)) {
                startTask("scan", parseRequest(request, false), true);
                sendStatus(response, HttpServletResponse.SC_ACCEPTED, "IR activation stopped; TA address scan started");
            }
            else if ("activate-force".equals(action)) {
                startTask("activate", parseRequest(request, true), true);
                sendStatus(response, HttpServletResponse.SC_ACCEPTED, "Running task stopped; IR activation started");
            }
            else if ("stop".equals(action)) {
                stopCurrentTask("Stop requested by user");
                sendStatus(response, HttpServletResponse.SC_OK, "IR task stop requested");
            }
            else {
                sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid IR test action");
            }
        } catch (IllegalArgumentException e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            sendError(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        } catch (Exception e) {
            logger.error("Unable to handle IR test request", e);
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    public void destroy() {
        stopCurrentTask("Servlet destroyed");
        executor.shutdownNow();
    }

    private IrTaskRequest parseRequest(HttpServletRequest request, boolean requireRange) throws IOException {
        String body = ServletLib.getJsonText(request);
        JsonObject json = body == null || body.trim().isEmpty() ? new JsonObject() : GSON.fromJson(body, JsonObject.class);

        String serialPort = getString(json, "serialPort", null);
        int startId = getInt(json, "startId", 1);
        int endId = getInt(json, "endId", 110);

        if (serialPort == null || serialPort.trim().isEmpty()) {
            throw new IllegalArgumentException("serialPort is required");
        }
        validateSlaveId(startId, "startId");
        validateSlaveId(endId, "endId");
        if (startId > endId) {
            throw new IllegalArgumentException("startId must be less than or equal to endId");
        }
        if (requireRange && (!json.has("startId") || !json.has("endId"))) {
            throw new IllegalArgumentException("startId and endId are required");
        }

        IrTaskRequest taskRequest = new IrTaskRequest();
        taskRequest.serialPort = serialPort.trim();
        taskRequest.startId = startId;
        taskRequest.endId = endId;
        return taskRequest;
    }

    private void validateSlaveId(int value, String fieldName) {
        if (value < 1 || value > 247) {
            throw new IllegalArgumentException(fieldName + " must be between 1 and 247");
        }
    }

    private String getString(JsonObject json, String key, String defaultValue) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : defaultValue;
    }

    private int getInt(JsonObject json, String key, int defaultValue) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsInt() : defaultValue;
    }

    private void startTask(String type, IrTaskRequest taskRequest, boolean forceStopRunning) {
        synchronized (lock) {
            if (status.running) {
                if (!forceStopRunning) {
                    throw new IllegalStateException("Task " + status.type + " is running at module " + status.currentId);
                }
                stopCurrentTask("Stopped to start " + type);
            }
            stopRequested = false;
            status = IrTaskStatus.started(type, taskRequest);
        }

        executor.submit(() -> runTask(type, taskRequest));
    }

    private void runTask(String type, IrTaskRequest taskRequest) {
        appendLog("Starting " + type + " on " + taskRequest.serialPort + " range " + taskRequest.startId + "-" + taskRequest.endId);

        try {
            SerialConnection connection = openConnection(taskRequest.serialPort);
            currentConnection = connection;

            if ("scan".equals(type)) {
                runScan(taskRequest, connection);
            }
            else {
                runActivate(taskRequest, connection);
            }

            synchronized (lock) {
                status.running = false;
                status.finishedAt = Instant.now().toString();
                status.state = stopRequested ? "stopped" : "completed";
                status.exitCode = stopRequested ? 130 : 0;
                if ("scan".equals(type)) {
                    updateMissingIds(taskRequest);
                }
            }
            appendLog((stopRequested ? "Stopped" : "Completed") + " " + type + " task.");
        } catch (Exception e) {
            logger.error("IR task failed", e);
            synchronized (lock) {
                status.running = false;
                status.state = stopRequested ? "stopped" : "failed";
                status.finishedAt = Instant.now().toString();
                status.exitCode = stopRequested ? 130 : 1;
                status.error = e.getMessage();
            }
            appendLog("Task failed: " + e.getMessage());
        } finally {
            closeConnection();
        }
    }

    private SerialConnection openConnection(String serialPort) throws IOException {
        SerialParameters params = new SerialParameters();
        params.setPortName(serialPort);
        params.setEncoding(Modbus.SERIAL_ENCODING_RTU);
        params.setBaudRate(BAUD_RATE);
        params.setDatabits(DATA_BITS);
        params.setStopbits(STOP_BITS);
        params.setParity(PARITY);
        params.setEcho(false);
        params.setFlowControlIn(AbstractSerialConnection.FLOW_CONTROL_DISABLED);
        params.setFlowControlOut(AbstractSerialConnection.FLOW_CONTROL_DISABLED);

        SerialConnection connection = new SerialConnection(params);
        connection.open();
        connection.setTimeout(TIMEOUT_MS);
        appendLog("Opened " + serialPort + " at 9600 N 8 1, timeout " + TIMEOUT_MS + " ms.");
        return connection;
    }

    private void runScan(IrTaskRequest request, SerialConnection connection) {
        for (int id = request.startId; id <= request.endId && !stopRequested; id++) {
            setCurrentId(id);
            try {
                int irValue = readIrValue(connection, id);
                addActiveId(id);
                appendLog("[ACTIVE] ID " + id + " responded. IR register = " + irValue);
                if (irValue > 0) {
                    addActivatedId(id);
                    appendLog("[ID " + id + "] Already activated by IR value " + irValue + ".");
                }
                else {
                    addNotActivatedId(id);
                    appendLog("[ID " + id + "] Active but IR is not activated yet.");
                }
            } catch (Exception e) {
                appendLog("[----] ID " + id + " no response: " + e.getMessage());
            }
            sleep(100);
        }
    }

    private void runActivate(IrTaskRequest request, SerialConnection connection) {
        for (int id = request.startId; id <= request.endId && !stopRequested; id++) {
            setCurrentId(id);

            Integer existingIr = readIrValueQuietly(connection, id);
            if (existingIr != null && existingIr > 0) {
                addActivatedId(id);
                appendLog("[ID " + id + "] Already activated. IR value " + existingIr + " > 0. Skipping.");
                setNextId(id + 1);
                continue;
            }

            appendLog("[ID " + id + "] Activating IR.");
            if (writeActivateRegisterWithRetry(connection, id)) {
                appendLog("[ID " + id + "] Write 0xF0F0 successful.");
            }
            else {
                appendLog("[ID " + id + "] Write failed after " + WRITE_RETRIES + " attempts"
                        + ". Continue polling because the write frame may still have reached the module.");
            }

            long deadline = System.currentTimeMillis() + 30000L;
            boolean activated = false;
            while (System.currentTimeMillis() < deadline && !stopRequested) {
                Integer irValue = readIrValueQuietly(connection, id);
                if (irValue != null) {
                    appendLog("[ID " + id + "] Polling... IR register: " + irValue);
                    if (irValue > 0) {
                        addActivatedId(id);
                        appendLog("[ID " + id + "] CONDITION MET (> 0).");
                        activated = true;
                        break;
                    }
                }
                sleep(1000);
            }

            if (!activated && !stopRequested) {
                appendLog("[ID " + id + "] 30s timeout reached.");
            }
            setNextId(id + 1);

            if (id < request.endId && !stopRequested) {
                appendLog("[ID " + id + "] Session finished. Resting 20 seconds.");
                sleep(20000);
            }
        }
    }

    private Integer readIrValueQuietly(SerialConnection connection, int slaveId) {
        try {
            return readIrValue(connection, slaveId);
        } catch (Exception e) {
            appendLog("[ID " + slaveId + "] IR read failed: " + e.getMessage());
            return null;
        }
    }

    private int readIrValue(SerialConnection connection, int slaveId) throws ModbusException {
        ReadMultipleRegistersResponse response = readRegisters(connection, slaveId, IR_REGISTER_START, IR_REGISTER_COUNT);
        return response.getRegisterValue(IR_VALUE_INDEX);
    }

    private ReadMultipleRegistersResponse readRegisters(SerialConnection connection, int slaveId, int start, int count)
            throws ModbusException {
        ReadMultipleRegistersRequest request = new ReadMultipleRegistersRequest(start, count);
        request.setUnitID(slaveId);
        request.setHeadless();

        ModbusSerialTransaction transaction = new ModbusSerialTransaction(connection);
        transaction.setSerialConnection(connection);
        transaction.setRetries(0);
        transaction.setRequest(request);
        transaction.execute();
        return (ReadMultipleRegistersResponse) transaction.getResponse();
    }

    private void writeActivateRegister(SerialConnection connection, int slaveId) throws ModbusException {
        WriteSingleRegisterRequest request = new WriteSingleRegisterRequest(ACTIVATE_REGISTER, new SimpleRegister(ACTIVATE_VALUE));
        request.setUnitID(slaveId);

        ModbusSerialTransaction transaction = new ModbusSerialTransaction(connection);
        transaction.setSerialConnection(connection);
        transaction.setRetries(0);
        transaction.setRequest(request);
        connection.setTimeout(WRITE_TIMEOUT_MS);
        try {
            transaction.execute();
        } finally {
            connection.setTimeout(TIMEOUT_MS);
        }
    }

    private boolean writeActivateRegisterWithRetry(SerialConnection connection, int slaveId) {
        for (int attempt = 1; attempt <= WRITE_RETRIES && !stopRequested; attempt++) {
            try {
                writeActivateRegister(connection, slaveId);
                return true;
            } catch (Exception e) {
                appendLog("[ID " + slaveId + "] Write attempt " + attempt + "/" + WRITE_RETRIES + " failed: "
                        + e.getMessage());
                if (attempt < WRITE_RETRIES) {
                    sleep(WRITE_RETRY_DELAY_MS);
                }
            }
        }
        return false;
    }

    private void setCurrentId(int id) {
        synchronized (lock) {
            status.currentId = id;
            status.nextId = id;
        }
    }

    private void setNextId(int id) {
        synchronized (lock) {
            status.nextId = id;
        }
    }

    private void addActiveId(int id) {
        synchronized (lock) {
            if (!status.activeIds.contains(id)) {
                status.activeIds.add(id);
            }
        }
    }

    private void addActivatedId(int id) {
        synchronized (lock) {
            if (!status.activatedIds.contains(id)) {
                status.activatedIds.add(id);
            }
        }
    }

    private void addNotActivatedId(int id) {
        synchronized (lock) {
            if (!status.notActivatedIds.contains(id)) {
                status.notActivatedIds.add(id);
            }
        }
    }

    private void updateMissingIds(IrTaskRequest request) {
        status.missingIds.clear();
        for (int id = request.startId; id <= request.endId; id++) {
            if (!status.activeIds.contains(id)) {
                status.missingIds.add(id);
            }
        }
    }

    private void appendLog(String line) {
        synchronized (lock) {
            status.output.add(Instant.now().toString() + " " + line);
            while (status.output.size() > MAX_LOG_LINES) {
                status.output.remove(0);
            }
        }
    }

    private void stopCurrentTask(String reason) {
        stopRequested = true;
        appendLog(reason);
        closeConnection();
    }

    private void closeConnection() {
        SerialConnection connection = currentConnection;
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                logger.debug("Unable to close IR serial connection", e);
            } finally {
                currentConnection = null;
            }
        }
    }

    private void sleep(long millis) {
        long end = System.currentTimeMillis() + millis;
        while (!stopRequested && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(Math.min(200, end - System.currentTimeMillis()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopRequested = true;
                return;
            }
        }
    }

    private IrTaskStatus snapshot() {
        synchronized (lock) {
            return status.copy();
        }
    }

    private void sendStatus(HttpServletResponse response, int statusCode, String message) throws ServletException, IOException {
        ToJson json = new ToJson();
        CommonResponse.toSuccessResult(message, json);
        json.addObject(snapshot());
        response.setStatus(statusCode);
        sendJson(json, response);
    }

    private void sendError(HttpServletResponse response, int statusCode, String message) throws ServletException, IOException {
        ToJson json = new ToJson();
        CommonResponse.toExceptionResult(message, json);
        json.addObject(snapshot());
        response.setStatus(statusCode);
        sendJson(json, response);
    }

    private static class IrTaskRequest {
        String serialPort;
        int startId;
        int endId;
    }

    private static class IrTaskStatus {
        String type;
        String state;
        String serialPort;
        int startId;
        int endId;
        int currentId;
        int nextId;
        boolean running;
        Integer exitCode;
        String startedAt;
        String finishedAt;
        String error;
        List<Integer> activeIds = new ArrayList<>();
        List<Integer> missingIds = new ArrayList<>();
        List<Integer> activatedIds = new ArrayList<>();
        List<Integer> notActivatedIds = new ArrayList<>();
        List<String> output = new ArrayList<>();

        static IrTaskStatus idle() {
            IrTaskStatus status = new IrTaskStatus();
            status.state = "idle";
            return status;
        }

        static IrTaskStatus started(String type, IrTaskRequest request) {
            IrTaskStatus status = new IrTaskStatus();
            status.type = type;
            status.state = "running";
            status.serialPort = request.serialPort;
            status.startId = request.startId;
            status.endId = request.endId;
            status.currentId = request.startId;
            status.nextId = request.startId;
            status.running = true;
            status.startedAt = Instant.now().toString();
            return status;
        }

        IrTaskStatus copy() {
            IrTaskStatus copy = new IrTaskStatus();
            copy.type = type;
            copy.state = state;
            copy.serialPort = serialPort;
            copy.startId = startId;
            copy.endId = endId;
            copy.currentId = currentId;
            copy.nextId = nextId;
            copy.running = running;
            copy.exitCode = exitCode;
            copy.startedAt = startedAt;
            copy.finishedAt = finishedAt;
            copy.error = error;
            copy.activeIds = new ArrayList<>(new LinkedHashSet<>(activeIds));
            copy.missingIds = new ArrayList<>(new LinkedHashSet<>(missingIds));
            copy.activatedIds = new ArrayList<>(new LinkedHashSet<>(activatedIds));
            copy.notActivatedIds = new ArrayList<>(new LinkedHashSet<>(notActivatedIds));
            copy.output = new ArrayList<>(output);
            return copy;
        }
    }
}
