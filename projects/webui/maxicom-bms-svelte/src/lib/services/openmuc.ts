import { Observable, timer, combineLatest, of, from } from 'rxjs';
import { switchMap, map, shareReplay, catchError } from 'rxjs/operators';
import type { DashboardItem } from '../interfaces/dashboard.interface';

// Interfaces
export interface StringSummaryData {
    stringName: string | null;
    cellQty: number | null;
    updateTime: number | null;
    rstUpdateTime: number | null;
    totalVoltage: number | null;
    stringCurrent: number | null;
    avgVoltage: number | null;
    avgTemp: number | null;
    avgRst: number | null;
    maxVolId: number | null;
    minVolId: number | null;
    maxRstId: number | null;
    minRstId: number | null;
    maxTempId: number | null;
    minTempId: number | null;
    maxVoltageValue: number | null;
    minVoltageValue: number | null;
    maxRstValue: number | null;
    minRstValue: number | null;
    maxTempValue: number | null;
    minTempValue: number | null;
    stringSoC: number | null;
    stringSoH: number | null;
}

export interface CellData {
    ID: number;
    Vol: number | null;
    Temp: number | null;
    Rst: number | null;
    IR: number | null;
    SoC: number | null;
    SoH: number | null;
}

export interface OpenMucRecord {
    id: string;
    valueType: string;
    record: {
        timestamp: number;
        flag: 'VALID' | 'INVALID';
        value: any;
    };
}

interface OpenMucDeviceResponse {
    records: OpenMucRecord[];
    state: string;
}

interface OpenMucDevicesResponse {
    devices: string[];
}

type RecordMap = Map<string, OpenMucRecord['record']>;
const POLLING_INTERVAL = 15000;

const DEVICES_API_URL = '/rest/devices';
const CHANNELS_API_URL = '/rest/channels';

const deviceCache = new Map<string, Observable<RecordMap>>();

function getDeviceRecords(deviceId: string): Observable<RecordMap> {
    if (!deviceCache.has(deviceId)) {
        const observable = timer(0, POLLING_INTERVAL).pipe(
            switchMap(() =>
                from(fetch(`${DEVICES_API_URL}/${deviceId}`).then(r => r.json() as Promise<OpenMucDeviceResponse>))
            ),
            map(response => {
                const recordMap = new Map<string, OpenMucRecord['record']>();
                if (response && response.records) {
                    for (const item of response.records) {
                        if (item.record.flag === 'VALID') {
                            recordMap.set(item.id, item.record);
                        }
                    }
                }
                return recordMap;
            }),
            shareReplay(1),
            catchError(err => {
                console.error(`Error fetching device: ${deviceId}`, err);
                return of(new Map<string, OpenMucRecord['record']>());
            })
        );
        deviceCache.set(deviceId, observable);
    }
    return deviceCache.get(deviceId)!;
}

function getValue(map: RecordMap, key: string): any {
    return map.get(key)?.value ?? null;
}

function getTimestamp(map: RecordMap, key: string): number | null {
    return map.get(key)?.timestamp ?? null;
}

function getAsVolts(map: RecordMap, key: string): number | null {
    const val = getValue(map, key);
    if (typeof val === 'number') {
        return key.includes('_cell') ? val / 1000 : val;
    }
    return null;
}

function getAsAmps(map: RecordMap, key: string): number | null {
    const val = getValue(map, key);
    return typeof val === 'number' ? val / 10 : null;
}

function getAsCelsius(map: RecordMap, key: string): number | null {
    const val = getValue(map, key);
    if (typeof val === 'number') {
        return key.includes('_cell') ? val / 10 : val;
    }
    return null;
}

function getAsRaw(map: RecordMap, key: string): number | null {
    const val = getValue(map, key);
    return typeof val === 'number' ? val : null;
}

function getAsString(map: RecordMap, key: string): string | null {
    const val = getValue(map, key);
    return typeof val === 'string' ? val : null;
}

