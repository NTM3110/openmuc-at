package org.openmuc.framework.app.simpledemo;

public class PowerCell {

    // === Fields ===
    private double current;      // Current (A)
    private double resistance;   // Internal resistance (Ω)
    private double voltage;      // Voltage (V)
    private double soc;          // State of Charge (%)
    private double soh;          // State of Health (%)
    private double temp;

    // === Constructors ===
    public PowerCell() {
        // Default empty constructor
    }

    public PowerCell(double current, double resistance, double voltage, double soc, double soh, double temp) {
        this.current = current;
        this.resistance = resistance;
        this.voltage = voltage;
        this.temp = temp;
        this.soc = soc;
        this.soh = soh;
    }

    // === Getters and Setters ===
    public double getCurrent() {
        return current;
    }

    public void setCurrent(double current) {
        this.current = current;
    }

    public double getResistance() {
        return resistance;
    }

    public void setResistance(double resistance) {
        this.resistance = resistance;
    }

    public double getVoltage() {
        return voltage;
    }

    public void setVoltage(double voltage) {
        this.voltage = voltage;
    }

    public double getSoc() {
        return soc;
    }

    public void setSoc(double soc) {
        this.soc = soc;
    }

    public double getSoh() {
        return soh;
    }

    public void setSoh(double soh) {
        this.soh = soh;
    }
    public double getTemp() {
        return temp;
    }

    public void setTemp(double temp) {
        this.temp = temp;
    }

    // === Optional Utility Method ===
    @Override
    public String toString() {
        return String.format(
            "PowerCellValue{I=%.2f A, R=%.4f Ω, V=%.2f V, SoC=%.2f%%, SoH=%.2f%%}",
            current, resistance, voltage, soc, soh
        );
    }
}

