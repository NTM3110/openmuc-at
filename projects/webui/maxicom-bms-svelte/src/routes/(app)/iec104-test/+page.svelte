<script lang="ts">
    import { onMount } from "svelte";
    import { kbd } from "$lib/actions/kbd";

    type CommandInputKind = "bytes" | "boolean" | "float" | "integer";
    type CommandInput = string | boolean;
    type CommandWriteValue = number[] | boolean | number;

    interface MeasurementPoint {
        id: string;
        label: string;
        typeLabel: string;
        valueType: string;
        description: string;
    }

    interface CommandPoint {
        id: string;
        label: string;
        typeLabel: string;
        valueType: string;
        feedbackId?: string;
        feedbackLabel?: string;
        feedbackTypeLabel?: string;
        confirmationId: string;
        inputKind: CommandInputKind;
        byteLength?: number;
        defaultInput: CommandInput;
    }

    interface ChannelRecord {
        flag: string;
        timestamp?: number | string;
        value: unknown;
    }

    interface ChannelState {
        loading: boolean;
        error: string;
        record: ChannelRecord | null;
        loadedAt: number | null;
    }

    interface CommandState {
        input: CommandInput;
        writing: boolean;
        writeError: string;
        writeMessage: string;
        feedback: ChannelState;
        confirmation: ChannelState;
    }

    interface DeviceConfig {
        id: string;
        description?: string | null;
        deviceAddress?: string | null;
        settings?: string | null;
        samplingTimeout?: number | null;
        connectRetryInterval?: number | null;
        disabled?: boolean | null;
    }

    const IEC104_DEVICE_ID = "lib60870_demo_server";

    const MEASUREMENT_POINTS: MeasurementPoint[] = [
        {
            id: "iec104_test_sp_1",
            label: "Single point",
            typeLabel: "Type 1 M_SP_NA",
            valueType: "BOOLEAN",
            description: "Single point indication at IOA 1001"
        },
        {
            id: "iec104_test_dp_3",
            label: "Double point",
            typeLabel: "Type 3 M_DP_NA",
            valueType: "INTEGER",
            description: "Double point indication at IOA 1003"
        },
        {
            id: "iec104_test_st_5",
            label: "Step position",
            typeLabel: "Type 5 M_ST_NA",
            valueType: "INTEGER",
            description: "Step position information at IOA 1005"
        },
        {
            id: "iec104_test_bo_7",
            label: "Bitstring",
            typeLabel: "Type 7 M_BO_NA",
            valueType: "BYTE_ARRAY[4]",
            description: "Bitstring indication at IOA 1007"
        },
        {
            id: "iec104_test_me_na_9",
            label: "Normalized measurement",
            typeLabel: "Type 9 M_ME_NA",
            valueType: "DOUBLE",
            description: "Normalized measured value at IOA 1009"
        },
        {
            id: "iec104_test_me_nb_11",
            label: "Scaled measurement",
            typeLabel: "Type 11 M_ME_NB",
            valueType: "INTEGER",
            description: "Scaled measured value at IOA 1011"
        },
        {
            id: "iec104_test_me_nc_13",
            label: "Float measurement",
            typeLabel: "Type 13 M_ME_NC",
            valueType: "DOUBLE",
            description: "Short floating point value at IOA 1013"
        },
        {
            id: "iec104_test_it_15",
            label: "Integrated total",
            typeLabel: "Type 15 M_IT_NA",
            valueType: "INTEGER",
            description: "Integrated totals at IOA 1015"
        }
    ];

    const COMMAND_POINTS: CommandPoint[] = [
        {
            id: "iec104_cmd_sc_45",
            label: "Single command",
            typeLabel: "Type 45 C_SC_NA",
            valueType: "BYTE_ARRAY[3]",
            feedbackId: "iec104_feedback_sc_45",
            feedbackLabel: "Single point feedback",
            feedbackTypeLabel: "M_SP_NA feedback",
            confirmationId: "iec104_confirm_sc_45",
            inputKind: "bytes",
            byteLength: 3,
            defaultInput: "1,-1,0"
        },
        {
            id: "iec104_cmd_dc_46",
            label: "Double command",
            typeLabel: "Type 46 C_DC_NA",
            valueType: "BOOLEAN",
            feedbackId: "iec104_feedback_dc_46",
            feedbackLabel: "Double point feedback",
            feedbackTypeLabel: "M_DP_NA feedback",
            confirmationId: "iec104_confirm_dc_46",
            inputKind: "boolean",
            defaultInput: true
        },
        {
            id: "iec104_cmd_rc_47",
            label: "Regulating step",
            typeLabel: "Type 47 C_RC_NA",
            valueType: "BYTE_ARRAY[3]",
            feedbackId: "iec104_feedback_rc_47",
            feedbackLabel: "Step position feedback",
            feedbackTypeLabel: "M_ST_NA feedback",
            confirmationId: "iec104_confirm_rc_47",
            inputKind: "bytes",
            byteLength: 3,
            defaultInput: "1,-1,0"
        },
        {
            id: "iec104_cmd_se_na_48",
            label: "Normalized setpoint",
            typeLabel: "Type 48 C_SE_NA",
            valueType: "BYTE_ARRAY[6]",
            feedbackId: "iec104_feedback_se_na_48",
            feedbackLabel: "Normalized measurement feedback",
            feedbackTypeLabel: "M_ME_NA feedback",
            confirmationId: "iec104_confirm_se_na_48",
            inputKind: "bytes",
            byteLength: 6,
            defaultInput: "0,0,0,1,0,-1"
        },
        {
            id: "iec104_cmd_se_nc_50",
            label: "Float setpoint",
            typeLabel: "Type 50 C_SE_NC",
            valueType: "FLOAT",
            feedbackId: "iec104_feedback_se_nc_50",
            feedbackLabel: "Float measurement feedback",
            feedbackTypeLabel: "M_ME_NC feedback",
            confirmationId: "iec104_confirm_se_nc_50",
            inputKind: "float",
            defaultInput: "1"
        },
        {
            id: "iec104_cmd_bo_51",
            label: "Bitstring command",
            typeLabel: "Type 51 C_BO_NA",
            valueType: "INTEGER",
            feedbackId: "iec104_feedback_bo_51",
            feedbackLabel: "Bitstring feedback",
            feedbackTypeLabel: "M_BO_NA feedback",
            confirmationId: "iec104_confirm_bo_51",
            inputKind: "integer",
            defaultInput: "1"
        },
        {
            id: "iec104_cmd_invalid_45",
            label: "Invalid single command",
            typeLabel: "Type 45 C_SC_NA",
            valueType: "BYTE_ARRAY[3]",
            confirmationId: "iec104_confirm_invalid_45",
            inputKind: "bytes",
            byteLength: 3,
            defaultInput: "1,1,0"
        }
    ];

    let visibleMeasurementIds = $state(MEASUREMENT_POINTS.map((point) => point.id));
    let visibleCommandIds = $state(COMMAND_POINTS.map((point) => point.id));
    let measurementToAdd = $state("");
    let commandToAdd = $state("");
    let refreshingMeasurements = $state(false);
    let refreshingFeedback = $state(false);
    let loadingDeviceConfig = $state(false);
    let savingDeviceAddress = $state(false);
    let deviceConfigError = $state("");
    let deviceAddressMessage = $state("");
    let deviceConfig = $state<DeviceConfig | null>(null);
    let deviceAddressInput = $state("");

    let measurementStates = $state(buildMeasurementStates());
    let commandStates = $state(buildCommandStates());

    const visibleMeasurements = $derived(
        MEASUREMENT_POINTS.filter((point) => visibleMeasurementIds.includes(point.id))
    );
    const hiddenMeasurements = $derived(
        MEASUREMENT_POINTS.filter((point) => !visibleMeasurementIds.includes(point.id))
    );
    const visibleCommands = $derived(
        COMMAND_POINTS.filter((point) => visibleCommandIds.includes(point.id))
    );
    const hiddenCommands = $derived(
        COMMAND_POINTS.filter((point) => !visibleCommandIds.includes(point.id))
    );

    onMount(() => {
        loadDeviceConfig();
        refreshVisibleMeasurements();
        refreshVisibleFeedback();
    });

    function emptyChannelState(): ChannelState {
        return {
            loading: false,
            error: "",
            record: null,
            loadedAt: null
        };
    }

    function buildMeasurementStates(): Record<string, ChannelState> {
        const states: Record<string, ChannelState> = {};
        for (const point of MEASUREMENT_POINTS) {
            states[point.id] = emptyChannelState();
        }
        return states;
    }

    function buildCommandStates(): Record<string, CommandState> {
        const states: Record<string, CommandState> = {};
        for (const point of COMMAND_POINTS) {
            states[point.id] = {
                input: point.defaultInput,
                writing: false,
                writeError: "",
                writeMessage: "",
                feedback: emptyChannelState(),
                confirmation: emptyChannelState()
            };
        }
        return states;
    }

    function orderedIds<T extends { id: string }>(ids: string[], definitions: T[]) {
        const idSet = new Set(ids);
        return definitions.filter((definition) => idSet.has(definition.id)).map((definition) => definition.id);
    }

    function setMeasurementState(id: string, patch: Partial<ChannelState>) {
        measurementStates[id] = {
            ...(measurementStates[id] ?? emptyChannelState()),
            ...patch
        };
    }

    function setCommandState(id: string, patch: Partial<CommandState>) {
        commandStates[id] = {
            ...(commandStates[id] ?? {
                input: "",
                writing: false,
                writeError: "",
                writeMessage: "",
                feedback: emptyChannelState(),
                confirmation: emptyChannelState()
            }),
            ...patch
        };
    }

    function setFeedbackState(commandId: string, patch: Partial<ChannelState>) {
        const current = commandStates[commandId];
        if (!current) {
            return;
        }

        commandStates[commandId] = {
            ...current,
            feedback: {
                ...current.feedback,
                ...patch
            }
        };
    }

    function setConfirmationState(commandId: string, patch: Partial<ChannelState>) {
        const current = commandStates[commandId];
        if (!current) {
            return;
        }

        commandStates[commandId] = {
            ...current,
            confirmation: {
                ...current.confirmation,
                ...patch
            }
        };
    }

    function isRecordObject(value: unknown): value is Record<string, unknown> {
        return typeof value === "object" && value !== null;
    }

    async function parseJsonSafe(response: Response): Promise<unknown> {
        const text = await response.text();
        if (!text) {
            return null;
        }

        try {
            return JSON.parse(text);
        } catch {
            return null;
        }
    }

    function responseMessage(json: unknown) {
        if (!isRecordObject(json)) {
            return "";
        }

        const message = json.message;
        if (typeof message === "string" && message.length > 0) {
            return message;
        }

        const error = json.error;
        if (typeof error === "string" && error.length > 0) {
            return error;
        }

        return "";
    }

    function readDeviceConfig(json: unknown): DeviceConfig {
        if (!isRecordObject(json) || !isRecordObject(json.configs)) {
            throw new Error(`GET /rest/devices/${IEC104_DEVICE_ID}/configs returned no configs`);
        }

        const configs = json.configs;
        return {
            id: typeof configs.id === "string" ? configs.id : IEC104_DEVICE_ID,
            description: typeof configs.description === "string" ? configs.description : null,
            deviceAddress: typeof configs.deviceAddress === "string" ? configs.deviceAddress : "",
            settings: typeof configs.settings === "string" ? configs.settings : null,
            samplingTimeout: typeof configs.samplingTimeout === "number" ? configs.samplingTimeout : null,
            connectRetryInterval:
                typeof configs.connectRetryInterval === "number" ? configs.connectRetryInterval : null,
            disabled: typeof configs.disabled === "boolean" ? configs.disabled : null
        };
    }

    async function loadDeviceConfig() {
        loadingDeviceConfig = true;
        deviceConfigError = "";
        deviceAddressMessage = "";

        try {
            const response = await fetch(`/rest/devices/${IEC104_DEVICE_ID}/configs`, {
                method: "GET",
                headers: { Accept: "application/json" }
            });
            const json = await parseJsonSafe(response);

            if (!response.ok) {
                const message = responseMessage(json);
                throw new Error(
                    `GET /rest/devices/${IEC104_DEVICE_ID}/configs failed with HTTP ${response.status}${message ? `: ${message}` : ""}`
                );
            }

            const configs = readDeviceConfig(json);
            deviceConfig = configs;
            deviceAddressInput = configs.deviceAddress ?? "";
        } catch (error) {
            deviceConfigError = errorMessage(error);
        } finally {
            loadingDeviceConfig = false;
        }
    }

    function validateDeviceAddress(address: string) {
        const parts = new Map<string, string>();
        for (const part of address.split(";")) {
            const [rawKey, ...rawValue] = part.split("=");
            const key = rawKey?.trim();
            const value = rawValue.join("=").trim();
            if (key) {
                parts.set(key, value);
            }
        }

        const host = parts.get("h");
        const portText = parts.get("p");
        const commonAddressText = parts.get("ca");
        if (!host || !portText || !commonAddressText) {
            throw new Error("Device address must include h, p, and ca, for example h=192.168.4.58;p=2404;ca=1");
        }

        const port = Number(portText);
        const commonAddress = Number(commonAddressText);
        if (!Number.isInteger(port) || port < 1 || port > 65535) {
            throw new Error("Device address port p must be an integer from 1 to 65535");
        }
        if (!Number.isInteger(commonAddress) || commonAddress < 1) {
            throw new Error("Device address common address ca must be a positive integer");
        }
    }

    async function saveDeviceAddress() {
        const address = deviceAddressInput.trim();
        deviceConfigError = "";
        deviceAddressMessage = "";

        try {
            validateDeviceAddress(address);
        } catch (error) {
            deviceConfigError = errorMessage(error);
            return;
        }

        let baseConfig = deviceConfig;
        if (!baseConfig) {
            await loadDeviceConfig();
            baseConfig = deviceConfig;
        }
        if (!baseConfig) {
            deviceConfigError = "Load the current device config before saving the address.";
            return;
        }

        savingDeviceAddress = true;
        try {
            const payload = {
                configs: {
                    ...baseConfig,
                    id: IEC104_DEVICE_ID,
                    deviceAddress: address
                }
            };
            const response = await fetch(`/rest/devices/${IEC104_DEVICE_ID}/configs`, {
                method: "PUT",
                headers: {
                    "Content-Type": "application/json",
                    Accept: "application/json"
                },
                body: JSON.stringify(payload)
            });
            const json = await parseJsonSafe(response);

            if (!response.ok) {
                const message = responseMessage(json);
                throw new Error(
                    `PUT /rest/devices/${IEC104_DEVICE_ID}/configs failed with HTTP ${response.status}${message ? `: ${message}` : ""}`
                );
            }

            deviceConfig = {
                ...baseConfig,
                id: IEC104_DEVICE_ID,
                deviceAddress: address
            };
            deviceAddressInput = address;
            deviceAddressMessage = "Server address saved. Restart or reconnect the IEC104 device if the current connection is already open.";
        } catch (error) {
            deviceConfigError = errorMessage(error);
        } finally {
            savingDeviceAddress = false;
        }
    }

    async function readChannel(channelId: string): Promise<ChannelRecord> {
        const response = await fetch(`/rest/channels/${encodeURIComponent(channelId)}`, {
            method: "GET",
            headers: { Accept: "application/json" }
        });
        const json = await parseJsonSafe(response);

        if (!response.ok) {
            const message = responseMessage(json);
            throw new Error(
                `GET /rest/channels/${channelId} failed with HTTP ${response.status}${message ? `: ${message}` : ""}`
            );
        }

        if (!isRecordObject(json) || !isRecordObject(json.record)) {
            throw new Error(`GET /rest/channels/${channelId} returned no record`);
        }

        const rawRecord = json.record;
        const flag = typeof rawRecord.flag === "string" ? rawRecord.flag : "UNKNOWN";
        const timestamp =
            typeof rawRecord.timestamp === "number" || typeof rawRecord.timestamp === "string"
                ? rawRecord.timestamp
                : undefined;

        return {
            flag,
            timestamp,
            value: rawRecord.value
        };
    }

    async function refreshMeasurement(point: MeasurementPoint) {
        setMeasurementState(point.id, { loading: true, error: "" });

        try {
            const record = await readChannel(point.id);
            setMeasurementState(point.id, {
                loading: false,
                error: "",
                record,
                loadedAt: Date.now()
            });
        } catch (error) {
            setMeasurementState(point.id, {
                loading: false,
                error: errorMessage(error)
            });
        }
    }

    async function refreshVisibleMeasurements() {
        refreshingMeasurements = true;
        try {
            await Promise.all(visibleMeasurements.map((point) => refreshMeasurement(point)));
        } finally {
            refreshingMeasurements = false;
        }
    }

    async function refreshFeedback(command: CommandPoint) {
        if (!command.feedbackId) {
            setFeedbackState(command.id, {
                loading: false,
                error: "",
                record: null,
                loadedAt: Date.now()
            });
            return;
        }

        setFeedbackState(command.id, { loading: true, error: "" });

        try {
            const record = await readChannel(command.feedbackId);
            setFeedbackState(command.id, {
                loading: false,
                error: "",
                record,
                loadedAt: Date.now()
            });
        } catch (error) {
            setFeedbackState(command.id, {
                loading: false,
                error: errorMessage(error)
            });
        }
    }

    async function refreshVisibleFeedback() {
        refreshingFeedback = true;
        try {
            await Promise.all(visibleCommands.filter((command) => command.feedbackId).map((command) => refreshFeedback(command)));
        } finally {
            refreshingFeedback = false;
        }
    }

    async function refreshConfirmation(command: CommandPoint) {
        setConfirmationState(command.id, { loading: true, error: "" });

        try {
            const record = await readChannel(command.confirmationId);
            setConfirmationState(command.id, {
                loading: false,
                error: "",
                record,
                loadedAt: Date.now()
            });
        } catch (error) {
            setConfirmationState(command.id, {
                loading: false,
                error: errorMessage(error)
            });
        }
    }

    async function waitForActivationConfirmation(command: CommandPoint, sentAt: number) {
        const deadline = Date.now() + 2000;
        setConfirmationState(command.id, { loading: true, error: "", record: null });

        while (Date.now() < deadline) {
            try {
                const record = await readChannel(command.confirmationId);
                const timestamp = recordTimestampMs(record.timestamp);
                if (record.flag === "VALID" && timestamp !== null && timestamp >= sentAt) {
                    setConfirmationState(command.id, {
                        loading: false,
                        error: "",
                        record,
                        loadedAt: Date.now()
                    });
                    return true;
                }
            } catch (error) {
                setConfirmationState(command.id, {
                    loading: false,
                    error: errorMessage(error)
                });
                return false;
            }

            await new Promise((resolve) => setTimeout(resolve, 250));
        }

        setConfirmationState(command.id, {
            loading: false,
            error: "No activation confirmation observed yet. Check IEC104 server logs or refresh confirmation.",
            loadedAt: Date.now()
        });
        return false;
    }

    function setCommandInput(commandId: string, input: CommandInput) {
        const current = commandStates[commandId];
        if (!current) {
            return;
        }

        setCommandState(commandId, {
            input,
            writeError: "",
            writeMessage: ""
        });
    }

    function getCommandInputText(commandId: string) {
        const input = commandStates[commandId]?.input;
        return typeof input === "string" ? input : "";
    }

    function getCommandInputBoolean(commandId: string) {
        const input = commandStates[commandId]?.input;
        return typeof input === "boolean" ? input : false;
    }

    function parseByteArray(command: CommandPoint): number[] {
        const input = getCommandInputText(command.id)
            .trim()
            .replace(/^\[/, "")
            .replace(/\]$/, "");

        const values = input
            .split(",")
            .map((part) => part.trim())
            .filter((part) => part.length > 0)
            .map((part) => Number(part));

        if (command.byteLength !== undefined && values.length !== command.byteLength) {
            throw new Error(`${command.label} requires ${command.byteLength} byte values`);
        }

        for (const value of values) {
            if (!Number.isInteger(value) || value < -128 || value > 255) {
                throw new Error(`${command.label} byte values must be integers from -128 to 255`);
            }
        }

        return values;
    }

    function parseCommandValue(command: CommandPoint): CommandWriteValue {
        if (command.inputKind === "boolean") {
            return getCommandInputBoolean(command.id);
        }

        if (command.inputKind === "bytes") {
            return parseByteArray(command);
        }

        const input = getCommandInputText(command.id).trim();
        const value = Number(input);
        if (!Number.isFinite(value)) {
            throw new Error(`${command.label} requires a numeric value`);
        }

        if (command.inputKind === "integer" && !Number.isInteger(value)) {
            throw new Error(`${command.label} requires an integer value`);
        }

        return value;
    }

    async function runCommand(command: CommandPoint) {
        setCommandState(command.id, {
            writing: true,
            writeError: "",
            writeMessage: ""
        });

        let value: CommandWriteValue;
        try {
            value = parseCommandValue(command);
        } catch (error) {
            setCommandState(command.id, {
                writing: false,
                writeError: errorMessage(error)
            });
            return;
        }

        const sentAt = Date.now();
        const payload = {
            record: {
                flag: "VALID",
                value
            }
        };

        try {
            const response = await fetch(`/rest/channels/${encodeURIComponent(command.id)}`, {
                method: "PUT",
                headers: {
                    "Content-Type": "application/json",
                    Accept: "application/json"
                },
                body: JSON.stringify(payload)
            });
            const json = await parseJsonSafe(response);

            if (!response.ok) {
                const message = responseMessage(json);
                throw new Error(
                    `PUT /rest/channels/${command.id} failed with HTTP ${response.status}${message ? `: ${message}` : ""}`
                );
            }

            setCommandState(command.id, {
                writeMessage: command.feedbackId
                    ? "Command write sent. Waiting for activation confirmation and feedback."
                    : "Command write sent. Waiting for negative activation confirmation."
            });
            await waitForActivationConfirmation(command, sentAt);
            if (command.feedbackId) {
                await refreshFeedback(command);
            }
        } catch (error) {
            setCommandState(command.id, {
                writeError: errorMessage(error)
            });
        } finally {
            setCommandState(command.id, { writing: false });
        }
    }

    function removeMeasurement(id: string) {
        visibleMeasurementIds = visibleMeasurementIds.filter((pointId) => pointId !== id);
    }

    function addMeasurement() {
        if (!measurementToAdd || visibleMeasurementIds.includes(measurementToAdd)) {
            return;
        }

        visibleMeasurementIds = orderedIds(
            [...visibleMeasurementIds, measurementToAdd],
            MEASUREMENT_POINTS
        );
        measurementToAdd = "";
    }

    function removeCommand(id: string) {
        visibleCommandIds = visibleCommandIds.filter((pointId) => pointId !== id);
    }

    function addCommand() {
        if (!commandToAdd || visibleCommandIds.includes(commandToAdd)) {
            return;
        }

        visibleCommandIds = orderedIds([...visibleCommandIds, commandToAdd], COMMAND_POINTS);
        commandToAdd = "";
    }

    function errorMessage(error: unknown) {
        return error instanceof Error ? error.message : "Unexpected REST error";
    }

    function formatValue(value: unknown) {
        if (Array.isArray(value)) {
            return `[${value.map((entry) => String(entry)).join(", ")}]`;
        }

        if (value === null || value === undefined) {
            return "N/A";
        }

        if (typeof value === "boolean") {
            return value ? "true" : "false";
        }

        if (typeof value === "number" || typeof value === "string") {
            return String(value);
        }

        try {
            return JSON.stringify(value) ?? "N/A";
        } catch {
            return "N/A";
        }
    }

    function formatTimestamp(timestamp: number | string | undefined) {
        if (timestamp === undefined) {
            return "N/A";
        }

        const date = new Date(timestamp);
        return Number.isNaN(date.getTime()) ? String(timestamp) : date.toLocaleString();
    }

    function recordTimestampMs(timestamp: number | string | undefined) {
        if (timestamp === undefined) {
            return null;
        }
        const date = new Date(timestamp);
        return Number.isNaN(date.getTime()) ? null : date.getTime();
    }

    function parseConfirmation(value: unknown): Record<string, unknown> | null {
        if (typeof value !== "string") {
            return null;
        }
        try {
            const parsed = JSON.parse(value);
            return isRecordObject(parsed) ? parsed : null;
        } catch {
            return null;
        }
    }

    function formatConfirmationValue(record: ChannelRecord | null | undefined) {
        const parsed = parseConfirmation(record?.value);
        if (!parsed) {
            return formatValue(record?.value);
        }
        const cot = typeof parsed.cot === "string" ? parsed.cot : "UNKNOWN";
        const negative = typeof parsed.negative === "boolean" ? String(parsed.negative) : "UNKNOWN";
        const type = typeof parsed.type === "string" ? parsed.type : String(parsed.typeId ?? "UNKNOWN");
        const commonAddress = String(parsed.commonAddress ?? "UNKNOWN");
        const ioa = String(parsed.ioa ?? "UNKNOWN");
        return `${cot}, negative=${negative}, ${type}, ca=${commonAddress}, ioa=${ioa}`;
    }

    function formatLoadedAt(loadedAt: number | null) {
        return loadedAt === null ? "Not refreshed" : new Date(loadedAt).toLocaleTimeString();
    }