export function getSummaryData(baseStringName: string): Observable<StringSummaryData> {
    return combineLatest({
        virtual: getDeviceRecords(`${baseStringName}_virtual`),
        modbus: getDeviceRecords(`${baseStringName}_modbus`)
    }).pipe(
        map(({ virtual, modbus }) => {
            const avgRstOhm = getValue(virtual, `${baseStringName}_average_rst`);
            const avgRstMicroOhm = (avgRstOhm !== null) ? avgRstOhm : null;

            return {
                stringName: getAsString(virtual, `${baseStringName}_string_name`),
                cellQty: getAsRaw(virtual, `${baseStringName}_cell_qty`),
                updateTime: getTimestamp(virtual, `${baseStringName}_string_vol`),
                rstUpdateTime: getTimestamp(virtual, `${baseStringName}_average_rst`),
                totalVoltage: getAsVolts(virtual, `${baseStringName}_string_vol`),
                stringCurrent: getAsAmps(modbus, `${baseStringName}_total_I`),
                avgVoltage: getAsVolts(virtual, `${baseStringName}_average_vol`),
                avgTemp: getAsCelsius(virtual, `${baseStringName}_average_temp`),
                avgRst: avgRstMicroOhm,
                maxVolId: getAsRaw(virtual, `${baseStringName}_max_voltage_cell_id`),
                minVolId: getAsRaw(virtual, `${baseStringName}_min_voltage_cell_id`),
                maxRstId: getAsRaw(virtual, `${baseStringName}_max_rst_cell_id`),
                minRstId: getAsRaw(virtual, `${baseStringName}_min_rst_cell_id`),
                maxTempId: getAsRaw(virtual, `${baseStringName}_max_temp_cell_id`),
                minTempId: getAsRaw(virtual, `${baseStringName}_min_temp_cell_id`),
                maxVoltageValue: getAsVolts(virtual, `${baseStringName}_max_voltage_value`),
                minVoltageValue: getAsVolts(virtual, `${baseStringName}_min_voltage_value`),
                maxRstValue: getValue(virtual, `${baseStringName}_max_rst_value`),
                minRstValue: getValue(virtual, `${baseStringName}_min_rst_value`),
                maxTempValue: getAsCelsius(virtual, `${baseStringName}_max_temp_value`),
                minTempValue: getAsCelsius(virtual, `${baseStringName}_min_temp_value`),
                stringSoC: getAsRaw(virtual, `${baseStringName}_string_SOC`),
                stringSoH: getAsRaw(virtual, `${baseStringName}_string_SOH`),
            };
        }),
        shareReplay(1)
    );
}

export function getCellsData(baseStringName: string, cellQty: number): Observable<CellData[]> {
    return combineLatest({
        virtual: getDeviceRecords(`${baseStringName}_virtual`),
        modbus: getDeviceRecords(`${baseStringName}_modbus`)
    }).pipe(
        map(({ virtual, modbus }) => {
            const cells: CellData[] = [];
            const totalIRaw = getAsRaw(modbus, `${baseStringName}_total_I`);

            for (let i = 1; i <= cellQty; i++) {
                const rstRaw = getAsRaw(modbus, `${baseStringName}_cell${i}_R`);
                const irCalculated = (rstRaw !== null && totalIRaw !== null) ? (rstRaw * totalIRaw) / 100000 : null;

                cells.push({
                    ID: i,
                    Vol: getAsVolts(modbus, `${baseStringName}_cell${i}_V`),
                    Temp: getAsCelsius(modbus, `${baseStringName}_cell${i}_T`),
                    Rst: rstRaw,
                    SoC: getAsRaw(virtual, `${baseStringName}_cell${i}_SOC`),
                    SoH: getAsRaw(virtual, `${baseStringName}_cell${i}_SOH`),
                    IR: irCalculated,
                });
            }
            return cells;
        }),
        shareReplay(1)
    );
}

