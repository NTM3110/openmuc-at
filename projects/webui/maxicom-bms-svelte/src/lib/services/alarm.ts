import type { BatteryString } from '../interfaces/string.interface';
import type { CellData, StringSummaryData } from './openmuc';

export function getCellAlarmReasons(cell: CellData, config: BatteryString): string[] {
    const reasons: string[] = [];
    const highResistanceThreshold = config.highResistanceThreshold ??
        Math.round(config.rNew * 2.3);

    if (isAbove(cell.Rst, highResistanceThreshold)) {
        reasons.push('High internal resistance');
    }
    if (isAbove(cell.Temp, config.highTemperatureThreshold)) {
        reasons.push('High temperature');
    }
    if (isBelow(cell.Vol, config.lowVoltageThreshold)) {
        reasons.push('Low voltage');
    }
    if (isAbove(cell.Vol, config.highVoltageThreshold)) {
        reasons.push('High voltage');
    }

    return reasons;
}

export function getPrimaryAlarmLabel(reasons: string[]): 'IR' | 'T' | 'V' | 'String alarm' | '' {
    if (reasons.includes('High internal resistance')) {
        return 'IR';
    }
    if (reasons.includes('High temperature')) {
        return 'T';
    }
    if (reasons.includes('Low voltage') || reasons.includes('High voltage')) {
        return 'V';
    }
    if (reasons.includes('String alarm')) {
        return 'String alarm';
    }
    return '';
}

export function getStringAlarmReasons(summary: Pick<StringSummaryData,
    'maxRstValue' | 'maxTempValue' | 'minVoltageValue' | 'maxVoltageValue'>,
    config: BatteryString
): string[] {
    const reasons: string[] = [];
    const highResistanceThreshold = config.highResistanceThreshold ??
        Math.round(config.rNew * 2.3);

    if (isAbove(summary.maxRstValue, highResistanceThreshold)) {
        reasons.push('High internal resistance');
    }
    if (isAbove(summary.maxTempValue, config.highTemperatureThreshold)) {
        reasons.push('High temperature');
    }
    if (isBelow(summary.minVoltageValue, config.lowVoltageThreshold)) {
        reasons.push('Low voltage');
    }
    if (isAbove(summary.maxVoltageValue, config.highVoltageThreshold)) {
        reasons.push('High voltage');
    }

    return reasons;
}

function isAbove(value: number | null, threshold: number | undefined): boolean {
    return value !== null && threshold !== undefined && threshold > 0 && value >= threshold;
}

function isBelow(value: number | null, threshold: number | undefined): boolean {
    return value !== null && threshold !== undefined && threshold > 0 && value <= threshold;
}
