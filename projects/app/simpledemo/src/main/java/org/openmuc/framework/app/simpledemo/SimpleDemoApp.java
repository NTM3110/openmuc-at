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
import java.util.List;
import java.util.Locale;
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
	private static final int DATA_OPTION_NUM = 6;
	private static final DecimalFormatSymbols DFS = DecimalFormatSymbols.getInstance(Locale.US);
	private static final DecimalFormat DF = new DecimalFormat("#0.000", DFS);

	private int stringNumber;
	private int stringNumber_1;
	private int cellNumber;
	private boolean isFirstSoC = true;


	private boolean isInitPowerCells = false;
	private boolean[] isInitCellDimensions;
	private boolean isFirstInitAllDimension = false;
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
			for	(int j = 0; j < cellNumber; j++) {
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
		Channel[][][] channels = new Channel[stringNumber][cellNumber][DATA_OPTION_NUM];
		String[][][] array = new String[stringNumber][cellNumber][DATA_OPTION_NUM];
		
		// logger.info("Current string number is {} with maxCellNumber = {}", stringNumber_1, maxCellNumber);
		for(int i = 0; i < stringNumber; i++) {
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
	private Record getLatestLoggedValue(String channelName) {
		Channel channel = dataAccessService.getChannel(channelName);
		try {
			long startTime = 1761773580L;
			List<Record> records = channel.getLoggedRecords(startTime);
			Record CnominalLatestRecord = records.get(records.size()-1);
			logger.info("--> channel {}: {}",channel.getId(), CnominalLatestRecord.getValue());
			return CnominalLatestRecord;
		} catch (DataLoggerNotAvailableException | IOException e) {
			// TODO Auto-generated catch block
			logger.warn("SQL DATA Logger not ready; skipping read this cycle.");
		}catch(NullPointerException e) {
			logger.warn("SQL Logger not ready; skipping read this cycle.");
		}
		catch(IndexOutOfBoundsException e){
			logger.warn("Channels not ready; skipping read this cycle.");
		}
		return null;
	}
	
	private void resetFlagInitStringSoC0(int stringIndex) {
		if(isInitSoC0 != null) {
			for(int j = 0; j < isInitSoC0[stringIndex].length; j++) {
				isInitSoC0[stringIndex][j] = false;
			}
		}
	}
	private void setSoCEngine(int stringNumber) {
		double vCutoff = -1.0;
		double vFloat = -1.0;
		for (int i = 0; i < stringNumber; i++) {
			try{
				Record VcutoffRecord = dataAccessService.getChannel("str" + (i+1) + "_Vcutoff").getLatestRecord();
				Record VfloatRecord = dataAccessService.getChannel("str" + (i+1) + "_Vfloat").getLatestRecord();
				vCutoff = VcutoffRecord.getValue().asDouble();
				vFloat = VfloatRecord.getValue().asDouble();
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
			}
		}
	}
	
	private void setLatestSoC(PowerCell powerCell, double deltaT, boolean isInitSoC0, int stringIndex) {
		if(!isInitSoC0) {
			double firstSoC = socEngines[stringIndex].initialSoCFromVoltage(powerCell.getVoltage());
			logger.info("Calculate the first SoC: voltage {}--------> firstSoc: {}", powerCell.getVoltage(), firstSoC);
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
				try{
					double current = channels[si][sj][3].getLatestRecord().getValue().asDouble() / 100;
					logger.info("Value of of {}: -----> {}",channels[si][sj][3].getId(), current);
	            	double voltage = channels[si][sj][1].getLatestRecord().getValue().asDouble() / 1000;
					double temp = channels[si][sj][2].getLatestRecord().getValue().asDouble() / 100;
					logger.info("OLD value of voltage of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getVoltage());
					logger.info("NEW value of voltage of string {}_cell{}: -----> {}",si+1, sj+1, voltage);
					if((voltage - powerCells[si][sj].getVoltage()) > 0) {
	            		current *= -1;
					}
					double resistance = channels[si][sj][0].getLatestRecord().getValue().asDouble() / 10000;
					logger.info("Value of of {}: -----> {}",channels[si][sj][0].getId(), resistance);

					powerCells[si][sj].setCurrent(current);
					logger.info("Value of POWERCELL's Current of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getCurrent());
					powerCells[si][sj].setVoltage(voltage);
					logger.info("Value of POWERCELL'S Voltage of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getVoltage());
					powerCells[si][sj].setTemp(temp);
					logger.info("Value of POWERCELL'S Temperature of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getTemp());
					powerCells[si][sj].setResistance(resistance);
					
					setLatestSoC(powerCells[si][sj], 1, isInitSoC0[si][sj], si);
					if(isInitSoC0[si][sj] == false) {
						isInitSoC0[si][sj] = true;
					}
					long now = System.currentTimeMillis();
					DoubleValue doubleSoCValue = new DoubleValue(Double.parseDouble(DF.format(powerCells[si][sj].getSoc())));
					Record socRecord = new Record(doubleSoCValue, now, Flag.VALID);
					channels[si][sj][4].setLatestRecord(socRecord);


					setLatestSoH(powerCells[si][sj]);
					DoubleValue doubleSoHValue = new DoubleValue(Double.parseDouble(DF.format(powerCells[si][sj].getSoh())));
					Record sohRecord = new Record(doubleSoHValue, now, Flag.VALID);
					channels[si][sj][5].setLatestRecord(sohRecord);
				}catch(NullPointerException e) {
					logger.warn("There is no value yet with this channel at string {} cell {}", si+1, sj+1);
				}
			}
		}
	}

	private void checkInitCellDimensions() {
		if(!isFirstInitAllDimension){
			isFirstInitAllDimension = true;
			for(int i = 0; i < stringNumber_1; i++) {
				if(!isInitCellDimensions[i]) {
					isFirstInitAllDimension = false;
				}
			}
		}
		if(isFirstInitAllDimension) {
			if(stringNumber == 0 || cellNumber == 0) {
				logger.info("Initializing isInitSoC0 array: string number: {}, cell number: {}", stringNumber_1, maxCellNumber);
				isInitSoC0 = new boolean[stringNumber_1][maxCellNumber];
				powerCells = new PowerCell[stringNumber_1][maxCellNumber];
				isSetSoCEngine = new boolean[stringNumber_1];
				logger.info("Created SoCEngines array.");
				socEngines = new SoCEngine[stringNumber_1];
				stringNumber = stringNumber_1;
				cellNumber = maxCellNumber;
				channels = initiateChannel();
				initiatePowerCells();
			}
			else {
				logger.info("Re-Initializing isInitSoC0 array if dimension changed: previous string number: {}, previous cell number: {}; new string number: {}, new cell number: {}", stringNumber, cellNumber, stringNumber_1, maxCellNumber);
				if (stringNumber != stringNumber_1 || cellNumber != maxCellNumber) {
					logger.info("Dimensions changed, re-initializing isInitSoC0 array.");
					boolean [][] isInitSoC0Demo = new boolean[stringNumber_1][maxCellNumber];
					int stringSize = Math.min(stringNumber, stringNumber_1);
					int cellSize = Math.min(cellNumber, maxCellNumber);
					for(int i = 0; i < stringSize; i++) {
						for(int j = 0; j < cellSize; j++) {
							isInitSoC0Demo[i][j] = isInitSoC0[i][j];
						}
					}
					isInitSoC0 = isInitSoC0Demo;

					PowerCell[][] powerCellsDemo = new PowerCell[stringNumber_1][maxCellNumber];
					int rows = Math.min(stringNumber_1, powerCells.length);
					for (int i = 0; i < rows; i++) {
						int cols = Math.min(maxCellNumber, powerCells[i].length);
						for (int j = 0; j < cols; j++) {
							PowerCell src = powerCells[i][j];
							powerCellsDemo[i][j] = (src == null) ? null : src; // copy ctor
						}
					}
					powerCells = powerCellsDemo;

					if(stringNumber != stringNumber_1) {
						boolean[] isSetSoCEngineDemo = new boolean[stringNumber_1];
						for(int i = 0; i < Math.min(stringNumber, stringNumber_1); i++) {
							isSetSoCEngineDemo[i] = isSetSoCEngine[i];
						}
						isSetSoCEngine = isSetSoCEngineDemo;
					}
					stringNumber = stringNumber_1;
					cellNumber = maxCellNumber;
					channels = initiateChannel();
					initiatePowerCells();
				}
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
				getCellDimension();
				checkInitCellDimensions();
				if(isFirstInitAllDimension && stringNumber > 0 && cellNumber > 0) {
					logger.info("Cell dimensions are ready. Initializing power cells and channels.");
					setSoCEngine(stringNumber);
	        		calculateSoCSoH();
					pushCalculatedDatatoChannels();
				}
				else{
					logger.info("Cell dimensions are not ready yet. Skipping this cycle.");
				}
	        }
	    };
	    updateTimer.scheduleAtFixedRate(task, (long) 1 * 1000, (long) 1 * 1000);
	}
	
	private void setFirstOverallValue(int stringIndex, String cellNumberChannelName, String stringNameChannelName,String cellBrandChannelName, String cellModelChannelName, String CnominalChannelName, String VnominalChannelName, String VcutoffChannelName, String VfloatChannelName) {
		//Getting cell number from logged data
		logger.info("GEtting Cell number from logged data for string {}", stringIndex+1);
		Record cellNumberRecord = getLatestLoggedValue(cellNumberChannelName);
		if(cellNumberRecord != null) {
			cellNumbers[stringIndex] = cellNumberRecord.getValue().asInt();
			if(maxCellNumber < cellNumbers[stringIndex]) {
				maxCellNumber = cellNumbers[stringIndex];
			}
			logger.info("-----------FINDING STR AND CELL NUMBER: string {} has cell number: {}", stringIndex+1, cellNumbers[stringIndex]);
			long now = System.currentTimeMillis();
			IntValue intCellNumberValue = new IntValue((int)cellNumbers[stringIndex]);
			Record cellNumberLatestRecord = new Record(intCellNumberValue, now, Flag.VALID);
			dataAccessService.getChannel(cellNumberChannelName).setLatestRecord(cellNumberLatestRecord);
			isInitCellDimensions[stringIndex] = true;
		}
		else {
			logger.warn("There is no logged data for cell number {}. Maybe data log does not rise yet.", stringIndex);
		}

		//Getting string name from logged data
		logger.info("GEtting the first string name from logged data for string {}", stringIndex+1);
		Record record = getLatestLoggedValue(stringNameChannelName);
		if(record != null) {
			String stringName = record.getValue().asString();
			logger.info("String {} has name: {}", stringIndex+1, stringName);
			long now = System.currentTimeMillis();
			StringValue stringNameValue = new StringValue(stringName);
			Record stringNameLatestRecord = new Record(stringNameValue, now, Flag.VALID);
			dataAccessService.getChannel(stringNameChannelName).setLatestRecord(stringNameLatestRecord);
		}
		else {
			logger.warn("There is no logged data for stringName OF STRING {}. Maybe data log does not rise yet.", stringIndex);
		}
		//Getting cell brand from logged data
		logger.info("GEtting the first cell brand from logged data for string {}", stringIndex+1);
		Record cellBrandRecord = getLatestLoggedValue(cellBrandChannelName);
		if(cellBrandRecord != null) {
			String cellBrand = cellBrandRecord.getValue().asString();
			logger.info("String {} has cell brand: {}", stringIndex+1, cellBrand);
			long now = System.currentTimeMillis();
			StringValue cellBrandValue = new StringValue(cellBrand);
			Record cellBrandLatestRecord = new Record(cellBrandValue, now, Flag.VALID);
			dataAccessService.getChannel(cellBrandChannelName).setLatestRecord(cellBrandLatestRecord);
		}
		else {
			logger.warn("There is no logged data for cell brand OF STRING {}. Maybe data log does not rise yet.", stringIndex);
		}

		//Getting cell model from logged data
		logger.info("GEtting the first cell model from logged data for string {}", stringIndex+1);
		Record cellModelRecord = getLatestLoggedValue(cellModelChannelName);
		if(cellModelRecord != null) {
			String cellModel = cellModelRecord.getValue().asString();
			logger.info("String {} has cell model: {}", stringIndex+1, cellModel);
			long now = System.currentTimeMillis();
			StringValue cellModelValue = new StringValue(cellModel);
			Record cellModelLatestRecord = new Record(cellModelValue, now, Flag.VALID);
			dataAccessService.getChannel(cellModelChannelName).setLatestRecord(cellModelLatestRecord);
		}
		else {
			logger.warn("There is no logged data for cell model OF STRING {}. Maybe data log does not rise yet.", stringIndex);
		}
		//Getting Cnominal from logged data
		logger.info("GEtting the first Cnominal from logged data for string {}", stringIndex+1);
		Record CnominalRecord = getLatestLoggedValue(CnominalChannelName);
		if(CnominalRecord != null) {
			double Cnominal = CnominalRecord.getValue().asDouble();
			logger.info("String {} has Cnominal: {}", stringIndex+1, Cnominal);
			long now = System.currentTimeMillis();
			DoubleValue doubleCnominalValue = new DoubleValue(Cnominal);
			Record CnominalLatestRecord = new Record(doubleCnominalValue, now, Flag.VALID);
			dataAccessService.getChannel(CnominalChannelName).setLatestRecord(CnominalLatestRecord);
		}
		else {
			logger.warn("There is no logged data for Cnominal OF STRING {}. Maybe data log does not rise yet.", stringIndex);
		}
		//Getting Vnominal from logged data
		logger.info("GEtting the first Vnominal from logged data for string {}", stringIndex+1);
		Record VnominalRecord = getLatestLoggedValue(VnominalChannelName);
		if(VnominalRecord != null) {
			double Vnominal = VnominalRecord.getValue().asDouble();
			logger.info("String {} has Vnominal: {}", stringIndex+1, Vnominal);
			long now = System.currentTimeMillis();
			DoubleValue doubleVnominalValue = new DoubleValue(Vnominal);
			Record VnominalLatestRecord = new Record(doubleVnominalValue, now, Flag.VALID);
			dataAccessService.getChannel(VnominalChannelName).setLatestRecord(VnominalLatestRecord);
		}
		else {
			logger.warn("There is no logged data for Vnominal OF STRING {}. Maybe data log does not rise yet.", stringIndex);
		}
		//Getting Vcutoff from logged data
		logger.info("GEtting the first Vcutoff from logged data for string {}", stringIndex+1);
		Record VcutoffRecord = getLatestLoggedValue(VcutoffChannelName);
		if(VcutoffRecord != null) {
			double Vcutoff = VcutoffRecord.getValue().asDouble();
			logger.info("String {} has Vcutoff: {}", stringIndex+1, Vcutoff);
			long now = System.currentTimeMillis();
			DoubleValue doubleVcutoffValue = new DoubleValue(Vcutoff);
			Record VcutoffLatestRecord = new Record(doubleVcutoffValue, now, Flag.VALID);
			dataAccessService.getChannel(VcutoffChannelName).setLatestRecord(VcutoffLatestRecord);
		}
		else {
			logger.warn("There is no logged data for Vcutoff OF STRING {}. Maybe data log does not rise yet.", stringIndex);
		}
		//Getting Vfloat from logged data
		logger.info("GEtting the first Vfloat from logged data for string {}", stringIndex+1);
		Record VfloatRecord = getLatestLoggedValue(VfloatChannelName);
		if(VfloatRecord != null) {
			double Vfloat = VfloatRecord.getValue().asDouble();
			logger.info("String {} has Vfloat: {}", stringIndex+1, Vfloat);
			long now = System.currentTimeMillis();
			DoubleValue doubleVfloatValue = new DoubleValue(Vfloat);
			Record VfloatLatestRecord = new Record(doubleVfloatValue, now, Flag.VALID);
			dataAccessService.getChannel(VfloatChannelName).setLatestRecord(VfloatLatestRecord);
		}
		else {
			logger.warn("There is no logged data for Vfloat OF STRING {}. Maybe data log does not rise yet.", stringIndex);
		}
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
		logger.info("-----------FINDING STR AND CELL NUMBER: index: {}--------------", index);
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
		logger.info("-----------FINDING STR AND CELL NUMBER: string number: {}--------------", stringNumber_1);
		for(int i = 0; i < stringNumber_1; i++) {
			String cellNumberChannelName = "str" + (i+1) + "_cell_number";
			String stringNameChannelName = "str" + (i+1) + "_string_name";
			String cellBrandChannelName = "str" + (i+1) + "_cell_brand";
			String cellModelChannelName = "str" + (i+1) + "_cell_model";
			String CnominalChannelName = "str" + (i+1) + "_Cnominal";
			String VnominalChannelName = "str" + (i+1) + "_Vnominal";
			String VcutoffChannelName = "str" + (i+1) + "_Vcutoff";
			String VfloatChannelName = "str" + (i+1) + "_Vfloat";


			if(!isFirstInitAllDimension){
				if(!isInitCellDimensions[i]){
					setFirstOverallValue(i, cellNumberChannelName, stringNameChannelName, cellBrandChannelName, cellModelChannelName, CnominalChannelName, VnominalChannelName, VcutoffChannelName, VfloatChannelName);
				}
			}
			else {
				logger.info("Getting Cell number from latest record for string {}", i+1);
				try {
					cellNumbers[i] = dataAccessService.getChannel(cellNumberChannelName).getLatestRecord().getValue().asInt();
					logger.info("-----------FINDING STR AND CELL NUMBER: string {} has cell number: {}", i+1, cellNumbers[i]);
					if(maxCellNumber < cellNumbers[i]) {
						maxCellNumber = cellNumbers[i];
					}
					long now = System.currentTimeMillis();
					IntValue intCellNumberValue = new IntValue((int)cellNumbers[i]);
					Record cellNumberLatestRecord = new Record(intCellNumberValue, now, Flag.VALID);
					dataAccessService.getChannel(cellNumberChannelName).setLatestRecord(cellNumberLatestRecord);

					String stringName = dataAccessService.getChannel(stringNameChannelName).getLatestRecord().getValue().asString();
					logger.info("String {} has name: {}", i+1, stringName);
					now = System.currentTimeMillis();
					StringValue stringNameValue = new StringValue(stringName);
					Record stringNameLatestRecord = new Record(stringNameValue, now, Flag.VALID);
					dataAccessService.getChannel(stringNameChannelName).setLatestRecord(stringNameLatestRecord);

					String cellBrand = dataAccessService.getChannel(cellBrandChannelName).getLatestRecord().getValue().asString();
					logger.info("String {} has cell brand: {}", i+1, cellBrand);
					now = System.currentTimeMillis();
					StringValue cellBrandValue = new StringValue(cellBrand);
					Record cellBrandLatestRecord = new Record(cellBrandValue, now, Flag.VALID);
					dataAccessService.getChannel(cellBrandChannelName).setLatestRecord(cellBrandLatestRecord);

					String cellModel = dataAccessService.getChannel(cellModelChannelName).getLatestRecord().getValue().asString();
					logger.info("String {} has cell model: {}", i+1, cellModel);
					now = System.currentTimeMillis();
					StringValue cellModelValue = new StringValue(cellModel);
					Record cellModelLatestRecord = new Record(cellModelValue, now, Flag.VALID);
					dataAccessService.getChannel(cellModelChannelName).setLatestRecord(cellModelLatestRecord);

					double Cnominal = dataAccessService.getChannel(CnominalChannelName).getLatestRecord().getValue().asDouble();
					logger.info("String {} has Cnominal: {}", i+1, Cnominal);
					now = System.currentTimeMillis();
					DoubleValue doubleCnominalValue = new DoubleValue(Cnominal);
					Record CnominalLatestRecord = new Record(doubleCnominalValue, now, Flag.VALID);
					dataAccessService.getChannel(CnominalChannelName).setLatestRecord(CnominalLatestRecord);

					double Vnominal = dataAccessService.getChannel(VnominalChannelName).getLatestRecord().getValue().asDouble();
					logger.info("String {} has Vnominal: {}", i+1, Vnominal);
					now = System.currentTimeMillis();
					DoubleValue doubleVnominalValue = new DoubleValue(Vnominal);
					Record VnominalLatestRecord = new Record(doubleVnominalValue, now, Flag.VALID);
					dataAccessService.getChannel(VnominalChannelName).setLatestRecord(VnominalLatestRecord);

					double Vcutoff = dataAccessService.getChannel(VcutoffChannelName).getLatestRecord().getValue().asDouble();
					logger.info("String {} has Vcutoff: {}", i+1, Vcutoff);
					now = System.currentTimeMillis();		
					DoubleValue doubleVcutoffValue = new DoubleValue(Vcutoff);
					Record VcutoffLatestRecord = new Record(doubleVcutoffValue, now, Flag.VALID);
					dataAccessService.getChannel(VcutoffChannelName).setLatestRecord(VcutoffLatestRecord);	

					double Vfloat = dataAccessService.getChannel(VfloatChannelName).getLatestRecord().getValue().asDouble();
					logger.info("String {} has Vfloat: {}", i+1, Vfloat);
					now = System.currentTimeMillis();		
					DoubleValue doubleVfloatValue = new DoubleValue(Vfloat);
					Record VfloatLatestRecord = new Record(doubleVfloatValue, now, Flag.VALID);
					dataAccessService.getChannel(VfloatChannelName).setLatestRecord(VfloatLatestRecord);

				}catch(NullPointerException e) {
					logger.warn("There is no value yet with this channel: {}, error: {}", e.getMessage());
				}catch(IndexOutOfBoundsException e){
					logger.warn("Index out of bound: {},error: {}", i, e.getMessage());
				}
			}
		}
	}
	
	private void initiate() {
		logger.info("Initiating {}", APP_NAME);
		logger.info("Getting the 1st cell dimension!!!!!");
		initUpdateTimer();
		//TODO: add function to let user set VCUTOFF and VFLOAT
		// double vCutoff = 1.85;
		// double vFloat = 2.25;
		// socEngine = new SoCEngine();
	}
}
