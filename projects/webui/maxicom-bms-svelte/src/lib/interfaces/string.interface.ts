export interface BatteryString {
    id: string; // ID unique (e.g., 'uuid-1234')
    stringIndex: number; // Index (e.g., 1, 2, 3...)
    stringName: string;
    cellBrand: string;
    cellModel: string;
    cellQty: number;
    ratedCapacity: number; // Cnominal
    cutoffVoltage: number; // Vcutoff
    floatVoltage: number;
    rNew: number;
    highResistanceThreshold: number; // uOhm; 0 disables the alarm
    highTemperatureThreshold: number; // degC; 0 disables the alarm
    lowVoltageThreshold: number; // V; 0 disables the alarm
    highVoltageThreshold: number; // V; 0 disables the alarm
    serialPortId: string; // Port ID (e.g., 'serial0')
}

export type StringFormData = Omit<BatteryString, 'id' | 'stringIndex'>;