export function getDashboardStatus(): Observable<DashboardItem[]> {
    return from(fetch(DEVICES_API_URL).then(r => r.json() as Promise<OpenMucDevicesResponse>)).pipe(
        switchMap(response => {
            const virtualDevices = response.devices.filter(d => d.endsWith('_virtual'));
            const deviceDataObservables = virtualDevices.map(virtualName => {
                const baseId = virtualName.replace('_virtual', '');
                return combineLatest({
                    virtualMap: getDeviceRecords(virtualName),
                    modbusMap: getDeviceRecords(`${baseId}_modbus`)
                }).pipe(
                    map(({ virtualMap, modbusMap }) => {
                        const avgVol = getAsVolts(virtualMap, `${baseId}_average_vol`);
                        const avgRst = getValue(virtualMap, `${baseId}_average_rst`);
                        const stringVol = getAsVolts(virtualMap, `${baseId}_string_vol`);
                        const current = getAsAmps(modbusMap, `${baseId}_total_I`);
                        const avgTemp = getAsCelsius(virtualMap, `${baseId}_average_temp`);
                        const cellQty = getAsRaw(virtualMap, `${baseId}_cell_qty`);
                        const soC = getAsRaw(virtualMap, `${baseId}_string_SOC`);
                        const soH = getAsRaw(virtualMap, `${baseId}_string_SOH`);

                        const cellVolStatus = (avgVol !== null && avgVol > 0) || (cellQty !== null && cellQty > 0) ? 'On' : 'Off';
                        const cellRstStatus = (avgRst !== null && avgRst > 0) ? 'On' : 'Off';
                        const stringVolStatus = (stringVol !== null && stringVol > 0) ? 'On' : 'Off';
                        const currentStatus = (current !== null && current !== 0) ? 'On' : 'Off';
                        const ambientStatus = (avgTemp !== null && avgTemp !== 0) ? 'On' : 'Off';

                        return {
                            id: baseId,
                            siteName: 'Lego Bình Thuận (Default)',
                            stringName: getAsString(virtualMap, `${baseId}_string_name`) || baseId,
                            cellVol: cellVolStatus,
                            cellRst: cellRstStatus,
                            stringVol: stringVolStatus,
                            current: currentStatus,
                            ambient: ambientStatus,
                            soC: soC,
                            soH: soH,
                            updateTime: getTimestamp(virtualMap, `${baseId}_string_vol`) ?
                                new Date(getTimestamp(virtualMap, `${baseId}_string_vol`)!) : new Date()
                        } as DashboardItem;
                    })
                );
            });

            if (deviceDataObservables.length === 0) {
                return of<DashboardItem[]>([]);
            }

            return combineLatest(deviceDataObservables);
        }),
        shareReplay(1)
    );
}

export function getChannelHistory(channelId: string, fromTime: number, until: number): Observable<Array<{ timestamp: number, value: number }>> {
    const params = new URLSearchParams({
        from: fromTime.toString(),
        until: until.toString()
    });

    return from(fetch(`${CHANNELS_API_URL}/${channelId}/history?${params}`).then(r => r.json())).pipe(
        map(response => {
            if (Array.isArray(response)) {
                return response.map((item: any) => ({
                    timestamp: item.timestamp || item.time || 0,
                    value: typeof item.value === 'number' ? item.value : (item.record?.value ? parseFloat(item.record.value) : 0)
                }));
            }
            if (response.records && Array.isArray(response.records)) {
                return response.records
                    .filter((r: any) => r.record?.flag === 'VALID')
                    .map((r: any) => ({
                        timestamp: r.record.timestamp || 0,
                        value: typeof r.record.value === 'number' ? r.record.value : parseFloat(r.record.value || 0)
                    }));
            }
            if (response.data && Array.isArray(response.data)) {
                return response.data.map((item: any) => ({
                    timestamp: item.timestamp || item.time || 0,
                    value: typeof item.value === 'number' ? item.value : parseFloat(item.value || 0)
                }));
            }
            return [];
        }),
        catchError(err => {
            console.error(`Error loading history for ${channelId}:`, err);
            return of([]);
        })
    );
}
