<script lang="ts">
    import { createEventDispatcher, onMount } from "svelte";
    import type { StringFormData, BatteryString } from "$lib/interfaces/string.interface";
    import { configuredPorts } from "$lib/services/communication";
    import { kbd } from "$lib/actions/kbd";

    export let stringConfig: BatteryString | null = null;
    export let isOpen: boolean = false;

    const dispatch = createEventDispatcher();
    const DEFAULT_R_NEW = 1450;
    const DEFAULT_HIGH_RESISTANCE_RATIO = 2.3;

    function defaultHighResistance(rNew: number): number {
        return Math.round(rNew * DEFAULT_HIGH_RESISTANCE_RATIO);
    }

    let formData: StringFormData = {
        stringName: "",
        cellBrand: "",
        cellModel: "",
        cellQty: 0,
        ratedCapacity: 0,
        cutoffVoltage: 0,
        floatVoltage: 0,
        rNew: DEFAULT_R_NEW,
        highResistanceThreshold: defaultHighResistance(DEFAULT_R_NEW),
        highTemperatureThreshold: 0,
        lowVoltageThreshold: 0,
        highVoltageThreshold: 0,
        serialPortId: ""
    };

    let isEditMode = false;
    let hasInvalidVoltageRange = false;

    $: if (isOpen) {
        if (stringConfig) {
            isEditMode = true;
            formData = {
                stringName: stringConfig.stringName,
                cellBrand: stringConfig.cellBrand,
                cellModel: stringConfig.cellModel,
                cellQty: stringConfig.cellQty,
                ratedCapacity: stringConfig.ratedCapacity,
                cutoffVoltage: stringConfig.cutoffVoltage,
                floatVoltage: stringConfig.floatVoltage,
                rNew: stringConfig.rNew,
                highResistanceThreshold: stringConfig.highResistanceThreshold ?? defaultHighResistance(stringConfig.rNew),
                highTemperatureThreshold: stringConfig.highTemperatureThreshold ?? 0,
                lowVoltageThreshold: stringConfig.lowVoltageThreshold ?? 0,
                highVoltageThreshold: stringConfig.highVoltageThreshold ?? 0,
                serialPortId: stringConfig.serialPortId
            };
        } else {
            isEditMode = false;
            resetForm();
        }
    }

    function resetForm() {
        formData = {
            stringName: "",
            cellBrand: "",
            cellModel: "",
            cellQty: 0,
            ratedCapacity: 0,
            cutoffVoltage: 0,
            floatVoltage: 0,
            rNew: DEFAULT_R_NEW,
            highResistanceThreshold: defaultHighResistance(DEFAULT_R_NEW),
            highTemperatureThreshold: 0,
            lowVoltageThreshold: 0,
            highVoltageThreshold: 0,
            serialPortId: $configuredPorts.length > 0 ? $configuredPorts[0].id : ""
        };
    }

    function close() {
        dispatch("cancel");
    }

    function save() {
        if (
            formData.lowVoltageThreshold > 0 &&
            formData.highVoltageThreshold > 0 &&
            formData.lowVoltageThreshold >= formData.highVoltageThreshold
        ) {
            return;
        }
        dispatch("save", { ...formData });
    }

    $: hasInvalidVoltageRange =
        formData.lowVoltageThreshold > 0 &&
        formData.highVoltageThreshold > 0 &&
        formData.lowVoltageThreshold >= formData.highVoltageThreshold;
</script>

