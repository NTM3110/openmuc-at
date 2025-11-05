package org.openmuc.framework.app.simpledemo;

public class SoHEngine {
	private static final double C_NOMINAL_AH = 100.0; // Ah
    private static final double C_NOMINAL_AS = C_NOMINAL_AH * 3600;
    private static final double R_NEW = 0.0034;
    private static final double R_EOL = R_NEW *1.75;
	
	public static double usedQ;
	public static double updatedSoHNominal(double deltaT, double current, int temperature, boolean isStart, boolean isEnd) {
    	if(isStart) usedQ += current * deltaT * TemperatureFactor.getFactor(temperature);
    	if(isEnd) {
    		usedQ = 0.0;
    		return usedQ / C_NOMINAL_AS;
    	}
    	return -1.0;
    }
    public static double updatedSoHRegular(double resistance) {
    	return ((R_EOL- resistance) / (R_EOL-R_NEW));
    }
}
