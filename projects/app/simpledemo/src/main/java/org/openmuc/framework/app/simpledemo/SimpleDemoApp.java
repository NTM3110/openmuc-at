/*
 * This file is part of OpenMUC.
 * For more information visit http://www.openmuc.org
 *
 * You are free to use code of this sample file in any
 * way you like and without any restrictions.
 *
 */
package org.openmuc.framework.app.simpledemo;

import org.openmuc.framework.data.Record;
import org.openmuc.framework.data.StringValue;
import org.openmuc.framework.data.DoubleValue;
import org.openmuc.framework.data.Flag;
import org.openmuc.framework.data.IntValue;
import org.openmuc.framework.data.ShortValue;
import org.openmuc.framework.dataaccess.Channel;
import org.openmuc.framework.dataaccess.DataAccessService;
import org.openmuc.framework.dataaccess.DataLoggerNotAvailableException;
import org.openmuc.framework.dataaccess.RecordListener;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.File;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.nio.file.*;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.*;

@Component(service = {})
public final class SimpleDemoApp {
	private static final Logger logger = LoggerFactory.getLogger(SimpleDemoApp.class);
	private static final String APP_NAME = "ATEnergy BMS App";
	private static final String[] OPTIONS = { "R", "V", "T", "I", "SOC", "SOH" };
	private static final String CSV_LOG_DIR = "./log/csv";
	private static final DateTimeFormatter FILENAME_FORMATTER = DateTimeFormatter.ofPattern("ddMMyy");
	private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final ZoneId LOCAL_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
	private static final List<String> STRING_CHANNEL_TEMPLATES = Arrays.asList(
        "str%d_cell_qty",
        "str%d_Cnominal",
        "str%d_string_name",
        "str%d_cell_brand",
        "str%d_cell_model",
        "str%d_Vcutoff",
		"str%d_Vfloat",
        "str%d_R_new",
		"str%d_alarm_high_rst",
		"str%d_alarm_high_temp",
		"str%d_alarm_low_voltage",
		"str%d_alarm_high_voltage",
        "str%d_serial_port_id"
);
	private static final int DATA_OPTION_NUM = 6;
	private static final double SOC_SOH_CHANNEL_SCALE = 100.0;
	private static final double VOLTAGE_CHANNEL_SCALE = 1000.0;
	private static final double TEMPERATURE_CHANNEL_SCALE = 10.0;
	private static final double RESISTANCE_CHANNEL_SCALE = 1.0;
	private static final DecimalFormatSymbols DFS = DecimalFormatSymbols.getInstance(Locale.US);
	private static final DecimalFormat DF = new DecimalFormat("#0.000", DFS);
	private static final String[] IEC60870_DEMO_CHANNEL_IDS = {
			"iec60870_demo_me_nb_100",
			"iec60870_demo_me_nb_101",
			"iec60870_demo_me_nb_102",
			"iec60870_demo_sp_104",
			"iec60870_demo_sp_105",
			"iec60870_demo_me_nb_110"
	};
	private static final long IEC60870_DEMO_LOG_INTERVAL_MS = 5000L;

	List<String> latestSaveChannelNames = new ArrayList<>();
	private final Map<String, RecordListener> listeners = new HashMap<>();

	boolean isRestored = false;
	private int stringNumber;
	private int stringNumber_1;
	private int cellNumber = 1;
	private boolean isFirstSoC = true;
	private boolean isCellNumberChanged = false;
	private boolean isUpdatedOverall = false;

	private boolean isInitPowerCells = false;
	private boolean[] isInitCellDimensions;
	private boolean isFirstInitAllDimension = false;
	private boolean isFirstInitAllVariables = false;
	private boolean isSetChannel = false;
	private boolean[][] isInitSoC0;
	private boolean[] isSetSoCEngine;

	private int[] cellNumbers;
	private int maxCellNumber = 0;
	private PowerCell[][] powerCells;
	private Channel[][][] channels;
	private Timer updateTimer;
    private Timer updateDbTimer;
	private Timer csvLoggerTimer;
	private Timer iec60870DemoLoggerTimer;
	private SoCEngine socEngine;
	private SoCEngine[] socEngines;
	// private SoHEngine sohEngine;
	// maps internal index (0..stringNumber_1-1) to actual "strX" number
	private int[] stringIds = new int[0];

	private Record getLatestRecord(String channelId) {
		Channel channel = dataAccessService.getChannel(channelId);
		if (channel == null) {
			logger.warn("Channel {} does not exist yet, skipping this cycle.", channelId);
			return null;
		}
		return channel.getLatestRecord();
	}

	private boolean setLatestRecord(String channelId, Record record) {
		Channel channel = dataAccessService.getChannel(channelId);
		if (channel == null) {
			logger.warn("Channel {} does not exist yet, skipping this cycle.", channelId);
			return false;
		}
		channel.setLatestRecord(record);
		return true;
	}

	@Reference
	private DataAccessService dataAccessService;

	@Activate
	private void activate() {
		logger.info("Activating {}", APP_NAME);
        initiate();
        initDbUpdateTimer();
		initCsvLoggerTimer();
		initIec60870DemoLoggerTimer();
	}

	@Deactivate
	private void deactivate() {
		// logger.info("Deactivating {}", APP_NAME);
		if (updateTimer != null) {
			updateTimer.cancel();
			updateTimer.purge();
		}
        if (updateDbTimer != null) {
            updateDbTimer.cancel();
            updateDbTimer.purge();
        }
		if (csvLoggerTimer != null) {
			csvLoggerTimer.cancel();
			csvLoggerTimer.purge();
		}
		if (iec60870DemoLoggerTimer != null) {
			iec60870DemoLoggerTimer.cancel();
			iec60870DemoLoggerTimer.purge();
		}
	}

	private void initiatePowerCells() {
		for (int i = 0; i < stringNumber_1; i++) {
			for (int j = 0; j < maxCellNumber; j++) {
				powerCells[i][j] = new PowerCell();
			}
		}
	}

	/**
	 * Detects all existing string IDs from channel IDs.
	 * Works with IDs like "str2_cell1_V", "str10_Cnominal", "str3_string_SOC", etc.
	 */