</script>

<div class="iec104-page">
    <div class="page-header">
        <div class="header-content">
            <h1 class="page-title">
                <i class="bi bi-diagram-3 title-icon"></i>
                IEC104 Test
            </h1>
            <p class="page-subtitle">
                Read IEC104 test measurements, send command channels, and inspect the explicitly paired feedback measurement channels.
            </p>
        </div>
    </div>

    <div class="info-card">
        <i class="bi bi-info-circle"></i>
        <span>
            Command and feedback rows are linked by this page's test map only. IEC104 does not automatically connect the command IOA to the feedback IOA.
        </span>
    </div>

    <section class="panel-card" aria-labelledby="server-address-title">
        <div class="panel-header">
            <div>
                <h2 id="server-address-title">IEC104 Server Address</h2>
                <p>Edit the OpenMUC master connection target for device <code>{IEC104_DEVICE_ID}</code>.</p>
            </div>
            <button
                class="btn btn-outline-primary"
                type="button"
                onclick={loadDeviceConfig}
                disabled={loadingDeviceConfig || savingDeviceAddress}
            >
                <i class="bi bi-arrow-clockwise"></i>
                {loadingDeviceConfig ? "Loading..." : "Reload Config"}
            </button>
        </div>
        <div class="device-address-form">
            <label class="address-field">
                Device address
                <input
                    class="form-control"
                    type="text"
                    bind:value={deviceAddressInput}
                    placeholder="h=192.168.4.58;p=2404;ca=1"
                    disabled={loadingDeviceConfig || savingDeviceAddress}
                    use:kbd
                />
            </label>
            <div class="device-actions">
                <button
                    class="btn btn-primary"
                    type="button"
                    onclick={saveDeviceAddress}
                    disabled={loadingDeviceConfig || savingDeviceAddress || !deviceAddressInput.trim()}
                >
                    <i class="bi bi-save"></i>
                    {savingDeviceAddress ? "Saving..." : "Save Address"}
                </button>
                <div class="point-help">
                    Format: <code>h=&lt;host&gt;;p=&lt;port&gt;;ca=&lt;common-address&gt;</code>. Current saved value:
                    <code>{deviceConfig?.deviceAddress ?? "not loaded"}</code>
                </div>
            </div>
            {#if deviceConfigError}
                <div class="status-text error">{deviceConfigError}</div>
            {:else if deviceAddressMessage}
                <div class="status-text success">{deviceAddressMessage}</div>
            {/if}
        </div>
    </section>

    <section class="panel-card" aria-labelledby="measurement-title">
        <div class="panel-header">
            <div>
                <h2 id="measurement-title">Measurement Points</h2>
                <p>Refresh reads every visible predefined measurement point with GET /rest/channels/&lbrace;id&rbrace;.</p>
            </div>
            <button
                class="btn btn-primary"
                type="button"
                onclick={refreshVisibleMeasurements}
                disabled={refreshingMeasurements || visibleMeasurements.length === 0}
            >
                <i class="bi bi-arrow-clockwise"></i>
                {refreshingMeasurements ? "Refreshing..." : "Refresh Visible"}
            </button>
        </div>

        <div class="list-controls">
            <select class="form-select" bind:value={measurementToAdd} aria-label="Measurement point to add">
                <option value="">Add a hidden measurement point</option>
                {#each hiddenMeasurements as point (point.id)}
                    <option value={point.id}>{point.label} ({point.id})</option>
                {/each}
            </select>
            <button
                class="btn btn-outline-primary"
                type="button"
                onclick={addMeasurement}
                disabled={!measurementToAdd}
            >
                <i class="bi bi-plus-lg"></i>
                Add
            </button>
        </div>

        <div class="table-container">
            <table class="mat-table">
                <thead>
                    <tr>
                        <th>Point</th>
                        <th>Channel ID</th>
                        <th>Value</th>
                        <th>Flag</th>
                        <th>Timestamp</th>
                        <th>Status</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    {#if visibleMeasurements.length === 0}
                        <tr class="no-data-row">
                            <td colspan="7">All measurement points are hidden. Add one from the predefined list above.</td>
                        </tr>
                    {:else}
                        {#each visibleMeasurements as point (point.id)}
                            {@const state = measurementStates[point.id]}
                            <tr>
                                <td>
                                    <div class="point-title">{point.label}</div>
                                    <div class="point-meta">{point.typeLabel} | {point.valueType}</div>
                                    <div class="point-help">{point.description}</div>
                                </td>
                                <td><code>{point.id}</code></td>
                                <td class="value-cell">{formatValue(state?.record?.value)}</td>
                                <td><span class="flag-pill">{state?.record?.flag ?? "N/A"}</span></td>
                                <td>{formatTimestamp(state?.record?.timestamp)}</td>
                                <td>
                                    {#if state?.loading}
                                        <span class="status-text info">Loading...</span>
                                    {:else if state?.error}
                                        <span class="status-text error">{state.error}</span>
                                    {:else}
                                        <span class="status-text muted">{formatLoadedAt(state?.loadedAt ?? null)}</span>
                                    {/if}
                                </td>
                                <td class="actions-cell">
                                    <button class="btn btn-sm btn-outline-primary" type="button" onclick={() => refreshMeasurement(point)}>
                                        Refresh
                                    </button>
                                    <button class="btn btn-sm btn-outline-danger" type="button" onclick={() => removeMeasurement(point.id)}>
                                        Remove
                                    </button>
                                </td>
                            </tr>
                        {/each}
                    {/if}
                </tbody>
            </table>
        </div>
    </section>

    <section class="panel-card" aria-labelledby="command-title">
        <div class="panel-header">
            <div>
                <h2 id="command-title">Command Points And Paired Feedback</h2>
                <p>Command writes use PUT /rest/channels/&lbrace;commandId&rbrace; with flag VALID, then refresh the mapped feedback channel.</p>
            </div>
            <button
                class="btn btn-outline-primary"
                type="button"
                onclick={refreshVisibleFeedback}
                disabled={refreshingFeedback || visibleCommands.length === 0}
            >
                <i class="bi bi-arrow-repeat"></i>
                {refreshingFeedback ? "Refreshing..." : "Refresh Feedback"}
            </button>
        </div>

        <div class="list-controls">
            <select class="form-select" bind:value={commandToAdd} aria-label="Command point to add">
                <option value="">Add a hidden command point</option>
                {#each hiddenCommands as command (command.id)}
                    <option value={command.id}>{command.label} ({command.id})</option>
                {/each}
            </select>
            <button
                class="btn btn-outline-primary"
                type="button"
                onclick={addCommand}
                disabled={!commandToAdd}
            >
                <i class="bi bi-plus-lg"></i>
                Add
            </button>
        </div>

        <div class="table-container command-table-wrap">
            <table class="mat-table command-table">
                <thead>
                    <tr>
                        <th>Command Point</th>
                        <th>Explicit Test Map</th>
                        <th>Value To Write</th>
                        <th>Activation Confirmation</th>
                        <th>Paired Feedback Measurement</th>
                        <th>Status</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    {#if visibleCommands.length === 0}
                        <tr class="no-data-row">
                            <td colspan="7">All command points are hidden. Add one from the predefined list above.</td>
                        </tr>
                    {:else}
                        {#each visibleCommands as command (command.id)}
                            {@const state = commandStates[command.id]}
                            {@const feedback = state?.feedback}
                            {@const confirmation = state?.confirmation}
                            <tr>
                                <td>
                                    <div class="point-title">{command.label}</div>
                                    <div class="point-meta">{command.typeLabel} | {command.valueType}</div>
                                    <code>{command.id}</code>
                                </td>
                                <td>
                                    <div class="map-line">
                                        <span class="map-node command-node">Command</span>
                                        <i class="bi bi-arrow-right"></i>
                                        <span class="map-node feedback-node">Feedback</span>
                                    </div>
                                    <div class="point-help">
                                        {#if command.feedbackId}
                                            {command.id} maps to {command.feedbackId}
                                        {:else}
                                            {command.id} intentionally maps to no feedback; expect negative confirmation.
                                        {/if}
                                    </div>
                                </td>
                                <td>
                                    {#if command.inputKind === "boolean"}
                                        <select
                                            class="form-select command-input"
                                            value={String(getCommandInputBoolean(command.id))}
                                            onchange={(event) => setCommandInput(command.id, event.currentTarget.value === "true")}
                                        >
                                            <option value="true">true</option>
                                            <option value="false">false</option>
                                        </select>
                                    {:else if command.inputKind === "bytes"}
                                        <input
                                            class="form-control command-input"
                                            type="text"
                                            value={getCommandInputText(command.id)}
                                            placeholder="1,1,0"
                                            oninput={(event) => setCommandInput(command.id, event.currentTarget.value)}
                                            use:kbd
                                        />
                                        <div class="point-help">Comma-separated BYTE_ARRAY length {command.byteLength}</div>
                                    {:else}
                                        <input
                                            class="form-control command-input"
                                            type="number"
                                            value={getCommandInputText(command.id)}
                                            step={command.inputKind === "float" ? "0.01" : "1"}
                                            oninput={(event) => setCommandInput(command.id, event.currentTarget.value)}
                                            use:kbd
                                        />
                                    {/if}
                                </td>
                                <td>
                                    <div class="point-title">Activation confirmation</div>
                                    <div class="point-meta">Actual IEC104 command response</div>
                                    <code>{command.confirmationId}</code>
                                    <div class="feedback-record">
                                        <span>Value: <strong>{formatConfirmationValue(confirmation?.record)}</strong></span>
                                        <span>Flag: <strong>{confirmation?.record?.flag ?? "N/A"}</strong></span>
                                        <span>Time: <strong>{formatTimestamp(confirmation?.record?.timestamp)}</strong></span>
                                    </div>
                                    {#if confirmation?.loading}
                                        <div class="status-text info">Waiting for confirmation...</div>
                                    {:else if confirmation?.error}
                                        <div class="status-text error">{confirmation.error}</div>
                                    {/if}
                                </td>
                                <td>
                                    {#if command.feedbackId}
                                        <div class="point-title">{command.feedbackLabel}</div>
                                        <div class="point-meta">{command.feedbackTypeLabel}</div>
                                        <code>{command.feedbackId}</code>
                                        <div class="feedback-record">
                                            <span>Value: <strong>{formatValue(feedback?.record?.value)}</strong></span>
                                            <span>Flag: <strong>{feedback?.record?.flag ?? "N/A"}</strong></span>
                                            <span>Time: <strong>{formatTimestamp(feedback?.record?.timestamp)}</strong></span>
                                        </div>
                                    {:else}
                                        <div class="point-title">No paired feedback</div>
                                        <div class="point-meta">Invalid IOA rejection test</div>
                                        <div class="point-help">The slave should reject this command and leave measurements unchanged.</div>
                                    {/if}
                                </td>
                                <td>
                                    {#if state?.writing}
                                        <span class="status-text info">Writing command...</span>
                                    {:else if state?.writeError}
                                        <span class="status-text error">{state.writeError}</span>
                                    {:else if state?.confirmation?.error}
                                        <span class="status-text error">{state.confirmation.error}</span>
                                    {:else if feedback?.error}
                                        <span class="status-text error">{feedback.error}</span>
                                    {:else if state?.writeMessage}
                                        <span class="status-text success">{state.writeMessage}</span>
                                    {:else}
                                        <span class="status-text muted">Feedback {formatLoadedAt(feedback?.loadedAt ?? null)}</span>
                                    {/if}
                                </td>
                                <td class="actions-cell command-actions">
                                    <button
                                        class="btn btn-sm btn-success"
                                        type="button"
                                        onclick={() => runCommand(command)}
                                        disabled={state?.writing}
                                    >
                                        Send
                                    </button>
                                    <button class="btn btn-sm btn-outline-primary" type="button" onclick={() => refreshConfirmation(command)}>
                                        Refresh Confirmation
                                    </button>
                                    <button
                                        class="btn btn-sm btn-outline-primary"
                                        type="button"
                                        onclick={() => refreshFeedback(command)}
                                        disabled={!command.feedbackId}
                                    >
                                        Refresh Feedback
                                    </button>
                                    <button class="btn btn-sm btn-outline-danger" type="button" onclick={() => removeCommand(command.id)}>
                                        Remove
                                    </button>
                                </td>
                            </tr>
                        {/each}
                    {/if}
                </tbody>
            </table>
        </div>
    </section>
</div>

<style>
    .iec104-page {
        min-height: calc(100vh - var(--header-height) - 3rem);
        animation: fadeIn 0.3s ease-in-out;
    }

    .page-header {
        margin-bottom: 1.5rem;
        display: flex;
        justify-content: space-between;
        align-items: center;
        flex-wrap: wrap;
        gap: 1rem;
    }

    .page-title {
        margin: 0 0 0.5rem;
        display: flex;
        align-items: center;
        gap: 0.75rem;
        color: var(--primary-color);
        font-size: 1.8rem;
        font-weight: 500;
    }

    .title-icon {
        font-size: 2rem;
        color: var(--accent-color);
    }

    .page-subtitle,
    .panel-header p,
    .point-help,
    .point-meta {
        color: var(--bs-secondary-color);
    }

    .page-subtitle,
    .panel-header p {
        margin: 0;
        line-height: 1.5;
    }

    .info-card,
    .panel-card {
        background: var(--surface-color);
        border: 1px solid var(--bs-border-color);
        border-radius: var(--bs-border-radius-xl);
        box-shadow: var(--bs-box-shadow-sm);
    }

    .info-card {
        display: flex;
        gap: 0.75rem;
        align-items: flex-start;
        margin-bottom: 1rem;
        padding: 1rem;
        background: color-mix(in srgb, var(--info-color) 8%, var(--surface-color));
        border-color: color-mix(in srgb, var(--info-color) 25%, var(--bs-border-color));
        color: var(--bs-body-color);
    }

    .info-card i {
        color: var(--info-color);
        font-size: 1.25rem;
        flex-shrink: 0;
    }

    .panel-card {
        margin-bottom: 1.5rem;
        overflow: hidden;
    }

    .panel-header {
        display: flex;
        justify-content: space-between;
        align-items: flex-start;
        gap: 1rem;
        padding: 1.25rem;
        border-bottom: 1px solid var(--bs-border-color);
    }

    .panel-header h2 {
        margin: 0 0 0.5rem;
        color: var(--primary-color);
        font-size: 1.25rem;
        font-weight: 600;
    }

    .list-controls {
        display: flex;
        gap: 0.75rem;
        padding: 1rem 1.25rem;
        border-bottom: 1px solid var(--bs-border-color);
        background: var(--bs-light);
    }

    .device-address-form {
        display: grid;
        gap: 1rem;
        padding: 1.25rem;
    }

    .address-field {
        display: grid;
        gap: 0.5rem;
        max-width: 42rem;
        font-weight: 600;
    }

    .device-actions {
        display: flex;
        align-items: center;
        gap: 1rem;
        flex-wrap: wrap;
    }

    .list-controls .form-select {
        max-width: 28rem;
    }

    .table-container {
        overflow-x: auto;
    }

    .mat-table {
        width: 100%;
        border-collapse: collapse;
    }

    .mat-table thead {
        background: var(--bs-light);
    }

    .mat-table th {
        padding: 1rem;
        text-align: left;
        font-weight: 600;
        font-size: 0.875rem;
        color: var(--bs-secondary-color);
        border-bottom: 1px solid var(--bs-border-color);
        vertical-align: top;
    }

    .mat-table td {
        padding: 1rem;
        border-bottom: 1px solid var(--bs-border-color);
        color: var(--bs-body-color);
        vertical-align: top;
    }

    .mat-table tbody tr:hover {
        background: color-mix(in srgb, var(--primary-color) 4%, var(--surface-color));
    }

    .no-data-row td {
        text-align: center;
        padding: 2.5rem;
        color: var(--bs-secondary-color);
    }

    .point-title {
        font-weight: 600;
        color: var(--bs-body-color);
    }

    .point-meta,
    .point-help {
        margin-top: 0.25rem;
        font-size: 0.8125rem;
    }

    code {
        color: var(--primary-color);
        background: color-mix(in srgb, var(--primary-color) 8%, var(--surface-color));
        border-radius: var(--bs-border-radius-sm);
        padding: 0.125rem 0.375rem;
        word-break: break-word;
    }

    .value-cell {
        min-width: 8rem;
        font-weight: 600;
    }

    .flag-pill,
    .map-node {
        display: inline-flex;
        align-items: center;
        border-radius: var(--bs-border-radius-pill);
        padding: 0.25rem 0.625rem;
        font-size: 0.8125rem;
        font-weight: 600;
    }

    .flag-pill {
        color: var(--success-color);
        background: color-mix(in srgb, var(--success-color) 12%, var(--surface-color));
    }

    .status-text {
        display: inline-block;
        max-width: 18rem;
        font-size: 0.875rem;
        line-height: 1.4;
    }

    .status-text.info {
        color: var(--info-color);
    }

    .status-text.error {
        color: var(--warn-color);
    }

    .status-text.success {
        color: var(--success-color);
    }

    .status-text.muted {
        color: var(--bs-secondary-color);
    }

    .actions-cell {
        display: flex;
        gap: 0.5rem;
        flex-wrap: wrap;
    }

    .command-table {
        min-width: 72rem;
    }

    .command-input {
        min-width: 11rem;
    }

    .map-line {
        display: flex;
        align-items: center;
        gap: 0.5rem;
        margin-bottom: 0.5rem;
    }

    .command-node {
        color: var(--primary-color);
        background: color-mix(in srgb, var(--primary-color) 10%, var(--surface-color));
    }

    .feedback-node {
        color: var(--accent-color);
        background: color-mix(in srgb, var(--accent-color) 10%, var(--surface-color));
    }

    .feedback-record {
        display: grid;
        gap: 0.25rem;
        margin-top: 0.75rem;
        color: var(--bs-body-color);
        font-size: 0.875rem;
    }

    .command-actions {
        min-width: 10rem;
    }

    @media (max-width: 768px) {
        .panel-header,
        .list-controls {
            flex-direction: column;
            align-items: stretch;
        }

        .list-controls .form-select {
            max-width: none;
        }

        .page-title {
            font-size: 1.5rem;
        }
    }
</style>
