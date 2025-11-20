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
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

@Component(service = {})
public final class SimpleDemoApp 
{
   private static final Logger logger = LoggerFactory.getLogger(SimpleDemoApp.class);
	private static final String APP_NAME = "ATEnergy BMS App";
	private static final String[] OPTIONS = {"R", "V", "T", "I", "SOC", "SOH"};
	private static final List<String> STRING_CHANNEL_TEMPLATES = Arrays.asList(
        "str%d_cell_qty",
        "str%d_Cnominal",
        "str%d_string_name",
        "str%d_cell_brand",
        "str%d_cell_model",
        "str%d_Vnominal"
);
	private static final int DATA_OPTION_NUM = 6;
	private static final DecimalFormatSymbols DFS = DecimalFormatSymbols.getInstance(Locale.US);
	private static final DecimalFormat DF = new DecimalFormat("#0.000", DFS);

	List<String> latestSaveChannelNames = new ArrayList<>();
	private final Map<String, RecordListener> listeners = new HashMap<>();

	boolean isRestored = false;
	private int stringNumber;
	private int stringNumber_1;
	private int cellNumber = 1;
	private boolean isFirstSoC = true;
	private boolean isCellNumberChanged = false;


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
	private SoCEngine socEngine;
	private SoCEngine[] socEngines;
	// private SoHEngine sohEngine;

	
	@Reference
	private DataAccessService dataAccessService;

	@Activate
	private void activate() {
		logger.info("Activating {}", APP_NAME);
		initiate();
	}

	@Deactivate
	private void deactivate() {
		logger.info("Deactivating {}", APP_NAME);
        updateTimer.cancel();
        updateTimer.purge();
	}	

	private void initiatePowerCells(){
		for(int i = 0; i < stringNumber_1; i++) {
			for	(int j = 0; j < maxCellNumber; j++) {
				powerCells[i][j] = new PowerCell();
			}
		}
	}
	private Channel[][][] initiateChannel() {
		
		logger.info("Initiate Channel {}", APP_NAME);

		// stringNumber_1 = 2; //TODO: change back to get from dimension function
		// maxCellNumber = 2; //TODO: change back to get from dimension function
		
		logger.info("DATA OPTION NUMBER {}", DATA_OPTION_NUM);
		if(stringNumber <= 0) return null;
		Channel[][][] channels = new Channel[stringNumber_1][cellNumber][DATA_OPTION_NUM];
		String[][][] array = new String[stringNumber_1][cellNumber][DATA_OPTION_NUM];
		
		// logger.info("Current string number is {} with maxCellNumber = {}", stringNumber_1, maxCellNumber);
		for(int i = 0; i < stringNumber_1; i++) {
			for	(int j = 0; j < cellNumbers[i]; j++) {				
				for(int k = 0; k < DATA_OPTION_NUM; k++){
					if(k == 3) {
						array[i][j][k] = "str" + (i+1) + "_total" + "_"+ OPTIONS[k];
					}
					else {
						array[i][j][k] = "str" + (i+1) + "_cell" + (j+1) + "_"+ OPTIONS[k];
					}
					channels[i][j][k] = dataAccessService.getChannel(array[i][j][k]);
					// logger.info("Get channels {} ----> {}", APP_NAME, array[i][j][k]);
				}
			}
		}
		return channels;
	}
	
