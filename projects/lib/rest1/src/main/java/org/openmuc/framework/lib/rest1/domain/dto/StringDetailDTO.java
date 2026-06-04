package org.openmuc.framework.lib.rest1.domain.dto;



public class StringDetailDTO {
    String stringName;
    String cellBrand;
    String cellModel;
    Double cellQty;
    Double cNominal;
    Double vCutoff;
    Double vFloat;
    Double rNew;
    Double highResistanceThreshold;
    Double highTemperatureThreshold;
    Double lowVoltageThreshold;
    Double highVoltageThreshold;
    String serialPortId;

    // Getters and Setters
    public String getStringName() {
        return stringName;
    }
    public void setStringName(String stringName) {
        this.stringName = stringName;
    }
    public String getCellBrand() {
        return cellBrand;
    }
    public void setCellBrand(String cellBrand) {
        this.cellBrand = cellBrand;
    }
    public String getCellModel() {
        return cellModel;
    }
    public void setCellModel(String cellModel) {
        this.cellModel = cellModel;
    }
    public Double getCellQty() {
        return cellQty;
    }
    public void setCellQty(Double cellQty) {
        this.cellQty = cellQty;
    }
    public Double getCNominal() {
        return cNominal;
    }
    public void setCNominal(Double cNominal) {
        this.cNominal = cNominal;
    }
    public Double getVCutoff() { return vCutoff;}
    public void setVCutoff(Double vCutoff) {
        this.vCutoff = vCutoff;
    }
    public Double getVFloat() { return vFloat; }
    public void setVFloat(Double vFloat) { this.vFloat = vFloat; }
    public Double getRNew() { return rNew; }
    public void setRNew(Double rNew) { this.rNew = rNew; }
    public Double getHighResistanceThreshold() { return highResistanceThreshold; }
    public void setHighResistanceThreshold(Double highResistanceThreshold) { this.highResistanceThreshold = highResistanceThreshold; }
    public Double getHighTemperatureThreshold() { return highTemperatureThreshold; }
    public void setHighTemperatureThreshold(Double highTemperatureThreshold) { this.highTemperatureThreshold = highTemperatureThreshold; }
    public Double getLowVoltageThreshold() { return lowVoltageThreshold; }
    public void setLowVoltageThreshold(Double lowVoltageThreshold) { this.lowVoltageThreshold = lowVoltageThreshold; }
    public Double getHighVoltageThreshold() { return highVoltageThreshold; }
    public void setHighVoltageThreshold(Double highVoltageThreshold) { this.highVoltageThreshold = highVoltageThreshold; }
    public String getSerialPortId() { return serialPortId; }
    public void setSerialPortId(String serialPortId) { this.serialPortId = serialPortId;}
}
