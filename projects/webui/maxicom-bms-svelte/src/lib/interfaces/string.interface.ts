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
    serialPortId: string; // Port ID (e.g., 'serial0')
}

export type StringFormData = Omit<BatteryString, 'id' | 'stringIndex'>;