	private void resetFlagInitStringSoC0(int stringIndex) {
		if(isInitSoC0 != null) {
			for(int j = 0; j < isInitSoC0[stringIndex].length; j++) {
				isInitSoC0[stringIndex][j] = false;
			}
		}
	}
	private int setSoCEngine(int stringNumber) {
		double vCutoff = -1.0;
		double vFloat = -1.0;
		for (int i = 0; i < stringNumber; i++) {
			try{
				Record VnominalRecord = dataAccessService.getChannel("str" + (i+1) + "_Vnominal").getLatestRecord();
				double Vnominal = VnominalRecord.getValue().asDouble();
				if (Vnominal > 0.0) {
					logger.info("SETSOCENGINE: Vnominal is 2.0V for string {}. Using default Vcutoff 1.75V and Vfloat 2.4V", i+1);
					vCutoff = Vnominal - 0.15;
					vFloat = Vnominal + 0.25;
				}
				if(vCutoff < 0 || vFloat < 0) {
					logger.warn("Vcutoff or Vfloat is not set properly yet for string {}. Skipping set SoC engine this cycle.", i+1);
					continue;
				}
				String CnominalChannelName = "str" + (i+1) + "_Cnominal";
				double Cnominal = -1.0;
				Record CnominalRecord = dataAccessService.getChannel(CnominalChannelName).getLatestRecord();
				Cnominal = CnominalRecord.getValue().asDouble();
				if(Cnominal < 0) {
					logger.warn("Cnominal is not set properly yet for string {}. Skipping set SoC engine this cycle.", i+1);
					continue;
				}
				// logger.info("Old value: String {}: Cnominal: {}, Vcutoff: {}, Vfloat: {}", i+1, socEngines[i]!=null?socEngines[i].getCnominal():"null", socEngines[i]!=null?socEngines[i].getVcutoff():"null", socEngines[i]!=null?socEngines[i].getVfloat():"null");
				logger.info("New Value: String {}: Cnominal: {}, Vcutoff: {}, Vfloat: {}", i+1, Cnominal, vCutoff, vFloat);
				boolean isSoCEngineChanged = false;
				if (isSetSoCEngine[i]) {
					if(socEngines[i].getVcutoff() != vCutoff) {
						socEngines[i].setVcutoff(vCutoff);
						logger.info("Update Vcutoff for string {} to {}", i+1, vCutoff);
						isSoCEngineChanged = true;
					}
					if(socEngines[i].getCnominal() != Cnominal) {
						socEngines[i].setCnominal(Cnominal);
						logger.info("Update Cnominal for string {} to {}", i+1, Cnominal);
						isSoCEngineChanged = true;
					}
					if (socEngines[i].getVfloat() != vFloat) {
						socEngines[i].setVfloat(vFloat);
						logger.info("Update Vfloat for string {} to {}", i+1, vFloat);
						isSoCEngineChanged = true;
					}
					if (isSoCEngineChanged) {
						resetFlagInitStringSoC0(i);
						double VcutoffNew = socEngines[i].getVcutoff();
						double VfloatNew = socEngines[i].getVfloat();
						double CnominalNew = socEngines[i].getCnominal();
						socEngines[i] = new SoCEngine(CnominalNew, VcutoffNew, VfloatNew);
						logger.info("Re-initialize SoC engine for string {} due to parameter change.", i+1);
					}
				}
				if(!isSetSoCEngine[i]) {
					socEngines[i] = new SoCEngine(Cnominal, vCutoff, vFloat);		
					isSetSoCEngine[i] = true;
					logger.info("Set SoC engine for string {} with Cnominal: {}, Vcutoff: {}, Vfloat: {}", i+1, Cnominal, vCutoff, vFloat);
				}
			}catch(NullPointerException e) {
				logger.warn("There is no value yet with this channel at string {}: {}, skipping set SoC engine this cycle.", i+1, e.getMessage());
				return 0;
			}
		}
		return 1;
	}
	
	private void setLatestSoC(PowerCell powerCell, double deltaT, boolean isInitSoC0, int stringIndex) {
		if(!isInitSoC0) {
			double firstSoC = socEngines[stringIndex].initialSoCFromVoltage(powerCell.getVoltage());
			// logger.info("Calculate the first SoC: voltage {}--------> firstSoc: {}", powerCell.getVoltage(), firstSoC);
			powerCell.setSoc(firstSoC * 100);
			logger.info("First SoC ----> {}", firstSoC*100);
		}
		else {
			double currentSoC = socEngines[stringIndex].updatedSoCEKF(powerCell.getVoltage(), powerCell.getCurrent(), powerCell.getTemp(), deltaT);
			powerCell.setSoc(currentSoC*100);
			logger.info("SetLatestSoC ----> currentSoC: {}", currentSoC*100);
		}
	}