    private List<String> detectChannelIdWithCurrentAndAmbientTempAndStringSOCSOH(List<String> allChannelIds){
        List<String> channelIds = new ArrayList<>(List.of());
        for (String id : allChannelIds){
            if ((id.startsWith("str") && id.endsWith("total_I")) || (id.startsWith("str") && id.endsWith("ambient_T")) || (id.startsWith("str") && id.endsWith("string_SOC")) || (id.startsWith("str") && id.endsWith("string_SOH"))) {
                channelIds.add(id);
            }
        }
        return  channelIds;
    }

	private int[] detectStringIdsWithCellQty(List<String> allChannelIds) {
		Set<Integer> ids = new TreeSet<>(); // sorted, unique

		for (String id : allChannelIds) {
			if (!(id.startsWith("str") && id.endsWith("cell_qty"))) {
				continue;
			}
			// logger.info("Detect = {}", id);
			// extract digits immediately after "str"
			int start = 3;
			int end = start;
			while (end < id.length() && Character.isDigit(id.charAt(end))) {
				end++;
			}
			if (end > start) {
				try {
					int value = Integer.parseInt(id.substring(start, end));
					ids.add(value);
				} catch (NumberFormatException ignore) {
					// ignore malformed IDs like "strX_..."
				}
			}
		}

		return ids.stream().mapToInt(Integer::intValue).toArray();
	}

	private Channel[][][] initiateChannel() {

		// logger.info("Initiate Channel {}", APP_NAME);

		// stringNumber_1 = 2; //TODO: change back to get from dimension function
		// maxCellNumber = 2; //TODO: change back to get from dimension function

		// logger.info("DATA OPTION NUMBER {}", DATA_OPTION_NUM);
		if (stringNumber <= 0)
			return null;
		Channel[][][] channels = new Channel[stringNumber_1][cellNumber][DATA_OPTION_NUM];
		String[][][] array = new String[stringNumber_1][cellNumber][DATA_OPTION_NUM];

		// logger.info("Current string number is {} with maxCellNumber = {}",
		// stringNumber_1, maxCellNumber);
		for (int i = 0; i < stringNumber_1; i++) {
			int strId = stringIds[i];
			for (int j = 0; j < cellNumbers[i]; j++) {
				for (int k = 0; k < DATA_OPTION_NUM; k++) {
					if (k == 3) {
						array[i][j][k] = "str" + strId + "_total" + "_" + OPTIONS[k];
					} else {
						array[i][j][k] = "str" + strId + "_cell" + (j + 1) + "_" + OPTIONS[k];
					}
					channels[i][j][k] = dataAccessService.getChannel(array[i][j][k]);
					// logger.info("Get channels {} ----> {}", APP_NAME, array[i][j][k]);
				}
			}
		}
		return channels;
	}

	private void resetFlagInitStringSoC0(int stringIndex) {
		if (isInitSoC0 != null) {
			for (int j = 0; j < isInitSoC0[stringIndex].length; j++) {
				isInitSoC0[stringIndex][j] = false;
			}
		}
	}

	private int setSoCEngine(int stringNumber) {
		for (int i = 0; i < stringNumber; i++) {
			int strId = stringIds[i];
			try{
				String vCutoffChannelName = "str" + strId + "_Vcutoff";
				Record VcutoffRecord = getLatestRecord(vCutoffChannelName);
				if(VcutoffRecord == null || VcutoffRecord.getValue() == null){
					logger.warn("There is no record of channel str%d_Vcutoff", strId);
					return 0;
				}
				double vCutoff = VcutoffRecord.getValue().asDouble();

				String vFloatChannelName = "str" + strId + "_Vfloat";
				Record VfloatRecord = getLatestRecord(vFloatChannelName);
                if(VfloatRecord == null || VfloatRecord.getValue() == null){
                    logger.warn("There is no record of channel str%d_Vfloat", strId);
                    return 0;
                }
                double vFloat = VfloatRecord.getValue().asDouble();

				if(vCutoff < 0 || vFloat < 0) {
					// logger.warn("Vcutoff or Vfloat is not set properly yet for string {}. Skipping set SoC engine this cycle.", i+1);
					continue;
				}
				String CnominalChannelName = "str" + strId + "_Cnominal";
				double Cnominal = -1.0;
				Record CnominalRecord = getLatestRecord(CnominalChannelName);
				if (CnominalRecord == null || CnominalRecord.getValue() == null) {
					logger.warn("There is no record of channel {}", CnominalChannelName);
					return 0;
				}
				Cnominal = CnominalRecord.getValue().asDouble();
				if (Cnominal < 0) {
					// logger.warn("Cnominal is not set properly yet for string {}. Skipping set SoC
					// engine this cycle.", i+1);
					continue;
				}
				// logger.info("Old value: String {}: Cnominal: {}, Vcutoff: {}, Vfloat: {}",
				// i+1, socEngines[i]!=null?socEngines[i].getCnominal():"null",
				// socEngines[i]!=null?socEngines[i].getVcutoff():"null",
				// socEngines[i]!=null?socEngines[i].getVfloat():"null");
				// logger.info("New Value: String {}: Cnominal: {}, Vcutoff: {}, Vfloat: {}",
				// i+1, Cnominal, vCutoff, vFloat);
				boolean isSoCEngineChanged = false;
				if (isSetSoCEngine[i]) {
					if (socEngines[i].getVcutoff() != vCutoff) {
						socEngines[i].setVcutoff(vCutoff);
						// logger.info("Update Vcutoff for string {} to {}", i+1, vCutoff);
						isSoCEngineChanged = true;
					}
					if (socEngines[i].getCnominal() != Cnominal) {
						socEngines[i].setCnominal(Cnominal);
						// logger.info("Update Cnominal for string {} to {}", i+1, Cnominal);
						isSoCEngineChanged = true;
					}
					if (socEngines[i].getVfloat() != vFloat) {
						socEngines[i].setVfloat(vFloat);
						// logger.info("Update Vffloat for string {} to {}", i+1, vFloat);
						isSoCEngineChanged = true;
					}
					if (isSoCEngineChanged) {
						resetFlagInitStringSoC0(i);
						double VcutoffNew = socEngines[i].getVcutoff();
						double VfloatNew = socEngines[i].getVfloat();
						double CnominalNew = socEngines[i].getCnominal();
						socEngines[i] = new SoCEngine(CnominalNew, VcutoffNew, VfloatNew);
						// logger.info("Re-initialize SoC engine for string {} due to parameter
						// change.", i+1);
					}
				}
				if (!isSetSoCEngine[i]) {
					socEngines[i] = new SoCEngine(Cnominal, vCutoff, vFloat);
					isSetSoCEngine[i] = true;
					// logger.info("Set SoC engine for string {} with Cnominal: {}, Vcutoff: {},
					// Vfloat: {}", i+1, Cnominal, vCutoff, vFloat);
				}
			}catch(NullPointerException e) {
				logger.warn("There is no value yet with this channel at string {}: {}, skipping set SoC engine this cycle.", i+1, e.getMessage());
				return 0;
			}
		}
		return 1;
	}

