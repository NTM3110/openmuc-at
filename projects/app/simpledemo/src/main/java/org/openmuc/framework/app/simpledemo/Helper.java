package org.openmuc.framework.app.simpledemo;

public class Helper {
	public static class Result {
        public final int index;       // posterior SoC
        public final double value;        // posterior Vp // R0 passed in

        public Result(int index, double value) {
            this.index = index;
            this.value = value;
        }
    }
	public static double[] caculateStringVoltage(int stringNumber, int cellNumber, PowerCell[][] powerCells) {
		double[] str_voltage = new double[stringNumber];
		for(int i = 0; i < stringNumber; i++) {
			for	(int j = 0; j < cellNumber; j++) {
				final int si = i; // final copies for capture
	            final int sj = j;
	            str_voltage[si] += powerCells[si][sj].getVoltage();
			}
		}
		return str_voltage;
	}
	public static double[] calculateStringSOC(int stringNumber, int cellNumber, PowerCell[][] powerCells) {
		double[] str_SOC = new double[stringNumber];
		for(int i = 0; i < stringNumber; i++) {
			for	(int j = 0; j < cellNumber; j++) {
				final int si = i; // final copies for capture
	            final int sj = j;
	            str_SOC[si] += powerCells[si][sj].getSoc();
			}
			str_SOC[i] = str_SOC[i] / cellNumber;
		}
		return str_SOC;
	}
	
	public static double[] calculateStringSOH(int stringNumber, int cellNumber, PowerCell[][] powerCells) {
		double[] str_SOH = new double[stringNumber];
		for(int i = 0; i < stringNumber; i++) {
			for	(int j = 0; j < cellNumber; j++) {
				final int si = i; // final copies for capture
	            final int sj = j;
	            if(str_SOH[si] < powerCells[si][sj].getSoh())
	            str_SOH[si] = powerCells[si][sj].getSoh();
			}
		}
		return str_SOH;
	}
	
	public static Result getMaxVoltageBattery(int stringIndex, int cellNumber, PowerCell[][] powerCells) {
		int maxVoltageIndex = -1;
		double maxVoltage = -1.0;
		for(int i = 0; i < cellNumber; i++) {
			if(powerCells[stringIndex][i].getVoltage() > maxVoltage) {
				maxVoltageIndex = i+1;
				maxVoltage = powerCells[stringIndex][i].getVoltage();
			}
		}
		return new Result(maxVoltageIndex, maxVoltage);
	}
	
	public static Result getMinVoltageBattery(int stringIndex, int cellNumber, PowerCell[][] powerCells) {
		int minVoltageIndex = -1;
		double minVoltage = 100.0;
		for(int i = 0; i < cellNumber; i++) {
			if(powerCells[stringIndex][i].getVoltage() > minVoltage) {
				minVoltageIndex = i+1;
				minVoltage = powerCells[stringIndex][i].getVoltage();
			}
		}
		return new Result(minVoltageIndex, minVoltage);
	}
	
	public static Result getMaxResistanceBattery(int stringIndex, int cellNumber, PowerCell[][] powerCells) {
		int maxResistanceIndex = -1;
		double maxResistance = -1.0;
		for(int i = 0; i < cellNumber; i++) {
			if(powerCells[stringIndex][i].getResistance() > maxResistance) {
				maxResistanceIndex = i+1;
				maxResistance = powerCells[stringIndex][i].getResistance();
			}
		}
		return new Result(maxResistanceIndex, maxResistance);
	}
	
	public static double getAverageResistanceBattery(int stringIndex, int cellNumber, PowerCell[][] powerCells) {
		double averageResistance = 0.0;
		for(int i = 0; i < cellNumber; i++) {
				averageResistance += powerCells[stringIndex][i].getResistance();
		}
		averageResistance /= cellNumber;
		return averageResistance;
	}
	
	public static Result getMinResistanceBattery(int stringIndex, int cellNumber, PowerCell[][] powerCells) {
		int minResistanceIndex = -1;
		double minResistance = 100.0;
		for(int i = 0; i < cellNumber; i++) {
			if(powerCells[stringIndex][i].getResistance() > minResistance) {
				minResistanceIndex = i+1;
				minResistance = powerCells[stringIndex][i].getResistance();
			}
		}
		return new Result(minResistanceIndex, minResistance);
	}
	
	public static Result getMaxTemperatureBattery(int stringIndex, int cellNumber, PowerCell[][] powerCells) {
		int maxTempIndex = -1;
		double maxTemp = -1.0;
		for(int i = 0; i < cellNumber; i++) {
			if(powerCells[stringIndex][i].getTemp() > maxTemp) {
				maxTempIndex = i+1;
				maxTemp = powerCells[stringIndex][i].getTemp();
			}
		}
		return new Result(maxTempIndex, maxTemp);
	}
	
	public static double getAverageTemperatureBattery(int stringIndex, int cellNumber, PowerCell[][] powerCells) {
		double averageTemp = 0.0;
		for(int i = 0; i < cellNumber; i++) {
				averageTemp += powerCells[stringIndex][i].getTemp();
		}
		averageTemp /= cellNumber;
		return averageTemp;
	}
	
	public static Result getMinTemperatureBattery(int stringIndex, int cellNumber, PowerCell[][] powerCells) {
		int minTempIndex = -1;
		double minTemp = 100.0;
		for(int i = 0; i < cellNumber; i++) {
			if(powerCells[stringIndex][i].getResistance() > minTemp) {
				minTempIndex = i+1;
				minTemp = powerCells[stringIndex][i].getTemp();
			}
		}
		return new Result(minTempIndex, minTemp);
	}
}