	private void setLatestSoH(PowerCell powerCell) {
		double currentSoH = SoHEngine.updatedSoHRegular(powerCell.getResistance());
		powerCell.setSoh(currentSoH*100);
		logger.info("SetLatestSoH ----> currentSoH: {}", currentSoH*100);
	}
	
	private void calculateSoCSoH() {
		for(int i = 0; i < stringNumber; i++) {
			for	(int j = 0; j < cellNumbers[i]; j++) {
				final int si = i; // final copies for capture
	            final int sj = j;
				// try{
					if(channels[si][sj][3] == null || 
					   channels[si][sj][1] == null ||
					   channels[si][sj][2] == null ||
					   channels[si][sj][0] == null) {
						logger.warn("----------------- MODBUS: There is no channel yet at string {} cell {} --------------", si+1, sj+1);
						channels = initiateChannel();
						continue;
					}
					if(channels[si][sj][3].getLatestRecord().getValue() == null ||
					   channels[si][sj][1].getLatestRecord().getValue() == null ||
					   channels[si][sj][2].getLatestRecord().getValue() == null ||
					   channels[si][sj][0].getLatestRecord().getValue() == null) {
						logger.warn("----------------- MODBUS: There is no value yet with this channel at string {} cell {} --------------", si+1, sj+1);
						continue;
					}
					
					double current = channels[si][sj][3].getLatestRecord().getValue().asDouble() / 100;
					// logger.info("Value of of {}: -----> {}",channels[si][sj][3].getId(), current);
	            	double voltage = channels[si][sj][1].getLatestRecord().getValue().asDouble() / 1000;
					double temp = channels[si][sj][2].getLatestRecord().getValue().asDouble() / 100;
					// logger.info("OLD value of voltage of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getVoltage());
					// logger.info("NEW value of voltage of string {}_cell{}: -----> {}",si+1, sj+1, voltage);
					if((voltage - powerCells[si][sj].getVoltage()) > 0) {
	            		current *= -1;
					}
					double resistance = channels[si][sj][0].getLatestRecord().getValue().asDouble() / 10000;
					// logger.info("Value of of {}: -----> {}",channels[si][sj][0].getId(), resistance);

					powerCells[si][sj].setCurrent(current);
					logger.info("I of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getCurrent());
					powerCells[si][sj].setVoltage(voltage);
					logger.info("V of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getVoltage());
					powerCells[si][sj].setTemp(temp);
					logger.info("T of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getTemp());
					powerCells[si][sj].setResistance(resistance);
					
					setLatestSoC(powerCells[si][sj], 1, isInitSoC0[si][sj], si);
					if(isInitSoC0[si][sj] == false) {
						isInitSoC0[si][sj] = true;
					}
					long now = System.currentTimeMillis();
					// logger.info("SoC of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getSoc());
					DoubleValue doubleSoCValue = new DoubleValue(Double.parseDouble(DF.format(powerCells[si][sj].getSoc())));
					Record socRecord = new Record(doubleSoCValue, now, Flag.VALID);
					channels[si][sj][4].setLatestRecord(socRecord);


					setLatestSoH(powerCells[si][sj]);
					// logger.info("SoH of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getTemp());
					DoubleValue doubleSoHValue = new DoubleValue(Double.parseDouble(DF.format(powerCells[si][sj].getSoh())));
					Record sohRecord = new Record(doubleSoHValue, now, Flag.VALID);
					channels[si][sj][5].setLatestRecord(sohRecord);
				// }catch(NullPointerException e) {
				// 	logger.warn("----------------- MODBUS: There is no value yet with this channel at string {} cell {} --------------", si+1, sj+1);
				// }
			}
		}
	}

	private void updateLatestSaveChannelNames(int stringCount) {
		latestSaveChannelNames.clear();

		// Add fixed channels
		latestSaveChannelNames.add("soh_process_status");
		latestSaveChannelNames.add("dev_serial_comm_number");
		latestSaveChannelNames.add("dev_serial_comm_0");
		latestSaveChannelNames.add("dev_serial_comm_1");
		latestSaveChannelNames.add("dev_serial_comm_2");
		latestSaveChannelNames.add("site_name_1");
		latestSaveChannelNames.add("account_1_username");
		latestSaveChannelNames.add("account_1_password");

		// Add channels for each string
		for (int i = 1; i <= stringCount; i++) {
			for (String template : STRING_CHANNEL_TEMPLATES) {
				latestSaveChannelNames.add(String.format(template, i));
			}
		}

		logger.info("Updated latestSaveChannelNames: {}", latestSaveChannelNames);
	}
	
	private void setFirstOverallStringValue(int stringCount){
		for(int i = 0; i < stringCount; i++) {
			for(String template: STRING_CHANNEL_TEMPLATES){
				try{
					String stringNameChannel = String.format(template, i+1);
					logger.info("First overall string name for string {}: {}", i+1, stringNameChannel);
					Record record = dataAccessService.getChannel(stringNameChannel).getLatestRecord();
					logger.info("First overall string value for string {}: {}", i+1, record.getValue().toString());
					dataAccessService.getChannel(stringNameChannel).setLatestRecord(record);
				}
				catch(NullPointerException e){
					logger.warn("There is no value yet with this channel at string {}: {}, skipping set first overall string name this cycle.", i+1, e.getMessage());
				}
			}
		}
	}

	private void checkInitCellDimensions() {
		if(!isFirstInitAllDimension){
			isFirstInitAllDimension = true;
			// for(int i = 0; i < stringNumber_1; i++) {
			// 	if(!isInitCellDimensions[i]) {
			// 		isFirstInitAllDimension = false;
			// 	}
			// }
		}
		if(isFirstInitAllDimension) {
			if(!isFirstInitAllVariables){
				logger.info("Initializing isInitSoC0 array: string number: {}, cell number: {}", stringNumber_1, maxCellNumber);
				isInitSoC0 = new boolean[stringNumber_1][maxCellNumber];
				powerCells = new PowerCell[stringNumber_1][maxCellNumber];
				isSetSoCEngine = new boolean[stringNumber_1];
				logger.info("Created SoCEngines array.");
				socEngines = new SoCEngine[stringNumber_1];
				stringNumber = stringNumber_1;
				updateLatestSaveChannelNames(stringNumber);
				applyListeners();
				cellNumber = maxCellNumber;
				channels = initiateChannel();
				initiatePowerCells();
				isFirstInitAllVariables = true;
			}
			else {
				// logger.info("Re-Initializing isInitSoC0 array if dimension changed: previous string number: {}, previous cell number: {}; new string number: {}, new cell number: {}", stringNumber, cellNumber, stringNumber_1, maxCellNumber);
				if (stringNumber != stringNumber_1 || cellNumber != maxCellNumber) {
					logger.info("Dimensions changed, re-initializing isInitSoC0 array.");
					isInitSoC0 = new boolean[stringNumber_1][maxCellNumber];

					powerCells = new PowerCell[stringNumber_1][maxCellNumber];

					initiatePowerCells();

					isSetSoCEngine = new boolean[stringNumber_1];
					socEngines = new SoCEngine[stringNumber_1];
					
					updateLatestSaveChannelNames(stringNumber_1);
					applyListeners();
						// LatestValuesRestorer.restoreAll(dataAccessService, latestSaveChannelNames);
						// setFirstOverallStringValue(stringNumber_1);
					
					stringNumber = stringNumber_1;
					cellNumber = maxCellNumber;
					channels = initiateChannel();
				}
				else if (isCellNumberChanged){
					// logger.info("No dimension change detected.");
					logger.info("Dimensions changed, re-initializing isInitSoC0 array.");
					isInitSoC0 = new boolean[stringNumber_1][maxCellNumber];

					powerCells = new PowerCell[stringNumber_1][maxCellNumber];

					initiatePowerCells();

					isSetSoCEngine = new boolean[stringNumber_1];
					socEngines = new SoCEngine[stringNumber_1];
					
					updateLatestSaveChannelNames(stringNumber_1);
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
			logger.info("All cell dimensions are initialized: string number: {}, cell number: {}", stringNumber, cellNumber);
		}
	}

	private void pushCalculatedDatatoChannels() {
		logger.info("Pushing calculated data to channels. stringNumber: {}, cellNumber: {}");
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

		for(int i = 0; i < stringNumber; i++) {
			try{
				String base = "str" + (i+1) + "_";
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
				averageVoltage[i] = strVoltage[i] / cellNumbers[i];
				long now = System.currentTimeMillis();
				DoubleValue doubleStrVolValue = new DoubleValue(Double.parseDouble(DF.format(strVoltage[i])));
				Record record = new Record(doubleStrVolValue, now, Flag.VALID);
				dataAccessService.getChannel(base + "string_vol").setLatestRecord(record);

				IntValue intMaxVoltageIndex = new IntValue(maxVoltageIndex[i]);
				record = new Record(intMaxVoltageIndex, now, Flag.VALID);
				dataAccessService.getChannel(base + "max_voltage_cell_id").setLatestRecord(record);

				IntValue intMinVoltageIndex = new IntValue(minVoltageIndex[i]);
				record = new Record(intMinVoltageIndex, now, Flag.VALID);
				dataAccessService.getChannel(base + "min_voltage_cell_id").setLatestRecord(record);

				IntValue intMaxTempIndex = new IntValue(maxTempIndex[i]);
				record = new Record(intMaxTempIndex, now, Flag.VALID);
				dataAccessService.getChannel(base + "max_temp_cell_id").setLatestRecord(record);

				IntValue intMinTempIndex = new IntValue(minTempIndex[i]);
				record = new Record(intMinTempIndex, now, Flag.VALID);
				dataAccessService.getChannel(base + "min_temp_cell_id").setLatestRecord(record);

				IntValue intMaxResistanceIndex = new IntValue(maxResistanceIndex[i]);
				record = new Record(intMaxResistanceIndex, now, Flag.VALID);
				dataAccessService.getChannel(base + "max_rst_cell_id").setLatestRecord(record);

				IntValue intMinResistanceIndex = new IntValue(minResistanceIndex[i]);
				record = new Record(intMinResistanceIndex, now, Flag.VALID);
				dataAccessService.getChannel(base + "min_rst_cell_id").setLatestRecord(record);

				DoubleValue doubleMaxVoltageValue = new DoubleValue(Double.parseDouble(DF.format(maxVoltage[i])));
				record = new Record(doubleMaxVoltageValue, now, Flag.VALID);
				dataAccessService.getChannel(base + "max_voltage_value").setLatestRecord(record);

				DoubleValue doubleMinVoltageValue = new DoubleValue(Double.parseDouble(DF.format(minVoltage[i])));
				record = new Record(doubleMinVoltageValue, now, Flag.VALID);
				dataAccessService.getChannel(base + "min_voltage_value").setLatestRecord(record);

				DoubleValue doubleMaxTempValue = new DoubleValue(Double.parseDouble(DF.format(maxTemp[i])));
				record = new Record(doubleMaxTempValue, now, Flag.VALID);
				dataAccessService.getChannel(base + "max_temp_value").setLatestRecord(record);

				DoubleValue doubleMinTempValue = new DoubleValue(Double.parseDouble(DF.format(minTemp[i])));
				record = new Record(doubleMinTempValue, now, Flag.VALID);
				dataAccessService.getChannel(base + "min_temp_value").setLatestRecord(record);

				DoubleValue doubleMaxResistanceValue = new DoubleValue(Double.parseDouble(DF.format(maxResistance[i])));
				record = new Record(doubleMaxResistanceValue, now, Flag.VALID);
				dataAccessService.getChannel(base + "max_rst_value").setLatestRecord(record);
				DoubleValue doubleMinResistanceValue = new DoubleValue(Double.parseDouble(DF.format(minResistance[i])));
				record = new Record(doubleMinResistanceValue, now, Flag.VALID);
				dataAccessService.getChannel(base + "min_rst_value").setLatestRecord(record);

				DoubleValue doubleAverageResistanceValue = new DoubleValue(Double.parseDouble(DF.format(averageResistance[i])));
				record = new Record(doubleAverageResistanceValue, now, Flag.VALID);
				dataAccessService.getChannel(base + "average_rst").setLatestRecord(record);

				DoubleValue doubleAverageTempValue = new DoubleValue(Double.parseDouble(DF.format(averageTemp[i])));
				record = new Record(doubleAverageTempValue, now, Flag.VALID);
				dataAccessService.getChannel(base + "average_temp").setLatestRecord(record);

				DoubleValue doubleAverageVoltageValue = new DoubleValue(Double.parseDouble(DF.format(averageVoltage[i])));
				record = new Record(doubleAverageVoltageValue, now, Flag.VALID);
				dataAccessService.getChannel(base + "average_vol").setLatestRecord(record);

				DoubleValue doubleAverageRstValue = new DoubleValue(Double.parseDouble(DF.format(averageResistance[i])));
				record = new Record(doubleAverageRstValue, now, Flag.VALID);
				dataAccessService.getChannel(base + "average_rst").setLatestRecord(record);

				DoubleValue doubleStrSOHValue = new DoubleValue(Double.parseDouble(DF.format(strSOH[i])));
				record = new Record(doubleStrSOHValue, now, Flag.VALID);
				dataAccessService.getChannel(base + "string_SOH").setLatestRecord(record);

				DoubleValue doubleStrSOCValue = new DoubleValue(Double.parseDouble(DF.format(strSOC[i])));
				record = new Record(doubleStrSOCValue, now, Flag.VALID);
				dataAccessService.getChannel(base + "string_SOC").setLatestRecord(record);

			}catch(NullPointerException e) {
				logger.warn("There is no value yet with this channel at string {}: {}, skipping push calculated data to channels this cycle.", i+1, e.getMessage());
			}
		}

	}
	
	private void initUpdateTimer() {
        updateTimer = new Timer("Modbus Update");
	
	    TimerTask task = new TimerTask() {
	        @Override
	        public void run() {
				
				if(isRestored == false) {
					List<String> allChannelId = dataAccessService.getAllIds();
					int index = 0;
					for(int i = 0; i < allChannelId.size(); i++) {
						if(ChannelLayout.getIndex(allChannelId.get(i)) != null){
							index++;
						}
					}
					updateLatestSaveChannelNames(index);
					if(LatestValuesRestorer.restoreAll(dataAccessService, latestSaveChannelNames) > 0) {
						isRestored = true;
					}
				}
				if (isRestored) {
					getCellDimension();
					checkInitCellDimensions();
					if(isFirstInitAllVariables && stringNumber > 0 && cellNumber > 0) {
						logger.info("Cell dimensions are ready. Initializing power cells and channels.");
						if(setSoCEngine(stringNumber) == 1){
							calculateSoCSoH();
							pushCalculatedDatatoChannels();
						}
					}
					else{
						logger.info("Cell dimensions are not ready yet. Skipping this cycle.");
					}
					if(isRestored == false) {
						if(LatestValuesRestorer.restoreAll(dataAccessService, latestSaveChannelNames) > 0) {
							isRestored = true;
						}
					}
				}
				// setFirstOverallStringValue(stringNumber);
	        }
	    };
	    updateTimer.scheduleAtFixedRate(task, (long) 1 * 1000, (long) 1 * 1000);
	}
	
	private void getCellDimension() {
		stringNumber_1 = 0;
		List<String> allChannelId = dataAccessService.getAllIds();
		int index = 0;
		for(int i = 0; i < allChannelId.size(); i++) {
			if(ChannelLayout.getIndex(allChannelId.get(i)) != null){
				index++;
			}
		}
		if(stringNumber != index){
			logger.info("String number changed from {} to {}, re-initializing cell dimensions.", stringNumber, index);
		}
		if(!isFirstInitAllDimension){
			cellNumbers = new int[index];
			isInitCellDimensions = new boolean[index];
			for(int i = 0; i < index; i++) {
				isInitCellDimensions[i] = false;
			}
			stringNumber_1 = index;
		}
		else{
			if(index != stringNumber_1) stringNumber_1 = index;
			cellNumbers = new int[index];
		}
		if(stringNumber_1 == 0) {
			logger.warn("String number is zero, skipping get cell dimension this cycle.");
			maxCellNumber = 0;
			return;
		}

		for(int i = 0; i < stringNumber_1; i++) {
			String cellNumberChannelName = "str" + (i+1) + "_cell_qty";

			try{
				Record cellNumberRecord = dataAccessService.getChannel(cellNumberChannelName).getLatestRecord();
				cellNumbers[i] = cellNumberRecord.getValue().asInt();
				logger.info("String {} has cell number: {}", i+1, cellNumbers[i]);
				if(maxCellNumber < cellNumbers[i]) {
					maxCellNumber = cellNumbers[i];
				}
			}catch(NullPointerException e) {
				logger.warn("There is no value yet with this channel: {}, skipping get cell dimension this cycle.", cellNumberChannelName);
			}catch(IndexOutOfBoundsException e){
				logger.warn("Index out of bound: {},error: {}", i, e.getMessage());	
			}
		}
	}
	
	private void initiate() {
		logger.info("Initiating {}", APP_NAME);
		logger.info("Getting the 1st cell dimension!!!!!");
		initUpdateTimer();
	}

	private void applyListeners() {
    	logger.info("Applying listeners for {}", APP_NAME);

    // 1) Remove listeners for channels that are no longer in the list
		for (Iterator<Map.Entry<String, RecordListener>> it = listeners.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, RecordListener> entry = it.next();
			String channelId = entry.getKey();
			logger.info("Checking listener for channel: {}", channelId);
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

			logger.info("Setting up listener for channel: {}", channelID);
			// Reuse existing listener if we already created one
			RecordListener listener = listeners.computeIfAbsent(channelID, id -> (record -> {
				if (record.getValue() != null) {
					if((channelID.startsWith("str") && channelID.contains("_cell_qty")) || channelID.equals("dev_serial_comm_number") || (channelID.startsWith("str") && channelID.contains("_Cnominal")) || (channelID.startsWith("str") && channelID.contains("_Vnominal"))){
						LatestValuesDao.updateDouble(id, record.getValue().asDouble());
						if(channelID.startsWith("str") && channelID.contains("_cell_qty")){
							// getCellDimension();
							// channels = initiateChannel();
							isCellNumberChanged = true;
						}
					}
					else if(channelID.equals("soh_process_status")) {
						LatestValuesDao.updateBoolean(id, record.getValue().asBoolean());
					}
					else
					LatestValuesDao.updateString(id, record.getValue().asString());

					logger.info("Listener triggered for channel: {} latest value: {}", channelID, dataAccessService.getChannel(channelID).getLatestRecord().getValue().toString());

					// dataAccessService.getChannel(channelID).setLatestRecord(record);
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
}