	private void setLatestSoC(PowerCell powerCell, double deltaT, boolean isInitSoC0, int stringIndex) {
		if (!isInitSoC0) {
			double firstSoC = socEngines[stringIndex].initialSoCFromVoltage(powerCell.getVoltage());
			// logger.info("Calculate the first SoC: voltage {}--------> firstSoc: {}",
			// powerCell.getVoltage(), firstSoC);
			powerCell.setSoc(firstSoC * 100);
			// logger.info("First SoC ----> {}", firstSoC*100);
		} else {
			double currentSoC = socEngines[stringIndex].updatedSoCEKF(powerCell.getVoltage(), powerCell.getCurrent(),
					powerCell.getTemp(), deltaT);
			powerCell.setSoc(currentSoC * 100);
			// logger.info("SetLatestSoC ----> currentSoC: {}", currentSoC*100);
		}
	}

	private void setLatestSoH(PowerCell powerCell, double rNew) {
		double currentSoH = SoHEngine.updatedSoHRegular(powerCell.getResistance(), rNew);
		powerCell.setSoh(currentSoH*100);
		// logger.info("SetLatestSoH ----> currentSoH: {}", currentSoH*100);
	}

	private boolean hasValidValue(Record record) {
		return record != null && record.getFlag() == Flag.VALID && record.getValue() != null;
	}

	private double decodeCurrentAmps(double rawValue) {
		int rawWord = ((int) rawValue) & 0xFFFF;
		// The current sensor uses bit 15 for direction and bits 0-14 for 0.1 A magnitude.
		double magnitudeAmps = (rawWord & 0x7FFF) / 10.0;
		return (rawWord & 0x8000) != 0 ? -magnitudeAmps : magnitudeAmps;
	}

	private void calculateSoCSoH() {
		for (int i = 0; i < stringNumber; i++) {
			for (int j = 0; j < cellNumbers[i]; j++) {
				final int si = i; // final copies for capture
				final int sj = j;
				// try{
				if(channels[si][sj][3] == null || 
					channels[si][sj][1] == null ||
					channels[si][sj][2] == null ||
					channels[si][sj][0] == null) {
					// logger.warn("----------------- MODBUS: There is no channel yet at string {} cell {} --------------", si+1, sj+1);
					channels = initiateChannel();
					continue;
				}
				if(channels[si][sj][4] == null || channels[si][sj][5] == null) {
					channels = initiateChannel();
					continue;
				}
				Record currentRecord = channels[si][sj][3].getLatestRecord();
				Record voltageRecord = channels[si][sj][1].getLatestRecord();
				Record tempRecord = channels[si][sj][2].getLatestRecord();
				Record resistanceRecord = channels[si][sj][0].getLatestRecord();
				if(!hasValidValue(voltageRecord) || !hasValidValue(tempRecord) || !hasValidValue(resistanceRecord)) {
					// logger.warn("----------------- MODBUS: There is no value yet with this channel at string {} cell {} --------------", si+1, sj+1);
					clearCellMeasurements(powerCells[si][sj]);
					continue;
				}
				
				double current = hasValidValue(currentRecord) ?
						decodeCurrentAmps(currentRecord.getValue().asDouble()) : 0;
				// logger.info("Value of of {}: -----> {}",channels[si][sj][3].getId(), current);
				double voltage = voltageRecord.getValue().asDouble() / 1000;
				double temp = tempRecord.getValue().asDouble() / 10;
				// logger.info("OLD value of voltage of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getVoltage());
				// logger.info("NEW value of voltage of string {}_cell{}: -----> {}",si+1, sj+1, voltage);
				double resistance = resistanceRecord.getValue().asDouble();
				// logger.info("Value of of {}: -----> {}",channels[si][sj][0].getId(), resistance);

				powerCells[si][sj].setCurrent(current);
				// logger.info("I of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getCurrent());
				powerCells[si][sj].setVoltage(voltage);
				// logger.info("V of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getVoltage());
				powerCells[si][sj].setTemp(temp);
				// logger.info("T of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getTemp());
				powerCells[si][sj].setResistance(resistance);
				
				double rNew = 1450.0;
				try {
					Channel rNewChannel = dataAccessService.getChannel("str" + stringIds[si] + "_R_new");
					if (rNewChannel != null && rNewChannel.getLatestRecord() != null && rNewChannel.getLatestRecord().getValue() != null) {
						rNew = rNewChannel.getLatestRecord().getValue().asDouble();
						if (rNew <= 0) rNew = 1450.0;
					}
				} catch (Exception e) {
					logger.debug("Could not read R_new for string {}: {}", stringIds[si], e.toString());
				}

				setLatestSoC(powerCells[si][sj], 1, isInitSoC0[si][sj], si);
				if(isInitSoC0[si][sj] == false) {
					isInitSoC0[si][sj] = true;
				}
				long now = System.currentTimeMillis();
				// logger.info("SoC of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getSoc());
				DoubleValue doubleSoCValue = new DoubleValue(scaleSocSohForChannel(powerCells[si][sj].getSoc()));
				Record socRecord = new Record(doubleSoCValue, now, Flag.VALID);
				channels[si][sj][4].setLatestRecord(socRecord);

                setLatestSoH(powerCells[si][sj], rNew);
				DoubleValue doubleSoHValue = new DoubleValue(scaleSocSohForChannel(powerCells[si][sj].getSoh()));
				Record sohRecord = new Record(doubleSoHValue, now, Flag.VALID);
				channels[si][sj][5].setLatestRecord(sohRecord);
				// }catch(NullPointerException e) {
				// logger.warn("----------------- MODBUS: There is no value yet with this
				// channel at string {} cell {} --------------", si+1, sj+1);
				// }
			}
		}
	}

