import { writable, get } from 'svelte/store';
import { browser } from '$app/environment';
import { v4 as uuidv4 } from 'uuid';
import { showToast } from './toast.store';
import type { BatteryString, StringFormData } from '../interfaces/string.interface';
import type { SerialPortConfig } from '../interfaces/communication.interface';
import type { LatestValueResponse } from '../interfaces/latest-value.interface';

interface LatestStringDetailDto {
    stringName?: string;
    cellBrand?: string;
    cellModel?: string;
    cellQty?: number;
    cNominal?: number;
    vCutoff?: number;
    vFloat?: number;
    rNew?: number;
    serialPortId?: string;
}

const BASE_URL = '/rest';
const LATEST_VALUE_API = '/rest/latest-value';
const STORAGE_KEY = 'maxicom-strings-config';
const MAX_STRING_SCAN = 12;

export const stringsState = writable<BatteryString[]>([]);
export const stringsLoaded = writable<boolean>(false);
export const isLoadingFromApi = writable<boolean>(false);

if (browser) {
    loadStringsFromStorage();
    loadStringsFromApi();
}

function loadStringsFromStorage() {
    try {
        const storedData = localStorage.getItem(STORAGE_KEY);
        if (storedData) {
            stringsState.set(JSON.parse(storedData));
        }
    } catch (e) {
        console.error("Error reading String config from localStorage", e);
        stringsState.set([]);
    }
}

function saveStringsToStorage(strings: BatteryString[]) {
    if (browser) {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(strings));
    }
}

export async function loadStringsFromApi(forceScanAll: boolean = false) {
    if (get(isLoadingFromApi)) {
        await new Promise(resolve => {
            const unsub = isLoadingFromApi.subscribe(v => {
                if (!v) {
                    unsub();
                    resolve(null);
                }
            });
        });
        return;
    }
    isLoadingFromApi.set(true);

    try {
        let indicesToCheck: number[] = [];
        const cachedIndices = buildStringIndicesToCheck(false);

        if (forceScanAll || cachedIndices.length === 0) {
            try {
                const openmucIndices = await getStringIndicesFromOpenMUC();
                indicesToCheck = openmucIndices
                    .filter(index => index > 0)
                    .sort((a, b) => a - b);
            } catch (err) {
                console.warn(
                    '[Strings] Failed to get devices from OpenMUC, using cache indices:',
                    err
                );
                indicesToCheck = cachedIndices;
            }
        } else {
            indicesToCheck = cachedIndices;
        }

        if (indicesToCheck.length === 0) {
            if (forceScanAll) {
                stringsState.set([]);
                saveStringsToStorage([]);
            }
            isLoadingFromApi.set(false);
            stringsLoaded.set(true);
            return;
        }

        const currentStrings = get(stringsState);
        const existingByIndex = new Map(currentStrings.map(s => [s.stringIndex, s]));
        const validIndicesSet = forceScanAll ? new Set(indicesToCheck) : null;

        const results = await Promise.all(indicesToCheck.map(async (index) => {
            try {
                const dto = await fetchStringDetailFromLatestValue(index);
                return { index, dto, source: 'latest-value' };
            } catch (err) {
                return { index, dto: null, source: 'latest-value' };
            }
        }));

        const missingIndices = results
            .filter(r => !r.dto || !hasStringDetail(r.dto))
            .map(r => r.index);

        if (missingIndices.length > 0) {
            const openmucResults = await Promise.all(missingIndices.map(async (index) => {
                try {
                    const dto = await fetchStringDetailFromOpenMUC(index);
                    return { index, dto, source: 'openmuc' };
                } catch {
                    return { index, dto: null, source: 'openmuc' };
                }
            }));

            openmucResults.forEach(r => {
                const existingResult = results.find(res => res.index === r.index);
                if (existingResult) {
                    existingResult.dto = r.dto;
                    existingResult.source = r.source;
                }
            });
        }

        const mergedMap = new Map<number, BatteryString>();

        results.forEach(({ index, dto }) => {
            if (validIndicesSet && !validIndicesSet.has(index)) return;

            const existing = existingByIndex.get(index);
            if (dto && hasStringDetail(dto)) {
                const baseId = `str${index}`;
                const mapped = mapLatestValueDtoToBatteryString(dto, baseId, index, existing);
                mergedMap.set(index, mapped);
            } else if (existing && !forceScanAll) {
                mergedMap.set(index, existing);
            }
        });

        if (!forceScanAll) {
            existingByIndex.forEach((value, index) => {
                if (!mergedMap.has(index)) mergedMap.set(index, value);
            });
        }

        const mergedStrings = Array.from(mergedMap.values()).sort((a, b) => a.stringIndex - b.stringIndex);
        stringsState.set(mergedStrings);
        saveStringsToStorage(mergedStrings);

    } finally {
        isLoadingFromApi.set(false);
        stringsLoaded.set(true);
    }
}

