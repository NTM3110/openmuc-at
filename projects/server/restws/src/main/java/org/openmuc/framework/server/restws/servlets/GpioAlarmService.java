package org.openmuc.framework.server.restws.servlets;

import org.openmuc.framework.data.Record;
import org.openmuc.framework.data.Value;
import org.openmuc.framework.dataaccess.Channel;
import org.openmuc.framework.dataaccess.DataAccessService;
import org.openmuc.framework.lib.rest1.domain.model.LatestValue;
import org.openmuc.framework.lib.rest1.sql.LatestValueRepoImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class GpioAlarmService {

    private static final Logger logger = LoggerFactory.getLogger(GpioAlarmService.class);

    private static final int STRING_COUNT = 2;
    private static final int DEFAULT_ALARM_VALUE = 1;
    private static final int DEFAULT_DIRECTION_OUTPUT = 1;
    private static final long COMMAND_TIMEOUT_SECONDS = 3;
    private static final String SETTINGS_PREFIX = "gpio_alarm_";

    private final Map<Integer, GpioOutputState> states = new LinkedHashMap<>();

    public GpioAlarmService() {
        for (int stringIndex = 1; stringIndex <= STRING_COUNT; stringIndex++) {
            states.put(stringIndex, new GpioOutputState(stringIndex));
        }
    }

    public synchronized GpioAlarmStatus getStatus() {
        GpioAlarmStatus status = new GpioAlarmStatus();
        for (int stringIndex = 1; stringIndex <= STRING_COUNT; stringIndex++) {
            GpioOutputState state = states.get(stringIndex);
            state.alarmValue = getConfiguredAlarmValue(stringIndex);
            state.normalValue = invert(state.alarmValue);
            state.direction = directionName(getConfiguredDirectionValue(stringIndex));
            status.strings.put(String.valueOf(stringIndex), state.copy());
        }
        status.scriptPath = getScriptPath();
        return status;
    }

    public synchronized boolean saveSettings(Integer string1AlarmValue, Integer string2AlarmValue,
            Integer string1DirectionValue, Integer string2DirectionValue) {
        boolean ok = true;
        if (string1AlarmValue != null) {
            ok &= LatestValueRepoImpl.upsertDoubleValue(settingChannelId(1), normalizeBit(string1AlarmValue));
        }
        if (string2AlarmValue != null) {
            ok &= LatestValueRepoImpl.upsertDoubleValue(settingChannelId(2), normalizeBit(string2AlarmValue));
        }
        if (string1DirectionValue != null) {
            ok &= LatestValueRepoImpl.upsertDoubleValue(directionSettingChannelId(1), normalizeBit(string1DirectionValue));
        }
        if (string2DirectionValue != null) {
            ok &= LatestValueRepoImpl.upsertDoubleValue(directionSettingChannelId(2), normalizeBit(string2DirectionValue));
        }
        return ok;
    }

    public synchronized void updateOutputs(DataAccessService dataAccessService) {
        if (dataAccessService == null) {
            return;
        }

        for (int stringIndex = 1; stringIndex <= STRING_COUNT; stringIndex++) {
            GpioOutputState state = states.get(stringIndex);
            state.alarmValue = getConfiguredAlarmValue(stringIndex);
            state.normalValue = invert(state.alarmValue);
            state.direction = directionName(getConfiguredDirectionValue(stringIndex));
            AlarmSnapshot alarmSnapshot = readStringAlarm(dataAccessService, stringIndex);
            state.alarm = alarmSnapshot.alarm;
            state.alarmRegisterValue = alarmSnapshot.registerValue;
            state.alarmChannelId = alarmSnapshot.channelId;
            state.alarmReadError = alarmSnapshot.error;
            state.alarmReadAt = System.currentTimeMillis();

            int outputValue = state.alarm ? state.alarmValue : state.normalValue;
            state.desiredValue = isOutputDirection(state.direction) ? outputValue : null;

            if (isInputDirection(state.direction)) {
                if (state.lastDirection != null && state.lastDirection.equals(state.direction)) {
                    continue;
                }

                try {
                    runGpioScript(state.ioIndex, state.direction, null);
                    state.lastDirection = state.direction;
                    state.lastError = null;
                    state.updatedAt = System.currentTimeMillis();
                } catch (Exception e) {
                    state.lastError = e.getMessage();
                    state.updatedAt = System.currentTimeMillis();
                    // logger.warn("GPIO direction update failed for string {}: {}", stringIndex, e.toString());
                }
                continue;
            }

            if (state.lastDirection != null && state.lastDirection.equals(state.direction)
                    && state.lastWrittenValue != null && state.lastWrittenValue == outputValue) {
                continue;
            }

            try {
                runGpioScript(state.ioIndex, state.direction, outputValue);
                state.lastDirection = state.direction;
                state.lastWrittenValue = outputValue;
                state.lastError = null;
                state.updatedAt = System.currentTimeMillis();
            } catch (Exception e) {
                state.lastError = e.getMessage();
                state.updatedAt = System.currentTimeMillis();
                logger.warn("GPIO alarm output update failed for string {}: {}", stringIndex, e.toString());
            }
        }
    }

    private AlarmSnapshot readStringAlarm(DataAccessService dataAccessService, int stringIndex) {
        String channelId = "str" + stringIndex + "_string_alarm";
        Channel channel = dataAccessService.getChannel(channelId);
        if (channel == null || channel.getLatestRecord() == null || channel.getLatestRecord().getValue() == null) {
            return new AlarmSnapshot(channelId, false, null, "No latest value for " + channelId);
        }

        Double registerValue = valueToDouble(channel.getLatestRecord().getValue());
        if (registerValue == null) {
            return new AlarmSnapshot(channelId, false, null, "Cannot parse " + channelId);
        }
        return new AlarmSnapshot(channelId, registerValue > 0, registerValue, null);
    }

    private Double valueToDouble(Value value) {
        try {
            return value.asDouble();
        } catch (Exception ignored) {
        }
        try {
            return (double) value.asFloat();
        } catch (Exception ignored) {
        }
        try {
            return (double) value.asLong();
        } catch (Exception ignored) {
        }
        try {
            return (double) value.asInt();
        } catch (Exception ignored) {
        }
        try {
            return (double) value.asShort();
        } catch (Exception ignored) {
        }
        try {
            return (double) value.asByte();
        } catch (Exception e) {
            return null;
        }
    }

    private int getConfiguredAlarmValue(int stringIndex) {
        LatestValue latestValue = LatestValueRepoImpl.findLatestValueByChannelId(settingChannelId(stringIndex));
        if (latestValue == null || latestValue.getValueDouble() == null) {
            return DEFAULT_ALARM_VALUE;
        }
        return normalizeBit(latestValue.getValueDouble().intValue());
    }

    private int getConfiguredDirectionValue(int stringIndex) {
        LatestValue latestValue = LatestValueRepoImpl.findLatestValueByChannelId(directionSettingChannelId(stringIndex));
        if (latestValue == null || latestValue.getValueDouble() == null) {
            return DEFAULT_DIRECTION_OUTPUT;
        }
        return normalizeBit(latestValue.getValueDouble().intValue());
    }

    private void runGpioScript(int ioIndex, String direction, Integer value) throws IOException, InterruptedException {
        String scriptPath = getScriptPath();
        ProcessBuilder builder;
        if (isInputDirection(direction)) {
            builder = new ProcessBuilder(scriptPath, String.valueOf(ioIndex), "input");
        } else {
            if (value == null) {
                throw new IOException("Missing GPIO output value");
            }
            builder = new ProcessBuilder(scriptPath, String.valueOf(ioIndex), "output", String.valueOf(value));
        }

        builder.redirectErrorStream(true);
        Process process = builder.start();
        boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String output = readProcessOutput(process);

        if (!finished) {
            process.destroyForcibly();
            throw new IOException("GPIO command timed out");
        }

        if (process.exitValue() != 0) {
            throw new IOException("GPIO command failed with exit " + process.exitValue() + ": " + output);
        }
    }

    private String readProcessOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) {
                    output.append('\n');
                }
                output.append(line);
            }
        }
        return output.toString();
    }

    private String getScriptPath() {
        String configuredPath = System.getenv("MAXIMUC_GPIO_SCRIPT");
        if (configuredPath != null && !configuredPath.trim().isEmpty()) {
            return configuredPath.trim();
        }
        File frameworkRelativeScript = new File("scripts/set_gpio_output.sh");
        if (frameworkRelativeScript.exists()) {
            return frameworkRelativeScript.getAbsolutePath();
        }
        return new File("framework/scripts/set_gpio_output.sh").getAbsolutePath();
    }

    private int normalizeBit(int value) {
        return value == 0 ? 0 : 1;
    }

    private int invert(int value) {
        return value == 0 ? 1 : 0;
    }

    private String directionName(int value) {
        return value == 0 ? "input" : "output";
    }

    private boolean isInputDirection(String direction) {
        return "input".equals(direction);
    }

    private boolean isOutputDirection(String direction) {
        return !isInputDirection(direction);
    }

    private String settingChannelId(int stringIndex) {
        return SETTINGS_PREFIX + "string" + stringIndex + "_alarm_value";
    }

    private String directionSettingChannelId(int stringIndex) {
        return SETTINGS_PREFIX + "string" + stringIndex + "_direction";
    }

    public static class GpioAlarmStatus {
        public String scriptPath;
        public Map<String, GpioOutputState> strings = new LinkedHashMap<>();
    }

    public static class GpioOutputState {
        public int stringIndex;
        public int ioIndex;
        public int gpioNumber;
        public int alarmValue;
        public int normalValue;
        public String direction = "output";
        public boolean alarm;
        public Double alarmRegisterValue;
        public String alarmChannelId;
        public String alarmReadError;
        public long alarmReadAt;
        public Integer desiredValue;
        public Integer lastWrittenValue;
        public String lastDirection;
        public String lastError;
        public long updatedAt;

        GpioOutputState(int stringIndex) {
            this.stringIndex = stringIndex;
            this.ioIndex = stringIndex - 1;
            this.gpioNumber = stringIndex == 1 ? 33 : 32;
            this.alarmValue = DEFAULT_ALARM_VALUE;
            this.normalValue = 0;
        }

        GpioOutputState copy() {
            GpioOutputState copy = new GpioOutputState(stringIndex);
            copy.ioIndex = ioIndex;
            copy.gpioNumber = gpioNumber;
            copy.alarmValue = alarmValue;
            copy.normalValue = normalValue;
            copy.direction = direction;
            copy.alarm = alarm;
            copy.alarmRegisterValue = alarmRegisterValue;
            copy.alarmChannelId = alarmChannelId;
            copy.alarmReadError = alarmReadError;
            copy.alarmReadAt = alarmReadAt;
            copy.desiredValue = desiredValue;
            copy.lastWrittenValue = lastWrittenValue;
            copy.lastDirection = lastDirection;
            copy.lastError = lastError;
            copy.updatedAt = updatedAt;
            return copy;
        }
    }

    private static class AlarmSnapshot {
        private final String channelId;
        private final boolean alarm;
        private final Double registerValue;
        private final String error;

        private AlarmSnapshot(String channelId, boolean alarm, Double registerValue, String error) {
            this.channelId = channelId;
            this.alarm = alarm;
            this.registerValue = registerValue;
            this.error = error;
        }
    }
}