	private void clearCellMeasurements(PowerCell powerCell) {
		powerCell.setCurrent(0);
		powerCell.setVoltage(0);
		powerCell.setTemp(0);
		powerCell.setResistance(0);
		powerCell.setSoc(0);
		powerCell.setSoh(0);
	}

	private void updateLatestSaveChannelNames(int[] stringIds) {
		latestSaveChannelNames.clear();

		// Add fixed channels
		// latestSaveChannelNames.add("soh_process_status");
		latestSaveChannelNames.add("dev_serial_comm_number");
		latestSaveChannelNames.add("dev_serial_comm_0");
		latestSaveChannelNames.add("dev_serial_comm_1");
		latestSaveChannelNames.add("dev_serial_comm_2");
		latestSaveChannelNames.add("site_name_1");
		latestSaveChannelNames.add("account_1_username");
		latestSaveChannelNames.add("account_1_password");

		// Add channels for each string
		for (int strId : stringIds) {
			for (String template : STRING_CHANNEL_TEMPLATES) {
				latestSaveChannelNames.add(String.format(template, strId));
			}
		}

		// logger.info("Updated latestSaveChannelNames: {}", latestSaveChannelNames);
	}

	private void setFirstOverallStringValue(int stringCount) {
		for (int i = 0; i < stringCount; i++) {
			for (String template : STRING_CHANNEL_TEMPLATES) {
				try {
					String stringNameChannel = String.format(template, i + 1);
					// logger.info("First overall string name for string {}: {}", i+1,
					// stringNameChannel);
					Record record = dataAccessService.getChannel(stringNameChannel).getLatestRecord();
					// logger.info("First overall string value for string {}: {}", i+1,
					// record.getValue().toString());

					if (record.getFlag() != Flag.VALID) {
						dataAccessService.getChannel(stringNameChannel).setLatestRecord(record);
					}
				} catch (NullPointerException e) {
					logger.warn(
							"There is no value yet with this channel at string {}: {}, skipping set first overall string name this cycle.",
							i + 1, e.getMessage());
				}
			}
		}
	}

	private void checkInitCellDimensions() {
		if (!isFirstInitAllDimension) {
			isFirstInitAllDimension = true;
			// for(int i = 0; i < stringNumber_1; i++) {
			// if(!isInitCellDimensions[i]) {
			// isFirstInitAllDimension = false;
			// }
			// }
		}
		if (isFirstInitAllDimension) {
			if (!isFirstInitAllVariables) {
				logger.info("Initializing isInitSoC0 array: string number: {}, cell number: {}", stringNumber_1,
						maxCellNumber);
				isInitSoC0 = new boolean[stringNumber_1][maxCellNumber];
				powerCells = new PowerCell[stringNumber_1][maxCellNumber];
				isSetSoCEngine = new boolean[stringNumber_1];
				logger.info("Created SoCEngines array.");
				socEngines = new SoCEngine[stringNumber_1];
				stringNumber = stringNumber_1;
				updateLatestSaveChannelNames(stringIds);
				applyListeners();
				cellNumber = maxCellNumber;
				channels = initiateChannel();
				initiatePowerCells();
				isFirstInitAllVariables = true;
			} else {
				// logger.info("Re-Initializing isInitSoC0 array if dimension changed: previous
				// string number: {}, previous cell number: {}; new string number: {}, new cell
				// number: {}", stringNumber, cellNumber, stringNumber_1, maxCellNumber);
				if (stringNumber != stringNumber_1 || cellNumber != maxCellNumber) {
					// logger.info("Dimensions changed, re-initializing isInitSoC0 array.");
					isInitSoC0 = new boolean[stringNumber_1][maxCellNumber];

					powerCells = new PowerCell[stringNumber_1][maxCellNumber];

					initiatePowerCells();

					isSetSoCEngine = new boolean[stringNumber_1];
					socEngines = new SoCEngine[stringNumber_1];

					updateLatestSaveChannelNames(stringIds);
					applyListeners();
					// LatestValuesRestorer.restoreAll(dataAccessService, latestSaveChannelNames);
					// setFirstOverallStringValue(stringNumber_1);
					// LatestValuesRestorer.restoreAll()
					stringNumber = stringNumber_1;
					cellNumber = maxCellNumber;
					channels = initiateChannel();
				} else if (isCellNumberChanged) {
					// logger.info("No dimension change detected.");
					// logger.info("Dimensions changed, re-initializing isInitSoC0 array.");
					isInitSoC0 = new boolean[stringNumber_1][maxCellNumber];

					powerCells = new PowerCell[stringNumber_1][maxCellNumber];

					initiatePowerCells();

					isSetSoCEngine = new boolean[stringNumber_1];
					socEngines = new SoCEngine[stringNumber_1];

					updateLatestSaveChannelNames(stringIds);
					applyListeners();
					// LatestValuesRestorer.restoreAll(dataAccessService, latestSaveChannelNames);
					// setFirstOverallStringValue(stringNumber_1);

					stringNumber = stringNumber_1;
					cellNumber = maxCellNumber;
					channels = initiateChannel();
					isCellNumberChanged = false;
				}
				// initiatePowerCells();
			}
			// logger.info("All cell dimensions are initialized: string number: {}, cell number: {}", stringNumber,
					// cellNumber);
		}
	}

