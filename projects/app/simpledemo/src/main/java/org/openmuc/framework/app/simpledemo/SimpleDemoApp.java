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
	private boolean[] isInitCellDimension;
	private int[] cellNumbers;
	private int maxCellNumber = 0;
	private PowerCell[][] powerCells;
	private Channel[][][] channels;
	private Timer updateTimer;
	private SoCEngine socEngine;
	private SoCEngine[] socEngines;

	
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

	private Channel[][][] initiateChannel() {
		
		logger.info("Initiate Channel {}", APP_NAME);

		// stringNumber_1 = 2; //TODO: change back to get from dimension function
		// maxCellNumber = 2; //TODO: change back to get from dimension function
		
		logger.info("DATA OPTION NUMBER {}", DATA_OPTION_NUM);
		Channel[][][] channels = new Channel[stringNumber][cellNumber][DATA_OPTION_NUM];
		logger.info("Created Channels {}", channels[0][0][0]);
		String[][][] array = new String[stringNumber][cellNumber][DATA_OPTION_NUM];
		
		// logger.info("Current string number is {} with maxCellNumber = {}", stringNumber_1, maxCellNumber);
		for(int i = 0; i < stringNumber; i++) {
			for	(int j = 0; j < cellNumber; j++) {				
				powerCells[i][j] = new PowerCell();
				for(int k = 0; k < DATA_OPTION_NUM; k++){
					if(k == 3) {
						array[i][j][k] = "str" + (i+1) + "_total" + "_"+ OPTIONS[k];
					}
					else {
						array[i][j][k] = "str" + (i+1) + "_cell" + (j+1) + "_"+ OPTIONS[k];
					}
					channels[i][j][k] = dataAccessService.getChannel(array[i][j][k]);
					
					logger.info("Get channels {} ----> {}", APP_NAME, array[i][j][k]);
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
	
	private void setSoCEngine(int stringNumber) {
		double vCutoff = 1.85;
		double vFloat = 2.25;
		for (int i = 0; i < stringNumber; i++) {
			socEngines[i] = new SoCEngine(vCutoff, vFloat);
			String CnominalChannelName = "str" + (i+1) + "_Cnominal";
			double Cnominal = -1.0;
			while(Cnominal == -1.0) {
				Record CnominalRecord = getLatestLoggedValue(CnominalChannelName);
				Cnominal = CnominalRecord.getValue().asDouble();
				logger.info("!!!! Cannot set the C nominal on SoCENGINE for string {} YET", i+1);
			}
			socEngines[i].setCnominal(Cnominal);
		}
	}
	
	private void setLatestSoC(PowerCell powerCell, double deltaT) {
		if(isFirstSoC) {
			double firstSoC = socEngine.initialSoCFromVoltage(powerCell.getVoltage());
			logger.info("Calculate the first SoC: voltage {}--------> firstSoc: {}", powerCell.getVoltage(), firstSoC);
			powerCell.setSoc(firstSoC*100);
			logger.info("First SoC ----> {}", firstSoC*100);
		}
		else {
			double currentSoC = socEngine.updatedSoCEKF(powerCell.getVoltage(), powerCell.getCurrent(), powerCell.getTemp(), deltaT);
			powerCell.setSoc(currentSoC*100);
			logger.info("SetLatestSoC ----> currentSoC: {}", currentSoC*100);
		}
	}
	
	private void calculateSoC() {
		for(int i = 0; i < stringNumber; i++) {
			for	(int j = 0; j < cellNumber; j++) {
				final int si = i; // final copies for capture
	            final int sj = j;
	            double current = channels[si][sj][3].getLatestRecord().getValue().asDouble() / 100;
	            logger.info("Value of of {}: -----> {}",channels[si][sj][3].getId(), current);
	            double voltage = channels[si][sj][1].getLatestRecord().getValue().asDouble() / 1000;
	            
	            double temp = channels[si][sj][2].getLatestRecord().getValue().asDouble() / 100;
	            //TODO: set the current direction though delta V
	            if((voltage - powerCells[si][sj].getVoltage()) > 0) {
	            	current *= -1; 
	            }
	            
	            powerCells[si][sj].setCurrent(current);
	            logger.info("Value of POWERCELL's Current of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getCurrent());
	            powerCells[si][sj].setVoltage(voltage);
	            logger.info("Value of POWERCELL'S Voltage of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getVoltage());
	            powerCells[si][sj].setTemp(temp);
	            logger.info("Value of POWERCELL'S Temperature of string {}_cell{}: -----> {}",si+1, sj+1, powerCells[si][sj].getTemp());
	            
	            setLatestSoC(powerCells[si][sj], 1);
	            long now = System.currentTimeMillis();
				DoubleValue doubleSoCValue = new DoubleValue(Double.parseDouble(DF.format(powerCells[si][sj].getSoc())));
				Record socRecord = new Record(doubleSoCValue, now, Flag.VALID);
				channels[si][sj][4].setLatestRecord(socRecord);
			}
		}
		if(isFirstSoC) isFirstSoC = false;
	}
	
	private void initUpdateTimer() {
        updateTimer = new Timer("Modbus Update");
	
	    TimerTask task = new TimerTask() {
	        @Override
	        public void run() {
//	            getModbusChannelData();
	        	calculateSoC();
	        	getLatestLoggedValue("str1_Cnominal");
	        	// getCellDimension();
	        }
	    };
	    updateTimer.scheduleAtFixedRate(task, (long) 1 * 1000, (long) 1 * 1000);
	}
	
	private void getCellDimension() {
		stringNumber_1 = 0;
		
		//TODO: change back the stringNumber and cell Number to dimension function result
		List<String> allChannelId = dataAccessService.getAllIds();
		
		for(int i = 0; i < allChannelId.size(); i++) {
			int index = 0;
			if(ChannelLayout.getIndex(allChannelId.get(i)) != null){
				index = ChannelLayout.getIndex(allChannelId.get(i));
			}
			if(index > stringNumber_1) stringNumber_1 = index;
		}
		logger.info("-----------FINDING STR AND CELL NUMBER: string number: {}--------------", stringNumber_1);
		cellNumbers = new int[stringNumber_1];
		isInitCellDimension = new boolean[stringNumber_1];
		for(int i = 0; i < stringNumber_1; i++) {
			isInitCellDimension[i] = false;
		}
		
		for(int i = 0; i < stringNumber_1; i++) {
			String cellNumberChannelName = "str" + (i+1) + "_cell_number";
			if(!isInitCellDimension[i]){
				Record cellNumberRecord = getLatestLoggedValue(cellNumberChannelName);
				if(cellNumberRecord != null) {
					cellNumbers[i] = cellNumberRecord.getValue().asInt();
					if(maxCellNumber < cellNumbers[i]) {
						maxCellNumber = cellNumbers[i];
					}
					logger.info("-----------FINDING STR AND CELL NUMBER: string {} has cell number: {}", i+1, cellNumbers[i]);
					long now = System.currentTimeMillis();
					IntValue intCellNumberValue = new IntValue((int)cellNumbers[i]);
					Record cellNumberLatestRecord = new Record(intCellNumberValue, now, Flag.VALID);
					dataAccessService.getChannel(cellNumberChannelName).setLatestRecord(cellNumberLatestRecord);
					isInitCellDimension[i] = true;
				}
				else {
					logger.warn("There is no logged data for cell number {}. Maybe data log does not rise yet.", i);
				}
			}
			else {
				try {
					cellNumbers[i] = dataAccessService.getChannel(cellNumberChannelName).getLatestRecord().getValue().asInt();
					logger.info("-----------FINDING STR AND CELL NUMBER: string {} has cell number: {}", i+1, cellNumbers[i]);
					if(maxCellNumber < cellNumbers[i]) {
						maxCellNumber = cellNumbers[i];
					}
				}catch(NullPointerException e) {
					logger.warn("There is no value yet with this channel");
				}
			}
		}
	}
	
	private void initiate() {
		logger.info("Initiating {}", APP_NAME);
		// logger.info("Getting the first cell dimension!!!!!");
		// while(!isInitCellDimension[0]) {
		// 	logger.info("Getting the first cell dimension loop!!!!!");
		// 	getCellDimension();
		// }
		stringNumber = 1;
		cellNumber = 2;
		if(!isInitPowerCells) {
			powerCells = new PowerCell[stringNumber][cellNumber];
			logger.info("Created PowerCellValue {}");
		}

		channels = initiateChannel();
//		addListener();
		initUpdateTimer();
		//TODO: add function to let user set VCUTOFF and VFLOAT
		double vCutoff = 1.85;
		double vFloat = 2.25;
		socEngine = new SoCEngine(vCutoff, vFloat);
	}
}
