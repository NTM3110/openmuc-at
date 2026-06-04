package org.openmuc.framework.server.restws.servlets;

import com.fazecast.jSerialComm.SerialPort;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class TcCalibrationServlet extends GenericServlet {

    private static final long serialVersionUID = -5109725021856753059L;
    private static final Logger logger = LoggerFactory.getLogger(TcCalibrationServlet.class);
    private static final Gson GSON = new Gson();

    private static final int BAUD_RATE = 9600;
    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = AbstractSerialConnection.ONE_STOP_BIT;
    private static final int PARITY = SerialPort.NO_PARITY;
    private static final int TIMEOUT_MS = 1000;
    private static final int WRITE_TIMEOUT_MS = 5000;
    private static final int WRITE_RETRIES = 3;
    private static final int SAMPLE_COUNT = 20;
    private static final int SAMPLE_INTERVAL_MS = 1000;
    private static final int MAX_LOG_LINES = 200;

    private static final int TC_SLAVE_ID = 206;
    private static final int MODULE_CHECK_REGISTER = 0x0000;
    private static final int CURRENT_REGISTER = 0x0003;
    private static final int RANGE_REGISTER = 0x000E;
    private static final int CALIBRATION_REGISTER = 0x000F;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Object lock = new Object();
    private volatile TcTaskStatus status = TcTaskStatus.idle();
    private volatile SerialConnection currentConnection;
    private volatile boolean stopRequested;

    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType(APPLICATION_JSON);
        sendStatus(response, HttpServletResponse.SC_OK, "TC calibration status");
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
            if ("read-range".equals(action)) {
                startTask("read-range", parseRequest(request, false), false);
                sendStatus(response, HttpServletResponse.SC_ACCEPTED, "TC range read started");
            }
            else if ("read-current".equals(action)) {
                startTask("read-current", parseRequest(request, false), false);
                sendStatus(response, HttpServletResponse.SC_ACCEPTED, "TC current read started");
            }
            else if ("calibrate".equals(action)) {
                startTask("calibrate", parseRequest(request, true), false);
                sendStatus(response, HttpServletResponse.SC_ACCEPTED, "TC calibration started");
            }
            else if ("stop".equals(action)) {
                stopCurrentTask("Stop requested by user");
                sendStatus(response, HttpServletResponse.SC_OK, "TC task stop requested");
            }
            else {
                sendError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid TC calibration action");
            }
        } catch (IllegalArgumentException e) {
            sendError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            sendError(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        } catch (Exception e) {
            logger.error("Unable to handle TC calibration request", e);
            sendError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    public void destroy() {
        stopCurrentTask("Servlet destroyed");
        executor.shutdownNow();
    }

    private TcTaskRequest parseRequest(HttpServletRequest request, boolean requireCalibration) throws IOException {
        String body = ServletLib.getJsonText(request);
        JsonObject json = body == null || body.trim().isEmpty() ? new JsonObject() : GSON.fromJson(body, JsonObject.class);

        String serialPort = getString(json, "serialPort", null);
        if (serialPort == null || serialPort.trim().isEmpty()) {
            throw new IllegalArgumentException("serialPort is required");
        }

        TcTaskRequest taskRequest = new TcTaskRequest();
        taskRequest.serialPort = serialPort.trim();
        taskRequest.slaveId = TC_SLAVE_ID;

        if (requireCalibration) {
            if (!json.has("calibration") || json.get("calibration").isJsonNull()) {
                throw new IllegalArgumentException("calibration is required");
            }
            int calibration = json.get("calibration").getAsInt();
            if (calibration < -32767 || calibration > 32767) {
                throw new IllegalArgumentException("calibration must be between -32767 and 32767");
            }
            taskRequest.calibrationSigned = calibration;
            taskRequest.calibrationRaw = encodeCalibration(calibration);
        }

        return taskRequest;
    }

    private String getString(JsonObject json, String key, String defaultValue) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : defaultValue;
    }

    private int encodeCalibration(int value) {
        if (value < 0) {
            return 0x8000 | Math.abs(value);
        }
        return value;
    }

    private int decodeTcSignedRaw(int raw) {
        int value = raw & 0xFFFF;
        int magnitude = value & 0x7FFF;
        return (value & 0x8000) != 0 ? -magnitude : magnitude;
    }

    private double decodeTcCurrentAmps(int raw) {
        return decodeTcSignedRaw(raw) / 10.0;
    }

    private void startTask(String type, TcTaskRequest taskRequest, boolean forceStopRunning) {
        synchronized (lock) {
            if (status.running) {
                if (!forceStopRunning) {
                    throw new IllegalStateException("TC task " + status.type + " is running");
                }
                stopCurrentTask("Stopped to start " + type);
            }
            stopRequested = false;
            status = TcTaskStatus.started(type, taskRequest);
        }

        executor.submit(() -> runTask(type, taskRequest));
    }

    private void runTask(String type, TcTaskRequest taskRequest) {
        appendLog("Starting " + type + " on " + taskRequest.serialPort + " for fixed TC module ID " + TC_SLAVE_ID);

        try {
            SerialConnection connection = openConnection(taskRequest.serialPort);
            currentConnection = connection;

            if ("read-range".equals(type)) {
                readAndStoreRange(connection, taskRequest.slaveId);
            }
            else if ("read-current".equals(type)) {
                readCurrentFor20Seconds(connection, taskRequest.slaveId);
            }
            else if ("calibrate".equals(type)) {
                verifyModule(connection, taskRequest.slaveId);
                readAndStoreRange(connection, taskRequest.slaveId);
                writeAndVerifyCalibration(connection, taskRequest);
                readCurrentFor20Seconds(connection, taskRequest.slaveId);
            }

            synchronized (lock) {
                status.running = false;
                status.finishedAt = Instant.now().toString();
                status.state = stopRequested ? "stopped" : "completed";
                status.exitCode = stopRequested ? 130 : 0;
            }
            appendLog((stopRequested ? "Stopped" : "Completed") + " " + type + " task.");
        } catch (Exception e) {
            logger.error("TC calibration task failed", e);
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

    private void verifyModule(SerialConnection connection, int slaveId) throws ModbusException {
        int moduleValue = readRegister(connection, slaveId, MODULE_CHECK_REGISTER);
        appendLog("[ID " + slaveId + "] Module check register 0x0000 = 0x" + toHex(moduleValue) + " (" + moduleValue + ")");
    }

    private void readAndStoreRange(SerialConnection connection, int slaveId) throws ModbusException {
        int range = readRegister(connection, slaveId, RANGE_REGISTER);
        synchronized (lock) {
            status.transformerRange = range;
        }
        appendLog("[ID " + slaveId + "] Transformer range register 400015 / 0x000E = 0x"
                + toHex(range) + " (" + range + ")");
    }

    private void writeAndVerifyCalibration(SerialConnection connection, TcTaskRequest taskRequest) throws ModbusException {
        synchronized (lock) {
            status.calibrationSigned = taskRequest.calibrationSigned;
            status.calibrationRaw = taskRequest.calibrationRaw;
        }
        appendLog("[ID " + taskRequest.slaveId + "] Requested signed calibration " + taskRequest.calibrationSigned
                + ", encoded raw 0x" + toHex(taskRequest.calibrationRaw) + " (" + taskRequest.calibrationRaw + ")");

        for (int attempt = 1; attempt <= WRITE_RETRIES && !stopRequested; attempt++) {
            appendLog("[ID " + taskRequest.slaveId + "] Write attempt " + attempt + "/" + WRITE_RETRIES
                    + ": register 400016 / 0x000F = 0x" + toHex(taskRequest.calibrationRaw));
            try {
                writeRegister(connection, taskRequest.slaveId, CALIBRATION_REGISTER, taskRequest.calibrationRaw);
                appendLog("[ID " + taskRequest.slaveId + "] Write returned normal confirmation.");
            } catch (Exception e) {
                appendLog("[ID " + taskRequest.slaveId + "] Write confirmation not received: " + e.getMessage());
                appendLog("[ID " + taskRequest.slaveId + "] Reading back register to determine whether write was processed.");
            }

            int readBack = readRegister(connection, taskRequest.slaveId, CALIBRATION_REGISTER);
            appendLog("[ID " + taskRequest.slaveId + "] Readback register 0x000F = 0x"
                    + toHex(readBack) + " (" + readBack + ")");
            if (readBack == taskRequest.calibrationRaw) {
                appendLog("[ID " + taskRequest.slaveId + "] Calibration write verified.");
                return;
            }

            appendLog("[ID " + taskRequest.slaveId + "] Readback does not match requested value 0x"
                    + toHex(taskRequest.calibrationRaw) + ".");
            if (attempt < WRITE_RETRIES) {
                sleep(1000);
            }
        }

        throw new ModbusException("Calibration setting failed after retries");
    }

    private void readCurrentFor20Seconds(SerialConnection connection, int slaveId) {
        appendLog("[ID " + slaveId + "] Reading current register 400004 / 0x0003 every 1 second for 20 seconds.");

        for (int sample = 1; sample <= SAMPLE_COUNT && !stopRequested; sample++) {
            try {
                int raw = readRegister(connection, slaveId, CURRENT_REGISTER);
                int signedRaw = decodeTcSignedRaw(raw);
                double currentAmps = decodeTcCurrentAmps(raw);
                synchronized (lock) {
                    if (status.firstCurrentSignedRaw == null) {
                        status.firstCurrentSignedRaw = signedRaw;
                    }
                    else if (status.firstCurrentSignedRaw.intValue() != signedRaw) {
                        status.currentChanged = true;
                    }
                    status.currentSampleIndex = sample;
                    status.successfulCurrentSamples++;
                    status.latestCurrentRaw = raw;
                    status.latestCurrentSignedRaw = signedRaw;
                    status.latestCurrentAmps = currentAmps;
                }
                appendLog("[ID " + slaveId + "] Current sample " + sample + "/20: raw 0x"
                        + toHex(raw) + " (" + raw + "), decoded " + signedRaw + " = " + currentAmps + " A");
            } catch (Exception e) {
                appendLog("[ID " + slaveId + "] Current sample " + sample + "/20 failed: " + e.getMessage());
            }

            if (sample < SAMPLE_COUNT) {
                sleep(SAMPLE_INTERVAL_MS);
            }
        }
    }

    private int readRegister(SerialConnection connection, int slaveId, int registerAddress) throws ModbusException {
        ReadMultipleRegistersResponse response = readRegisters(connection, slaveId, registerAddress, 1);
        return response.getRegisterValue(0) & 0xFFFF;
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

    private void writeRegister(SerialConnection connection, int slaveId, int registerAddress, int value)
            throws ModbusException {
        WriteSingleRegisterRequest request = new WriteSingleRegisterRequest(registerAddress, new SimpleRegister(value));
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
                logger.debug("Unable to close TC serial connection", e);
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

    private String toHex(int value) {
        return String.format("%04X", value & 0xFFFF);
    }

    private TcTaskStatus snapshot() {
        synchronized (lock) {
            return status.copy();
        }
    }

    private void sendStatus(HttpServletResponse response, int statusCode, String message)
            throws ServletException, IOException {
        ToJson json = new ToJson();
        CommonResponse.toSuccessResult(message, json);
        json.addObject(snapshot());
        response.setStatus(statusCode);
        sendJson(json, response);
    }

    private void sendError(HttpServletResponse response, int statusCode, String message)
            throws ServletException, IOException {
        ToJson json = new ToJson();
        CommonResponse.toExceptionResult(message, json);
        json.addObject(snapshot());
        response.setStatus(statusCode);
        sendJson(json, response);
    }

    private static class TcTaskRequest {
        String serialPort;
        int slaveId;
        int calibrationSigned;
        int calibrationRaw;
    }

    private static class TcTaskStatus {
        String type;
        String state;
        String serialPort;
        int slaveId;
        boolean running;
        Integer exitCode;
        String startedAt;
        String finishedAt;
        String error;
        Integer transformerRange;
        Integer calibrationSigned;
        Integer calibrationRaw;
        Integer firstCurrentSignedRaw;
        Integer latestCurrentRaw;
        Integer latestCurrentSignedRaw;
        Double latestCurrentAmps;
        int currentSampleIndex;
        int successfulCurrentSamples;
        boolean currentChanged;
        List<String> output = new ArrayList<>();

        static TcTaskStatus idle() {
            TcTaskStatus status = new TcTaskStatus();
            status.state = "idle";
            status.slaveId = TC_SLAVE_ID;
            return status;
        }

        static TcTaskStatus started(String type, TcTaskRequest request) {
            TcTaskStatus status = new TcTaskStatus();
            status.type = type;
            status.state = "running";
            status.serialPort = request.serialPort;
            status.slaveId = request.slaveId;
            status.running = true;
            status.startedAt = Instant.now().toString();
            if ("calibrate".equals(type)) {
                status.calibrationSigned = request.calibrationSigned;
                status.calibrationRaw = request.calibrationRaw;
            }
            return status;
        }

        TcTaskStatus copy() {
            TcTaskStatus copy = new TcTaskStatus();
            copy.type = type;
            copy.state = state;
            copy.serialPort = serialPort;
            copy.slaveId = slaveId;
            copy.running = running;
            copy.exitCode = exitCode;
            copy.startedAt = startedAt;
            copy.finishedAt = finishedAt;
            copy.error = error;
            copy.transformerRange = transformerRange;
            copy.calibrationSigned = calibrationSigned;
            copy.calibrationRaw = calibrationRaw;
            copy.firstCurrentSignedRaw = firstCurrentSignedRaw;
            copy.latestCurrentRaw = latestCurrentRaw;
            copy.latestCurrentSignedRaw = latestCurrentSignedRaw;
            copy.latestCurrentAmps = latestCurrentAmps;
            copy.currentSampleIndex = currentSampleIndex;
            copy.successfulCurrentSamples = successfulCurrentSamples;
            copy.currentChanged = currentChanged;
            copy.output = new ArrayList<>(output);
            return copy;
        }
    }
}