	private void pushCalculatedDatatoChannels() {
		// logger.info("Pushing calculated data to channels. stringNumber: {}, cellNumber: {}");
		double strSOC[] = Helper.calculateStringSOC(stringNumber, cellNumber, powerCells);
		double strSOH[] = Helper.calculateStringSOH(stringNumber, cellNumber, powerCells);
		double maxVoltage[] = new double[stringNumber];
		int maxVoltageIndex[] = new int[stringNumber];
		double minVoltage[] = new double[stringNumber];
		int minVoltageIndex[] = new int[stringNumber];
		double minTemp[] = new double[stringNumber];
		int minTempIndex[] = new int[stringNumber];
		double maxTemp[] = new double[stringNumber];
		int maxTempIndex[] = new int[stringNumber];
		double maxResistance[] = new double[stringNumber];
		int maxResistanceIndex[] = new int[stringNumber];
		int minResistanceIndex[] = new int[stringNumber];
		double minResistance[] = new double[stringNumber];
		double averageResistance[] = new double[stringNumber];
		double averageTemp[] = new double[stringNumber];
		double averageVoltage[] = new double[stringNumber];
		double strVoltage[] = Helper.calculateStringVoltage(stringNumber, cellNumber, powerCells);

		for (int i = 0; i < stringNumber; i++) {
			try {
				int strId = stringIds[i];
				String base = "str" + strId + "_";
				Helper.Result result = Helper.getMaxVoltageBattery(i, cellNumbers, powerCells);
				maxVoltageIndex[i] = result.index;
				maxVoltage[i] = result.value;
				result = Helper.getMinVoltageBattery(i, cellNumbers, powerCells);
				minVoltageIndex[i] = result.index;
				minVoltage[i] = result.value;
				result = Helper.getMaxTemperatureBattery(i, cellNumbers, powerCells);
				maxTempIndex[i] = result.index;
				maxTemp[i] = result.value;
				result = Helper.getMinTemperatureBattery(i, cellNumbers, powerCells);
				minTempIndex[i] = result.index;
				minTemp[i] = result.value;
				result = Helper.getMaxResistanceBattery(i, cellNumbers, powerCells);
				maxResistanceIndex[i] = result.index;
				maxResistance[i] = result.value;
				result = Helper.getMinResistanceBattery(i, cellNumbers, powerCells);
				minResistanceIndex[i] = result.index;
				minResistance[i] = result.value;
				averageResistance[i] = Helper.getAverageResistanceBattery(i, cellNumbers, powerCells);
				averageTemp[i] = Helper.getAverageTemperatureBattery(i, cellNumbers, powerCells);
				averageVoltage[i] = Helper.getAverageVoltageBattery(i, cellNumbers, powerCells);
				long now = System.currentTimeMillis();
				short stringAlarm = calculateStringAlarm(strId, i);
				DoubleValue doubleStrVolValue = new DoubleValue(formatChannelValue(strVoltage[i]));
				Record record = new Record(doubleStrVolValue, now, Flag.VALID);
				if (!setLatestRecord(base + "string_vol", record)) {
					continue;
				}

				IntValue intMaxVoltageIndex = new IntValue(maxVoltageIndex[i]);
				record = new Record(intMaxVoltageIndex, now, Flag.VALID);
				if (!setLatestRecord(base + "max_voltage_cell_id", record)) {
					continue;
				}

				IntValue intMinVoltageIndex = new IntValue(minVoltageIndex[i]);
				record = new Record(intMinVoltageIndex, now, Flag.VALID);
				if (!setLatestRecord(base + "min_voltage_cell_id", record)) {
					continue;
				}

				IntValue intMaxTempIndex = new IntValue(maxTempIndex[i]);
				record = new Record(intMaxTempIndex, now, Flag.VALID);
				if (!setLatestRecord(base + "max_temp_cell_id", record)) {
					continue;
				}

				IntValue intMinTempIndex = new IntValue(minTempIndex[i]);
				record = new Record(intMinTempIndex, now, Flag.VALID);
				if (!setLatestRecord(base + "min_temp_cell_id", record)) {
					continue;
				}

				IntValue intMaxResistanceIndex = new IntValue(maxResistanceIndex[i]);
				record = new Record(intMaxResistanceIndex, now, Flag.VALID);
				if (!setLatestRecord(base + "max_rst_cell_id", record)) {
					continue;
				}

				IntValue intMinResistanceIndex = new IntValue(minResistanceIndex[i]);
				record = new Record(intMinResistanceIndex, now, Flag.VALID);
				if (!setLatestRecord(base + "min_rst_cell_id", record)) {
					continue;
				}

				DoubleValue doubleMaxVoltageValue = new DoubleValue(scaleForChannel(maxVoltage[i], VOLTAGE_CHANNEL_SCALE));
				record = new Record(doubleMaxVoltageValue, now, Flag.VALID);
				if (!setLatestRecord(base + "max_voltage_value", record)) {
					continue;
				}

				DoubleValue doubleMinVoltageValue = new DoubleValue(scaleForChannel(minVoltage[i], VOLTAGE_CHANNEL_SCALE));
				record = new Record(doubleMinVoltageValue, now, Flag.VALID);
				if (!setLatestRecord(base + "min_voltage_value", record)) {
					continue;
				}

				DoubleValue doubleMaxTempValue = new DoubleValue(scaleForChannel(maxTemp[i], TEMPERATURE_CHANNEL_SCALE));
				record = new Record(doubleMaxTempValue, now, Flag.VALID);
				if (!setLatestRecord(base + "max_temp_value", record)) {
					continue;
				}

				DoubleValue doubleMinTempValue = new DoubleValue(scaleForChannel(minTemp[i], TEMPERATURE_CHANNEL_SCALE));
				record = new Record(doubleMinTempValue, now, Flag.VALID);
				if (!setLatestRecord(base + "min_temp_value", record)) {
					continue;
				}

				DoubleValue doubleMaxResistanceValue = new DoubleValue(scaleForChannel(maxResistance[i], RESISTANCE_CHANNEL_SCALE));
				record = new Record(doubleMaxResistanceValue, now, Flag.VALID);
				if (!setLatestRecord(base + "max_rst_value", record)) {
					continue;
				}
				DoubleValue doubleMinResistanceValue = new DoubleValue(scaleForChannel(minResistance[i], RESISTANCE_CHANNEL_SCALE));
				record = new Record(doubleMinResistanceValue, now, Flag.VALID);
				if (!setLatestRecord(base + "min_rst_value", record)) {
					continue;
				}

				DoubleValue doubleAverageResistanceValue = new DoubleValue(
						scaleForChannel(averageResistance[i], RESISTANCE_CHANNEL_SCALE));
				record = new Record(doubleAverageResistanceValue, now, Flag.VALID);
				if (!setLatestRecord(base + "average_rst", record)) {
					continue;
				}

				DoubleValue doubleAverageTempValue = new DoubleValue(scaleForChannel(averageTemp[i], TEMPERATURE_CHANNEL_SCALE));
				record = new Record(doubleAverageTempValue, now, Flag.VALID);
				if (!setLatestRecord(base + "average_temp", record)) {
					continue;
				}

				DoubleValue doubleAverageVoltageValue = new DoubleValue(
						scaleForChannel(averageVoltage[i], VOLTAGE_CHANNEL_SCALE));
				record = new Record(doubleAverageVoltageValue, now, Flag.VALID);
				if (!setLatestRecord(base + "average_vol", record)) {
					continue;
				}

				DoubleValue doubleAverageRstValue = new DoubleValue(
						scaleForChannel(averageResistance[i], RESISTANCE_CHANNEL_SCALE));
				record = new Record(doubleAverageRstValue, now, Flag.VALID);
				if (!setLatestRecord(base + "average_rst", record)) {
					continue;
				}

				DoubleValue doubleStrSOHValue = new DoubleValue(scaleSocSohForChannel(strSOH[i]));
				record = new Record(doubleStrSOHValue, now, Flag.VALID);
				if (!setLatestRecord(base + "string_SOH", record)) {
					continue;
				}

				DoubleValue doubleStrSOCValue = new DoubleValue(scaleSocSohForChannel(strSOC[i]));
				record = new Record(doubleStrSOCValue, now, Flag.VALID);
				if (!setLatestRecord(base + "string_SOC", record)) {
					continue;
				}

				record = new Record(new ShortValue(stringAlarm), now, Flag.VALID);
				if (!setLatestRecord(base + "string_alarm", record)) {
					continue;
				}

			} catch (NullPointerException e) {
				logger.warn(
						"There is no value yet with this channel at string {}: {}, skipping push calculated data to channels this cycle.",
						i + 1, e.getMessage());
			}
		}

	}