export async function loadStringConfig(stringIndex: number): Promise<BatteryString | null> {
    try {
        let dto = await fetchStringDetailFromLatestValue(stringIndex);

        if (!dto || !hasStringDetail(dto)) {
            try {
                dto = await fetchStringDetailFromOpenMUC(stringIndex);
            } catch {
                // ignore
            }
        }

        if (dto && hasStringDetail(dto)) {
            const baseId = `str${stringIndex}`;
            const currentStrings = get(stringsState);
            const existing = currentStrings.find(s => s.stringIndex === stringIndex);

            const mapped = mapLatestValueDtoToBatteryString(dto, baseId, stringIndex, existing);

            stringsState.update(s => {
                const idx = s.findIndex(x => x.stringIndex === stringIndex);
                if (idx >= 0) {
                    const newState = [...s];
                    newState[idx] = mapped;
                    saveStringsToStorage(newState);
                    return newState;
                } else {
                    const newState = [...s, mapped].sort((a, b) => a.stringIndex - b.stringIndex);
                    saveStringsToStorage(newState);
                    return newState;
                }
            });
            return mapped;
        }
    } catch (e) {
        console.error(`Failed to load string ${stringIndex}`, e);
    }
    return null;
}

function buildStringIndicesToCheck(forceScanAll: boolean = false): number[] {
    const existingIndices = get(stringsState).map(s => s.stringIndex);

    if (forceScanAll) {
        const scanIndices = Array.from({ length: MAX_STRING_SCAN }, (_, i) => i + 1);
        const unique = Array.from(new Set([...existingIndices, ...scanIndices]))
            .filter(index => index > 0)
            .sort((a, b) => a - b);
        return unique;
    }

    return existingIndices.filter(index => index > 0).sort((a, b) => a - b);
}

async function fetchStringDetailFromLatestValue(stringIndex: number): Promise<LatestStringDetailDto | null> {
    const params = new URLSearchParams({ stringID: stringIndex.toString() });
    const res = await fetch(`${LATEST_VALUE_API}/string?${params}`);
    const json: LatestValueResponse<LatestStringDetailDto> = await res.json();
    return json?.success ? json.data : null;
}

async function fetchStringDetailFromOpenMUC(stringIndex: number): Promise<LatestStringDetailDto | null> {
    const channels = [
        `str${stringIndex}_string_name`,
        `str${stringIndex}_cell_qty`,
        `str${stringIndex}_cell_brand`,
        `str${stringIndex}_cell_model`,
        `str${stringIndex}_Cnominal`,
        `str${stringIndex}_Vnominal`,
        `str${stringIndex}_R_new`,
    ];

    const results = await Promise.all(channels.map(async (channel) => {
        try {
            const res = await fetch(`${BASE_URL}/channels/${channel}`);
            const json = await res.json();
            return { channel, value: json?.record?.value };
        } catch {
            return { channel, value: null };
        }
    }));

    const dto: LatestStringDetailDto = {};
    results.forEach(({ channel, value }) => {
        if (value !== null && value !== undefined) {
            if (channel.includes('string_name')) dto.stringName = String(value);
            else if (channel.includes('cell_qty')) dto.cellQty = Number(value);
            else if (channel.includes('cell_brand')) dto.cellBrand = String(value);
            else if (channel.includes('cell_model')) dto.cellModel = String(value);
            else if (channel.includes('Cnominal')) dto.cNominal = Number(value);
            else if (channel.includes('Vcutoff')) dto.vCutoff = Number(value);
            else if (channel.includes('Vfloat')) dto.vFloat = Number(value);
            else if (channel.includes('R_new')) dto.rNew = Number(value);
            else if (channel.includes('serial_port_id')) dto.serialPortId = String(value);
        }
    });

    return hasStringDetail(dto) ? dto : null;
}

