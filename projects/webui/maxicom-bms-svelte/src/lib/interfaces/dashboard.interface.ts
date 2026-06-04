export interface DashboardItem {
    id: string;
    siteName: string;
    stringName: string;
    cellVol: 'On' | 'Off';
    cellRst: 'On' | 'Off';
    stringVol: 'On' | 'Off';
    current: 'On' | 'Off';
    ambient: 'On' | 'Off';
    totalCells: number | null;
    maxVoltageValue: number | null;
    minVoltageValue: number | null;
    maxRstValue: number | null;
    maxTempValue: number | null;
    alarm: boolean;
    stringAlarm: boolean | null;
    alarmReasons: string[];
    soC: number | null;
    soH: number | null;
    updateTime: Date;
}