	private double scaleSocSohForChannel(double value) {
		return scaleForChannel(value, SOC_SOH_CHANNEL_SCALE);
	}

	private double scaleForChannel(double value, double scale) {
		return formatChannelValue(value * scale);
	}

	private double formatChannelValue(double value) {
		return Double.parseDouble(DF.format(value));
	}

	private short calculateStringAlarm(int stringId, int stringIndex) {
		String base = "str" + stringId + "_";
		double highResistanceThreshold = readPositiveDoubleThreshold(base + "alarm_high_rst");
		double highTemperatureThreshold = readPositiveDoubleThreshold(base + "alarm_high_temp");
		double lowVoltageThreshold = readPositiveDoubleThreshold(base + "alarm_low_voltage");
		double highVoltageThreshold = readPositiveDoubleThreshold(base + "alarm_high_voltage");

		for (int j = 0; j < cellNumbers[stringIndex]; j++) {
			PowerCell cell = powerCells[stringIndex][j];
			if (isAboveThreshold(cell.getResistance(), highResistanceThreshold)
					|| isAboveThreshold(cell.getTemp(), highTemperatureThreshold)
					|| isBelowThreshold(cell.getVoltage(), lowVoltageThreshold)
					|| isAboveThreshold(cell.getVoltage(), highVoltageThreshold)) {
				return 1;
			}
		}
		return 0;
	}

	private double readPositiveDoubleThreshold(String channelId) {
		Double channelValue = readChannelDouble(channelId);
		if (channelValue != null && channelValue > 0) {
			return channelValue;
		}

		Double dbValue = LatestValuesDao.findDouble(channelId);
		if (dbValue != null && dbValue > 0) {
			return dbValue;
		}

		return 0;
	}

	private Double readChannelDouble(String channelId) {
		try {
			Channel channel = dataAccessService.getChannel(channelId);
			if (channel == null || channel.getLatestRecord() == null || channel.getLatestRecord().getValue() == null) {
				return null;
			}
			return channel.getLatestRecord().getValue().asDouble();
		} catch (Exception e) {
			logger.debug("Could not read alarm threshold {}: {}", channelId, e.toString());
			return null;
		}
	}

	private boolean isAboveThreshold(double value, double threshold) {
		return value > 0 && threshold > 0 && value >= threshold;
	}

	private boolean isBelowThreshold(double value, double threshold) {
		return value > 0 && threshold > 0 && value <= threshold;
	}