function hasStringDetail(dto: LatestStringDetailDto | null): boolean {
    if (!dto) return false;
    if (dto.stringName && dto.stringName.trim().length > 0) return true;
    if (dto.cellBrand && dto.cellBrand.trim().length > 0) return true;
    if (dto.cellModel && dto.cellModel.trim().length > 0) return true;
    if (typeof dto.cellQty === 'number') return true;
    if (typeof dto.cNominal === 'number') return true;
    if (typeof dto.vCutoff === 'number') return true;
    if (typeof dto.vFloat === 'number') return true;
    return false;
}

function mapLatestValueDtoToBatteryString(
    dto: LatestStringDetailDto,
    baseId: string,
    stringIndex: number,
    existing?: BatteryString
): BatteryString {
    const normalize = (value?: number | null, fallback?: number): number => {
        if (value === null || value === undefined) return fallback ?? 0;
        const num = Number(value);
        return Number.isFinite(num) ? num : (fallback ?? 0);
    };

    return {
        id: existing?.id || uuidv4(),
        stringIndex,
        stringName: dto.stringName?.trim() || existing?.stringName || baseId,
        cellQty: normalize(dto.cellQty, existing?.cellQty),
        cellBrand: dto.cellBrand ?? existing?.cellBrand ?? '',
        cellModel: dto.cellModel ?? existing?.cellModel ?? '',
        ratedCapacity: normalize(dto.cNominal, existing?.ratedCapacity),
        cutoffVoltage: normalize(dto.vCutoff, existing?.cutoffVoltage),
        floatVoltage: normalize(dto.vFloat, existing?.floatVoltage),
        rNew: (dto.rNew !== undefined && dto.rNew !== null) ? Number(dto.rNew) : (existing?.rNew ?? 1450),
        serialPortId: dto.serialPortId ?? existing?.serialPortId ?? ''
    };
}

async function getStringIndicesFromOpenMUC(): Promise<number[]> {
    const res = await fetch(`${BASE_URL}/devices`);
    const json: { devices: string[] } = await res.json();
    const indices: number[] = [];
    const pattern = /^str(\d+)_(modbus|virtual)$/;

    json.devices?.forEach(deviceId => {
        const match = deviceId.match(pattern);
        if (match) {
            const index = parseInt(match[1], 10);
            if (!isNaN(index) && !indices.includes(index)) {
                indices.push(index);
            }
        }
    });
    return indices.sort((a, b) => a - b);
}

export async function addString(formData: StringFormData, portConfig: SerialPortConfig) {
    const currentStrings = get(stringsState);
    const maxIndex = currentStrings.reduce((max, s) => Math.max(max, s.stringIndex), 0);
    const newStringIndex = maxIndex + 1;

    const newStringConfig: BatteryString = {
        ...formData,
        id: uuidv4(),
        stringIndex: newStringIndex,
    };

    const exists = await checkDeviceExists(newStringIndex);
    if (exists) {
        throw new Error(`String ${newStringIndex} already exists on OpenMUC. Please reload the page.`);
    }

    await createStringApi(newStringIndex, formData, portConfig);

    stringsState.update(s => {
        const newState = [...s, newStringConfig];
        saveStringsToStorage(newState);
        return newState;
    });
    stringsLoaded.set(true);

    // Poll for update
    setTimeout(async () => {
        const dto = await fetchStringDetailFromLatestValue(newStringIndex);
        if (dto && hasStringDetail(dto)) {
            const enriched = mapLatestValueDtoToBatteryString(dto, `str${newStringIndex}`, newStringIndex, newStringConfig);
            stringsState.update(s => {
                const idx = s.findIndex(x => x.id === newStringConfig.id);
                if (idx >= 0) {
                    const newState = [...s];
                    newState[idx] = enriched;
                    saveStringsToStorage(newState);
                    return newState;
                }
                return s;
            });
        }
    }, 2000);

    return newStringConfig;
}