{#if isOpen}
    <div class="modal-backdrop" onclick={close} role="button" tabindex="0" onkeydown={(e) => e.key === 'Escape' && close()}>
        <div class="modal-content" onclick={(e) => e.stopPropagation()}>
            <div class="modal-header">
                <h2>{isEditMode ? "Edit String" : "Add String"}</h2>
                <button class="close-button" onclick={close} aria-label="Close">
                    <i class="bi bi-x-lg"></i>
                </button>
            </div>
            <div class="modal-body">
                <div class="form-row">
                    <div class="form-group">
                        <label for="stringName">String Name</label>
                        <input type="text" id="stringName" bind:value={formData.stringName} placeholder="e.g. String 1" use:kbd />
                    </div>
                    <div class="form-group">
                        <label for="rNew">R_NEW (uOhm)</label>
                        <input type="number" id="rNew" bind:value={formData.rNew} min="0" use:kbd />
                    </div>
                </div>

                <div class="form-row">
                    <div class="form-group">
                        <label for="cellQty">Cell Qty</label>
                        <input type="number" id="cellQty" bind:value={formData.cellQty} min="1" use:kbd />
                    </div>
                    <div class="form-group">
                        <label for="cellBrand">Cell Brand</label>
                        <input type="text" id="cellBrand" bind:value={formData.cellBrand} use:kbd />
                    </div>
                </div>
                <div class="form-row">
                    <div class="form-group">
                        <label for="cellModel">Cell Model</label>
                        <input type="text" id="cellModel" bind:value={formData.cellModel} use:kbd />
                    </div>
                    <div class="form-group">
                        <label for="ratedCapacity">Rated Capacity (Ah)</label>
                        <input type="number" id="ratedCapacity" bind:value={formData.ratedCapacity} step="0.1" use:kbd />
                    </div>
                </div>

                <div class="form-row">
                    <div class="form-group">
                        <label for="cutoffVoltage">Cutoff Voltage (V)</label>
                        <input type="number" id="cutoffVoltage" bind:value={formData.cutoffVoltage} step="0.01" use:kbd />
                    </div>
                    <div class="form-group">
                        <label for="floatVoltage">Float Voltage (V)</label>
                        <input type="number" id="floatVoltage" bind:value={formData.floatVoltage} step="0.01" use:kbd />
                    </div>
                </div>
                <div class="form-row">
                    <div class="form-group">
                        <label for="serialPort">Serial Port</label>
                        <select id="serialPort" bind:value={formData.serialPortId}>
                            {#each $configuredPorts as port}
                                <option value={port.id}>{port.alias || port.id}</option>
                            {/each}
                        </select>
                    </div>
                </div>

                <section class="alarm-settings" aria-labelledby="alarm-settings-title">
                    <div class="section-header">
                        <h3 id="alarm-settings-title">Alarm Settings</h3>
                        <span>0 = disable</span>
                    </div>
                    <div class="threshold-grid">
                        <div class="form-group">
                            <label for="highResistanceThreshold">IR High (uOhm)</label>
                            <input type="number" id="highResistanceThreshold" bind:value={formData.highResistanceThreshold} min="0" step="1" use:kbd />
                        </div>
                        <div class="form-group">
                            <label for="highTemperatureThreshold">T High (C)</label>
                            <input type="number" id="highTemperatureThreshold" bind:value={formData.highTemperatureThreshold} min="0" step="0.1" use:kbd />
                        </div>
                        <div class="form-group">
                            <label for="lowVoltageThreshold">V Low (V)</label>
                            <input type="number" id="lowVoltageThreshold" bind:value={formData.lowVoltageThreshold} min="0" step="0.001" use:kbd />
                        </div>
                        <div class="form-group">
                            <label for="highVoltageThreshold">V High (V)</label>
                            <input type="number" id="highVoltageThreshold" bind:value={formData.highVoltageThreshold} min="0" step="0.001" use:kbd />
                        </div>
                    </div>
                    {#if hasInvalidVoltageRange}
                        <p class="validation-error">Low voltage threshold must be below the high voltage threshold.</p>
                    {/if}
                </section>
            </div>
            <div class="modal-footer">
                <button class="btn-secondary" onclick={close}>Cancel</button>
                <button class="btn-primary" onclick={save} disabled={hasInvalidVoltageRange}>Save</button>
            </div>
        </div>
    </div>
{/if}

<style>
    .modal-backdrop {
        position: fixed;
        top: 0;
        left: 0;
        width: 100%;
        height: 100%;
        background-color: rgba(0, 0, 0, 0.5);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 1000;
        animation: fadeIn 0.2s ease-out;
    }

    .modal-content {
        background-color: white;
        border-radius: 12px;
        width: 90%;
        max-width: 600px;
        max-height: 85vh;
        display: flex;
        flex-direction: column;
        box-shadow: 0 4px 20px rgba(0, 0, 0, 0.15);
        animation: slideUp 0.3s ease-out;
    }

    .modal-header {
        padding: 20px 24px;
        border-bottom: 1px solid #eee;
        display: flex;
        justify-content: space-between;
        align-items: center;
        flex-shrink: 0;
    }

    .modal-header h2 {
        margin: 0;
        font-size: 1.25rem;
        color: var(--primary-color);
    }

    .close-button {
        background: none;
        border: none;
        font-size: 1.25rem;
        cursor: pointer;
        color: #666;
        padding: 4px;
        border-radius: 4px;
        transition: background-color 0.2s;
    }

    .close-button:hover {
        background-color: #f5f5f5;
    }

    .modal-body {
        padding: 24px;
        display: flex;
        flex-direction: column;
        gap: 16px;
        overflow-y: auto;
        flex-grow: 1;
    }

    .form-group {
        display: flex;
        flex-direction: column;
        gap: 8px;
        flex: 1;
    }

    .form-row {
        display: flex;
        gap: 16px;
    }

    .alarm-settings {
        border: 1px solid #e5e7eb;
        border-radius: 10px;
        padding: 16px;
        background: #f9fafb;
        display: flex;
        flex-direction: column;
        gap: 12px;
    }

    .section-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 12px;
        padding-bottom: 4px;
    }

    .section-header h3 {
        font-size: 1rem;
        margin: 0;
        color: #1f2937;
    }

    .section-header span {
        margin: 0;
        font-size: 0.8rem;
        color: #6b7280;
    }

    .threshold-grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 14px 16px;
    }

    .alarm-settings .validation-error {
        margin: 0;
        font-size: 0.8rem;
        color: #dc2626;
        font-weight: 500;
    }

    label {
        font-size: 0.875rem;
        font-weight: 500;
        color: #424242;
    }

    input, select {
        padding: 10px 12px;
        border: 1px solid #e0e0e0;
        border-radius: 6px;
        font-size: 0.9375rem;
        transition: border-color 0.2s;
    }

    input:focus, select:focus {
        outline: none;
        border-color: var(--primary-color);
    }

    .modal-footer {
        padding: 16px 24px;
        border-top: 1px solid #eee;
        display: flex;
        justify-content: flex-end;
        gap: 12px;
        flex-shrink: 0;
    }

    button {
        padding: 10px 20px;
        border-radius: 6px;
        font-size: 0.9375rem;
        font-weight: 500;
        cursor: pointer;
        border: none;
        transition: all 0.2s;
    }

    .btn-secondary {
        background-color: #f5f5f5;
        color: #616161;
    }

    .btn-secondary:hover {
        background-color: #e0e0e0;
    }

    .btn-primary {
        background-color: var(--primary-color);
        color: white;
    }

    .btn-primary:hover {
        background-color: #013bb5;
    }

    .btn-primary:disabled {
        cursor: not-allowed;
        opacity: 0.55;
    }

    @keyframes fadeIn {
        from { opacity: 0; }
        to { opacity: 1; }
    }

    @keyframes slideUp {
        from { transform: translateY(20px); opacity: 0; }
        to { transform: translateY(0); opacity: 1; }
    }

    @media (max-width: 640px) {
        .form-row,
        .threshold-grid {
            grid-template-columns: 1fr;
            flex-direction: column;
        }
    }
</style>
