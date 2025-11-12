package org.openmuc.framework.app.simpledemo;

public class SoHEngine {
	private static final double C_NOMINAL_AH = 100.0; // Ah
    private static final double C_NOMINAL_AS = C_NOMINAL_AH * 3600;
    private static final double R_NEW = 0.0034;
    private static final double R_EOL = R_NEW *1.75;

	
	public static class Status {
        public boolean isStart;        // isStart
        public boolean isEnd;   // R0 passed in
		public double usedQ;

        public Status(boolean isStart, boolean isEnd, double usedQ) {
            this.isStart = isStart;
			this.isEnd = isEnd;
			this.usedQ = usedQ;
        }
    }
	

	public static double usedQ;
	public static Status updatedSoHNominal(double deltaT, double current, int temperature, boolean isStart, boolean isEnd) {
    	if(isStart) usedQ += current * deltaT * TemperatureFactor.getFactor(temperature);
    	if(isEnd) {
    		usedQ = 0.0; 
    		return new Status(isStart,isEnd, usedQ / C_NOMINAL_AS);
    	}
    	return new Status(isStart, isEnd, usedQ / C_NOMINAL_AS);
    }
    public static double updatedSoHRegular(double resistance) {
    	return ((R_EOL- resistance) / (R_EOL-R_NEW));
    }
}