async function checkDeviceExists(stringIndex: number): Promise<boolean> {
    try {
        const [modbus, virtual] = await Promise.all([
            fetch(`${BASE_URL}/devices_v2/str${stringIndex}_modbus`).then(r => r.ok),
            fetch(`${BASE_URL}/devices_v2/str${stringIndex}_virtual`).then(r => r.ok)
        ]);
        return modbus || virtual;
    } catch {
        return false;
    }
}

async function createStringApi(s: number, formData: StringFormData, portConfig: SerialPortConfig) {
    const reqBody = {
        stringIndex: s,
        cellQty: formData.cellQty,
        stringName: formData.stringName,
        cellBrand: formData.cellBrand,
        cellModel: formData.cellModel,
        ratedCapacity: formData.ratedCapacity,
        cutoffVoltage: formData.cutoffVoltage,
        floatVoltage: formData.floatVoltage,
        rNew: formData.rNew,
        serialPortId: formData.serialPortId,
        portConfig: {
            port: portConfig.port,
            baudRate: portConfig.baudRate,
            dataBits: portConfig.dataBits,
            stopBits: portConfig.stopBits,
            parity: portConfig.parity
        }
    };

    await fetch(`${BASE_URL}/string`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(reqBody)
    });

    await new Promise(resolve => setTimeout(resolve, 1000));

    const putCalls = [
        apiPutChannel(`str${s}_string_name`, formData.stringName),
        apiPutChannel(`str${s}_cell_qty`, formData.cellQty),
        apiPutChannel(`str${s}_cell_brand`, formData.cellBrand),
        apiPutChannel(`str${s}_cell_model`, formData.cellModel),
        apiPutChannel(`str${s}_Cnominal`, formData.ratedCapacity),
        apiPutChannel(`str${s}_Vcutoff`, formData.cutoffVoltage),
        apiPutChannel(`str${s}_Vfloat`, formData.floatVoltage),
        apiPutChannel(`str${s}_R_new`, formData.rNew ?? 1450),
        apiPutChannel(`str${s}_serial_port_id`, formData.serialPortId)
    ];

    await Promise.all(putCalls);
}

async function apiPutChannel(channelId: string, value: string | number | boolean) {
    const payload = { record: { flag: 'VALID', value } };
    await fetch(`${BASE_URL}/channels/${channelId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    });
}

export async function deleteString(id: string) {
    const stringConfig = get(stringsState).find(s => s.id === id);
    if (!stringConfig) throw new Error('String Config not found');

    const s = stringConfig.stringIndex;

    await apiDelete(`/devices_v2/str${s}_modbus`);
    await apiDelete(`/devices_v2/str${s}_virtual`);

    try {
        await fetch(`${LATEST_VALUE_API}/delete-string?stringId=${s}`, { method: 'POST' });
    } catch (e) {
        console.warn('Failed to delete metadata', e);
    }

    stringsState.update(state => {
        const newState = state.filter(item => item.id !== id);
        saveStringsToStorage(newState);
        return newState;
    });
    stringsLoaded.set(true);
}

async function apiDelete(path: string) {
    try {
        await fetch(`${BASE_URL}${path}`, { method: 'DELETE' });
    } catch (e) {
        // ignore 404
    }
}

export async function updateString(stringId: string, formData: StringFormData, portConfig: SerialPortConfig) {
    const stringConfig = get(stringsState).find(s => s.id === stringId);
    if (!stringConfig) throw new Error('String Config not found');

    const s = stringConfig.stringIndex;

    await apiDelete(`/devices_v2/str${s}_modbus`);
    await apiDelete(`/devices_v2/str${s}_virtual`);

    await createStringApi(s, formData, portConfig);

    stringsState.update(state => {
        const updatedConfig = { ...stringConfig, ...formData };
        const newState = state.map(str => str.id === stringId ? updatedConfig : str);
        saveStringsToStorage(newState);
        return newState;
    });
}