    private void initDbUpdateTimer(){
        logger.info("Init DB Update Timer");
        updateDbTimer = new Timer("Database update");
        int interval = SoHSchedulePrepare.getInterval();
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                // logger.info("---------------- << Pushing data to DB >> ------------------");
                List<String> allChannelId = dataAccessService.getAllIds();
                List<String> channelIds = detectChannelIdWithCurrentAndAmbientTempAndStringSOCSOH(allChannelId);
				for(String channelId : channelIds){
                    // logger.info("Channel found to publish to DB: {}", channelId);
                }
                SoHSchedulePrepare.implement(dataAccessService, channelIds);
            }
        };
        updateDbTimer.scheduleAtFixedRate(task, (long) interval * 1000, (long) interval * 1000);

    }
	
	private void initCsvLoggerTimer() {
		logger.info("Initializing CSV Logger Timer (60s interval)");
		csvLoggerTimer = new Timer("CSV Data Logger");
		TimerTask task = new TimerTask() {
			@Override
			public void run() {
				if (isFirstInitAllVariables && stringNumber > 0) {
					saveDataToCsv();
				}
			}
		};
		csvLoggerTimer.scheduleAtFixedRate(task, 60000, 60000);
	}

	private void initIec60870DemoLoggerTimer() {
		logger.info("Initializing IEC60870 demo logger timer (5s interval)");
		iec60870DemoLoggerTimer = new Timer("IEC60870 Demo Logger");
		TimerTask task = new TimerTask() {
			@Override
			public void run() {
				logIec60870DemoValues();
			}
		};
		iec60870DemoLoggerTimer.scheduleAtFixedRate(task, IEC60870_DEMO_LOG_INTERVAL_MS,
				IEC60870_DEMO_LOG_INTERVAL_MS);
	}

	private void logIec60870DemoValues() {
		StringBuilder message = new StringBuilder("IEC60870 demo values:");
		for (String channelId : IEC60870_DEMO_CHANNEL_IDS) {
			message.append(' ').append(channelId).append('=').append(readIec60870DemoValue(channelId));
		}
		logger.info(message.toString());
	}

	private String readIec60870DemoValue(String channelId) {
		Channel channel = dataAccessService.getChannel(channelId);
		if (channel == null) {
			return "missing-channel";
		}

		Record record = channel.getLatestRecord();
		if (record == null) {
			return "no-record";
		}
		if (record.getFlag() != Flag.VALID) {
			return "flag=" + record.getFlag();
		}
		if (record.getValue() == null) {
			return "no-value";
		}
		return record.getValue().toString();
	}

	private void saveDataToCsv() {
		ZonedDateTime now = ZonedDateTime.now(LOCAL_ZONE);
		String datePart = now.format(FILENAME_FORMATTER);
		File dir = new File(CSV_LOG_DIR);
		if (!dir.exists()) {
			if (!dir.mkdirs()) {
				logger.error("Could not create CSV log directory: {}. Permission denied or path invalid.", dir.getAbsolutePath());
				return;
			}
		}

		String timestamp = now.format(TIMESTAMP_FORMATTER);

		for (int i = 0; i < stringNumber; i++) {
			int strId = stringIds[i];
			String filename = datePart + "_str" + strId + ".csv";
			File file = new File(dir, filename);
			boolean isNewFile = !file.exists();

			try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
				if (isNewFile) {
					StringBuilder header = new StringBuilder("Timestamp,StringID,Vol,Curr,SoC,SoH,AvgT,AvgR,MaxV_ID,MaxV_Val,MinV_ID,MinV_Val,MaxT_ID,MaxT_Val,MinT_ID,MinT_Val,MaxR_ID,MaxR_Val,MinR_ID,MinR_Val");
					for (int j = 1; j <= maxCellNumber; j++) {
						header.append(",cell").append(j).append("_V");
						header.append(",cell").append(j).append("_T");
						header.append(",cell").append(j).append("_R");
						header.append(",cell").append(j).append("_SoC");
						header.append(",cell").append(j).append("_SoH");
					}
					writer.println(header.toString());
				}

				StringBuilder row = new StringBuilder();
				row.append(timestamp).append(",");
				row.append("str").append(strId).append(",");
				
				// String level data
				double strVol = Helper.calculateStringVoltage(stringNumber, cellNumber, powerCells)[i];
				double strSoc = Helper.calculateStringSOC(stringNumber, cellNumber, powerCells)[i];
				double strSoh = Helper.calculateStringSOH(stringNumber, cellNumber, powerCells)[i];
				double avgTemp = Helper.getAverageTemperatureBattery(i, cellNumbers, powerCells);
				double avgRst = Helper.getAverageResistanceBattery(i, cellNumbers, powerCells);
				double strCurr = powerCells[i][0].getCurrent(); // String current is same for all cells
				
				row.append(DF.format(strVol)).append(",");
				row.append(DF.format(strCurr)).append(",");
				row.append(DF.format(strSoc)).append(",");
				row.append(DF.format(strSoh)).append(",");
				row.append(DF.format(avgTemp)).append(",");
				row.append(DF.format(avgRst)).append(",");
				
				// Max/Min stats
				Helper.Result maxV = Helper.getMaxVoltageBattery(i, cellNumbers, powerCells);
				Helper.Result minV = Helper.getMinVoltageBattery(i, cellNumbers, powerCells);
				Helper.Result maxT = Helper.getMaxTemperatureBattery(i, cellNumbers, powerCells);
				Helper.Result minT = Helper.getMinTemperatureBattery(i, cellNumbers, powerCells);
				Helper.Result maxR = Helper.getMaxResistanceBattery(i, cellNumbers, powerCells);
				Helper.Result minR = Helper.getMinResistanceBattery(i, cellNumbers, powerCells);
				
				row.append(maxV.index).append(",").append(DF.format(maxV.value)).append(",");
				row.append(minV.index).append(",").append(DF.format(minV.value)).append(",");
				row.append(maxT.index).append(",").append(DF.format(maxT.value)).append(",");
				row.append(minT.index).append(",").append(DF.format(minT.value)).append(",");
				row.append(maxR.index).append(",").append(DF.format(maxR.value)).append(",");
				row.append(minR.index).append(",").append(DF.format(minR.value)).append(",");

				// Cell data
				for (int j = 0; j < cellNumbers[i]; j++) {
					PowerCell cell = powerCells[i][j];
					row.append(DF.format(cell.getVoltage())).append(",");
					row.append(DF.format(cell.getTemp())).append(",");
					row.append(DF.format(cell.getResistance())).append(",");
					row.append(DF.format(cell.getSoc())).append(",");
					row.append(DF.format(cell.getSoh()));
					if (j < cellNumbers[i] - 1) {
						row.append(",");
					}
				}
				writer.println(row.toString());
			} catch (IOException e) {
				logger.error("Error writing to CSV for string {}: {}", strId, e.getMessage());
			}
		}
	}

	private void initUpdateTimer() {
        updateTimer = new Timer("Modbus Update");
		// logger.info("------------------ INit update TImer------------------");
	
	    TimerTask task = new TimerTask() {
	        @Override
	        public void run() {
				try {
				// logger.info("-------------------- Run Update Task for MODBUS-------------------");
				if(!isRestored) {
					List<String> allChannelId = dataAccessService.getAllIds();
					stringIds = detectStringIdsWithCellQty(allChannelId);
					updateLatestSaveChannelNames(stringIds);
					if(LatestValuesRestorer.restoreAll(dataAccessService, latestSaveChannelNames) > 0) {
						isRestored = true;
					}
				}
				if (isRestored) {
					getCellDimension();
					checkInitCellDimensions();
					if(isFirstInitAllVariables && stringNumber > 0 && cellNumber > 0) {
						// logger.info("Cell dimensions are ready. Initializing power cells and channels.");
						if(setSoCEngine(stringNumber) == 1){
							calculateSoCSoH();
							pushCalculatedDatatoChannels();
						}
					}
					else{
						// logger.info("Cell dimensions are not ready yet. Skipping this cycle.");
					}
					if(isRestored == false) {
						if(LatestValuesRestorer.restoreAll(dataAccessService, latestSaveChannelNames) > 0) {
							isRestored = true;
						}
					}
					// if(isUpdatedOverall){
					// 	if(LatestValuesRestorer.restoreAll(dataAccessService, latestSaveChannelNames) > 0) {
					// 		logger.info("Updated the overall channel!!!!");
					// 	}
					// 	isUpdatedOverall = false;
					// 	isRestoredAfterUpdated = true;
					// }
				}
				// setFirstOverallStringValue(stringNumber);
				} catch (RuntimeException e) {
					logger.error("Unhandled exception in SimpleDemoApp update timer", e);
				}
			}
		};
		updateTimer.scheduleAtFixedRate(task, (long) 1 * 1000, (long) 1 * 1000);
	}

	private void getCellDimension() {
		stringNumber_1 = 0;
		List<String> allChannelId = dataAccessService.getAllIds();
		stringIds = detectStringIdsWithCellQty(allChannelId);
		int index = stringIds.length;

		// logger.info("logger.info(\"------------- GETCELLDIMESION: stringNumber_1 {}----------------\n", index);
		// stringNumber_1 = index;

//		if(stringNumber != index){
//			// logger.info("String number changed from {} to {}, re-initializing cell dimensions.", stringNumber, index);
//		}
		if(!isFirstInitAllDimension){
			cellNumbers = new int[index];
			isInitCellDimensions = new boolean[index];
			for (int i = 0; i < index; i++) {
				isInitCellDimensions[i] = false;
			}
			stringNumber_1 = index;
		} else {
			if (index != stringNumber_1)
				stringNumber_1 = index;
			cellNumbers = new int[index];
		}
		if (stringNumber_1 == 0) {
			logger.warn("String number is zero, skipping get cell dimension this cycle.");
			maxCellNumber = 0;
			return;
		}

		// logger.info("------------- GETCELLDIMESION: stringNumber_1 {}----------------\n", stringNumber_1);
		for (int i = 0; i < stringNumber_1; i++) {
			int strId = stringIds[i];
			String cellNumberChannelName = "str" + strId + "_cell_qty";

			try {
				Record cellNumberRecord = getLatestRecord(cellNumberChannelName);
				if (cellNumberRecord == null || cellNumberRecord.getValue() == null) {
					logger.warn("There is no value yet with this channel: {}, skipping get cell dimension this cycle.",
							cellNumberChannelName);
					continue;
				}
				cellNumbers[i] = cellNumberRecord.getValue().asInt();
				// logger.info("String {} has cell number: {}", i+1, cellNumbers[i]);
				if (maxCellNumber < cellNumbers[i]) {
					maxCellNumber = cellNumbers[i];
				}
			} catch (NullPointerException e) {
				logger.warn("There is no value yet with this channel: {}, skipping get cell dimension this cycle.",
						cellNumberChannelName);
			} catch (IndexOutOfBoundsException e) {
				logger.warn("Index out of bound: {}, error: {}", i, e.getMessage());
			}
		}
	}

	private void initiate() {
		// logger.info("Initiating {}", APP_NAME);
		// logger.info("Getting the 1st cell dimension!!!!!");
		initUpdateTimer();
	}

	private void applyListeners() {
		// logger.info("Applying listeners for {}", APP_NAME);

		// 1) Remove listeners for channels that are no longer in the list
		for (Iterator<Map.Entry<String, RecordListener>> it = listeners.entrySet().iterator(); it.hasNext();) {
			Map.Entry<String, RecordListener> entry = it.next();
			String channelId = entry.getKey();
			// logger.info("Checking listener for channel: {}", channelId);
			RecordListener listener = entry.getValue();

			if (!latestSaveChannelNames.contains(channelId)) {
				Channel channel = dataAccessService.getChannel(channelId);
				if (channel != null) {
					channel.removeListener(listener);
				}
				it.remove();
			}
		}

		// 2) Add listeners for all channels we want to track
		for (String channelID : latestSaveChannelNames) {

			// logger.info("Setting up listener for channel: {}", channelID);
			// Reuse existing listener if we already created one
			RecordListener listener = listeners.computeIfAbsent(channelID, id -> (record -> {
				if (record.getValue() != null) {
					// setFirstOverallStringValue(stringNumber);
					if (isPersistedDoubleChannel(channelID)) {
						LatestValuesDao.updateDouble(id, record.getValue().asDouble());
						if (channelID.startsWith("str") && channelID.contains("_cell_qty")) {
							// getCellDimension();
							// channels = initiateChannel();
							isCellNumberChanged = true;
						}
					}
					else
					    LatestValuesDao.updateString(id, record.getValue().asString());

					// logger.info("Listener triggered for channel: {} latest value: {}", channelID,
					// dataAccessService.getChannel(channelID).getLatestRecord().getValue().toString());

					// dataAccessService.getChannel(channelID).setLatestRecord(record);
					// LatestValuesRestorer.restoreAll(dataAccessService, latestSaveChannelNames);

					isUpdatedOverall = true;
				}
			}));

			Channel channel = dataAccessService.getChannel(channelID);
			if (channel == null) {
				logger.warn("Channel {} does not exist", channelID);
				continue;
			}

			// Optional safety: ensure it’s not attached twice
			channel.removeListener(listener);
			channel.addListener(listener);
		}
	}

	private boolean isPersistedDoubleChannel(String channelID) {
		return channelID.equals("dev_serial_comm_number")
				|| (channelID.startsWith("str")
				&& (channelID.contains("_cell_qty")
				|| channelID.contains("_Cnominal")
				|| channelID.contains("_Vcutoff")
				|| channelID.contains("_Vfloat")
				|| channelID.contains("_R_new")
				|| channelID.contains("_alarm_high_rst")
				|| channelID.contains("_alarm_high_temp")
				|| channelID.contains("_alarm_low_voltage")
				|| channelID.contains("_alarm_high_voltage")));
	}
}
