package org.openmuc.framework.app.simpledemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//import org.ejml.simple.SimpleMatrix;
//import org.apache.commons.math3.linear.MatrixUtils;
//import org.apache.commons.math3.linear.RealMatrix;


public class SoCEngine {
    private double C_NOMINAL_AH = 100; // Ah
    private double C_NOMINAL_AS = C_NOMINAL_AH * 3600;
    private static final Logger logger = LoggerFactory.getLogger(SimpleDemoApp.class);
    private static final double K_V_T = 0.0035;
    private static final double R1 = 0.00073;
    private static final double tau = 20;
    private static final double C1 = tau/R1;
    private static final double alpha_temp = 3.5*Math.pow(10, -3);
    
    
//    private static final EKFSoC ekfSoc = new EKFSoC(1, C_NOMINAL_AS, V_CUTOFF, V_FLOAT, R1, C1, alpha_temp,Math.pow(0.01,2));
	
//	private static final R0Estimator r0Estimator = new R0Estimator(30, 0.2, 0.0003, 0.0001, 0.0001, 0.5, 0.2);
	
    private EKFSoC ekfSoC;
    private R0Estimator r0Estimator;
    public double V_CUTOFF;
    public double V_FLOAT;
    public double usedQ;
    
    
	public SoCEngine(double VCutoff, double VFloat) {
		ekfSoC = new EKFSoC(1, C_NOMINAL_AH, VCutoff, VFloat, R1, C1, alpha_temp,Math.pow(0.01,2));
		r0Estimator = new R0Estimator(30, 0.2, 0.0003, 0.0001, 0.0001, 0.5, 0.2);
		this.V_CUTOFF = VCutoff;
		this.V_FLOAT = VFloat;
		usedQ = 0.0;
	}
	
    public double initialSoCFromVoltage(double v){
    	double soc = 0.0;
        if (v >= V_FLOAT) {
            soc =  100.0;
        } else if (v <= V_CUTOFF) {
            soc = 0.0;
        } else {
            // Linear interpolation between V_CUTOFF and V_FLOAT
            soc = ((v - V_CUTOFF) / (V_FLOAT - V_CUTOFF));
        }
        this.ekfSoC.setSoC(soc);
        return soc;
    }
    public double updatedSoC(double lastSoC, double current, double deltaTimeSeconds) {   
    	logger.info("------------------ Updating SOC ----------------");
        double deltaSoC = (current * deltaTimeSeconds) / (C_NOMINAL_AH * 3600);
        logger.info("Updating SOC: Last SoC = {} ------------> deltaSoC: {}", lastSoC, deltaSoC);
        double newSoC = lastSoC - deltaSoC;
        if (newSoC > 100.0) {
            return 100.0;
        } else if (newSoC < 0.0) {
            return 0.0;
        } else {
            return newSoC;
        }
    }
    
    public double updatedSoCEKF(double voltage, double current,double temperature, double detaTimeSeconds) {
    	double R0 = r0Estimator.update(voltage, current);
    	
    	EKFSoC.Result result = ekfSoC.step(voltage, current, temperature, R0);
    	
    	logger.info("SoC: {}, Vp: {}, y_pred: {}, nu: {}, R0: {}", result.SoC, result.Vp, result.y_pred, result.nu, result.R0_used);
    	
    	return result.SoC;
    }
    
    public static double updateVoltageWithTemp(double voltageT, double temperature) {
    	return voltageT - K_V_T*(temperature - 25);
    }
    public void setCnominal(double Cnominal) {
    	this.C_NOMINAL_AH = Cnominal;
    }
    public double getCnominal() {
    	return this.C_NOMINAL_AH;
    }
}
