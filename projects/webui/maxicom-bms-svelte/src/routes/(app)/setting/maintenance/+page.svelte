<script lang="ts">
    import { showToast } from "$lib/services/toast.store";
    import { kbd } from "$lib/actions/kbd";
    import { onMount } from "svelte";

    interface WifiNetwork {
        ssid: string;
        bssid: string;
        signal: number;
        security: string;
        inUse: boolean;
        known: boolean;
    }

    interface WifiStatus {
        connected: boolean;
        ip?: string;
        interface?: string;
    }

    interface IrTaskStatus {
        type?: string;
        state: string;
        serialPort?: string;
        startId?: number;
        endId?: number;
        currentId?: number;
        nextId?: number;
        running: boolean;
        exitCode?: number;
        startedAt?: string;
        finishedAt?: string;
        error?: string;
        activeIds: number[];
        missingIds: number[];
        activatedIds: number[];
        notActivatedIds: number[];
        output: string[];
    }

    interface TcTaskStatus {
        type?: string;
        state: string;
        serialPort?: string;
        slaveId: number;
        running: boolean;
        exitCode?: number;
        startedAt?: string;
        finishedAt?: string;
        error?: string;
        transformerRange?: number | null;
        calibrationSigned?: number | null;
        calibrationRaw?: number | null;
        firstCurrentSignedRaw?: number | null;
        latestCurrentRaw?: number | null;
        latestCurrentSignedRaw?: number | null;
        latestCurrentAmps?: number | null;
        currentSampleIndex: number;
        successfulCurrentSamples: number;
        currentChanged: boolean;
        output: string[];
    }

    interface GpioOutputState {
        stringIndex: number;
        ioIndex: number;
        gpioNumber: number;
        alarmValue: number;
        normalValue: number;
        direction: "input" | "output";
        alarm: boolean;
        alarmRegisterValue?: number | null;
        alarmChannelId?: string | null;
        alarmReadError?: string | null;
        alarmReadAt?: number | null;
        desiredValue?: number | null;
        lastWrittenValue?: number | null;
        lastDirection?: string | null;
        lastError?: string | null;
        updatedAt: number;
    }

    interface GpioAlarmStatus {
        scriptPath: string;
        strings: Record<string, GpioOutputState>;
    }

    let activeMaintenanceTab = $state("system");
    let isLoading = $state(false);
    let wifiNetworks = $state<WifiNetwork[]>([]);
    let isScanning = $state(false);
    let showPasswordDialog = $state(false);
    let selectedNetwork = $state<WifiNetwork | null>(null);
    let wifiPassword = $state("");
    let wifiStatus = $state<WifiStatus | null>(null);
    let fileInput: HTMLInputElement;
    let irSerialPort = $state("/dev/ttyS1");
    let irStartId = $state(1);
    let irEndId = $state(108);
    let irStatus = $state<IrTaskStatus | null>(null);
    let irRequestRunning = $state(false);
    let irPollTimer: ReturnType<typeof setInterval> | undefined;
    let tcSerialPort = $state("/dev/ttyS1");
    let tcCalibrationValue = $state(0);
    let tcStatus = $state<TcTaskStatus | null>(null);
    let tcRequestRunning = $state(false);
    let tcConfiguredStringCount = $state<number | null>(null);
    let tcStringCheckRunning = $state(false);
    let tcPollTimer: ReturnType<typeof setInterval> | undefined;
    let gpioAlarmStatus = $state<GpioAlarmStatus | null>(null);
    let gpioString1AlarmValue = $state(1);
    let gpioString2AlarmValue = $state(1);
    let gpioString1Direction = $state<"input" | "output">("output");
    let gpioString2Direction = $state<"input" | "output">("output");
    let gpioRequestRunning = $state(false);
    let gpioSettingsDirty = $state(false);
    let gpioPollTimer: ReturnType<typeof setInterval> | undefined;

    onMount(() => {
        getWifiStatus();
        getIrStatus();
        getTcStatus();
        getGpioAlarmStatus();
        refreshTcConfiguredStringCount();
        irPollTimer = setInterval(getIrStatus, 2000);
        tcPollTimer = setInterval(getTcStatus, 1000);
        gpioPollTimer = setInterval(getGpioAlarmStatus, 2000);

        return () => {
            if (irPollTimer) {
                clearInterval(irPollTimer);
            }
            if (tcPollTimer) {
                clearInterval(tcPollTimer);
            }
            if (gpioPollTimer) {
                clearInterval(gpioPollTimer);
            }
        };
    });

    async function getWifiStatus() {
        try {
            const response = await fetch("/rest/wifi/status");
            if (response.ok) {
                wifiStatus = await response.json();
            }
        } catch (error) {
            console.error("Error fetching WiFi status:", error);
        }
    }

    function handleImportConfig() {
        fileInput.click();
    }

    async function handleFileSelect(event: Event) {
        const input = event.target as HTMLInputElement;
        if (!input.files || input.files.length === 0) return;

        const file = input.files[0];
        const formData = new FormData();
        formData.append("file", file);

        try {
            isLoading = true;
            showToast("Importing configuration...", "info");

            const response = await fetch("/rest/csv_export", {
                method: "POST",
                body: formData,
            });

            if (response.ok) {
                showToast("Configuration imported successfully", "success");
                input.value = ""; // Reset
            } else {
                const errorText = await response.text();
                // Try to parse JSON error if possible
                try {
                    const err = JSON.parse(errorText);
                    showToast(
                        `Import failed: ${err.message || errorText}`,
                        "error",
                    );
                } catch (e) {
                    showToast(`Import failed: ${errorText}`, "error");
                }
            }
        } catch (error: any) {
            console.error("Import error:", error);
            showToast(`Import failed: ${error.message}`, "error");
        } finally {
            isLoading = false;
        }
    }

    function handleExportConfig() {
        try {
            const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
            const fileName = `config_backup_${timestamp}.csv`;

            // Trigger download via hidden link
            const link = document.createElement("a");
            link.href = `/rest/csv_export?fileName=${fileName}`;
            link.download = fileName;
            document.body.appendChild(link);
            link.click();
            document.body.removeChild(link);

            showToast("Export initiated", "success");
        } catch (error: any) {
            console.error("Export error:", error);
            showToast("Failed to initiate export", "error");
        }
    }

    async function handleResetConfig() {
        if (
            !confirm(
                "WARNING: This will delete all battery string configurations and data. This action cannot be undone. Are you sure you want to proceed?",
            )
        ) {
            return;
        }

        try {
            isLoading = true;
            showToast("Resetting configuration...", "info");

            const response = await fetch("/rest/reset", {
                method: "POST",
            });

            const result = await response.json();

            if (response.ok && result.success) {
                showToast(
                    "Reset successful. Please reboot the device.",
                    "success",
                );
                if (
                    confirm(
                        "Reset successful. The device needs to be rebooted to apply changes. Reboot now?",
                    )
                ) {
                    handleReboot();
                }
            } else {
                showToast(
                    `Reset failed: ${result.error || "Unknown error"}`,
                    "error",
                );
            }
        } catch (error: any) {
            console.error("Reset error:", error);
            showToast(`Reset failed: ${error.message}`, "error");
        } finally {
            isLoading = false;
        }
    }

    async function handleReboot() {
        if (!confirm("Are you sure you want to reboot the device?")) {
            return;
        }

        try {
            showToast("Sending reboot command...", "info");
            const response = await fetch("/rest/reboot", {
                method: "POST",
            });

            if (response.ok) {
                showToast(
                    "Reboot command sent. Device is restarting...",
                    "success",
                );
            } else {
                showToast("Failed to send reboot command", "error");
            }
        } catch (error: any) {
            console.error("Reboot error:", error);
            showToast(`Reboot failed: ${error.message}`, "error");
        }
    }

    async function scanWifiNetworks() {
        isScanning = true;
        wifiNetworks = [];

        try {
            const response = await fetch("/rest/wifi/scan");
            if (response.ok) {
                wifiNetworks = await response.json();
            } else {
                console.error("Failed to scan WiFi networks");
                showToast("Failed to scan WiFi networks", "error");
            }
        } catch (error) {
            console.error("Error scanning WiFi:", error);
            showToast("Error scanning WiFi networks", "error");
        } finally {
            isScanning = false;
        }
    }

    function connectToNetwork(network: WifiNetwork) {
        selectedNetwork = network;

        // If network is known (saved) or open (no security), try connecting directly
        if (
            network.known ||
            !network.security ||
            network.security.trim() === ""
        ) {
            performConnection(network.ssid, network.bssid);
        } else {
            // Show password dialog for new secured networks
            showPasswordDialog = true;
            wifiPassword = "";
        }
    }

    async function performConnection(
        ssid: string,
        bssid: string,
        password?: string,
    ) {
        try {
            const body: any = { ssid, bssid };
            if (password) {
                body.password = password;
            }

            const response = await fetch("/rest/wifi/connect", {
                method: "POST",
                headers: {
                    "Content-Type": "application/json",
                },
                body: JSON.stringify(body),
            });

            const result = await response.json();

            if (result.success) {
                showToast(`Successfully connected to ${ssid}`, "success");
                // Refresh network list and status
                await scanWifiNetworks();
                await getWifiStatus();
            } else {
                showToast(
                    `Failed to connect: ${result.error || "Unknown error"}`,
                    "error",
                );
            }
        } catch (error) {
            console.error("Error connecting to WiFi:", error);
            showToast("Failed to connect to WiFi network", "error");
        }
    }

    function handlePasswordSubmit() {
        if (selectedNetwork && wifiPassword) {
            showPasswordDialog = false;
            performConnection(
                selectedNetwork.ssid,
                selectedNetwork.bssid,
                wifiPassword,
            );
        }
    }

    function cancelPasswordDialog() {
        showPasswordDialog = false;
        selectedNetwork = null;
        wifiPassword = "";
    }

    function getSignalIcon(signal: number): string {
        if (signal >= 75) return "bi-wifi";
        if (signal >= 50) return "bi-wifi";
        if (signal >= 25) return "bi-wifi";
        return "bi-wifi";
    }

    function getSignalColor(signal: number): string {
        if (signal >= 75) return "signal-excellent";
        if (signal >= 50) return "signal-good";
        return "signal-weak";
    }

    async function getIrStatus() {
        try {
            const response = await fetch("/rest/ir-test");
            if (response.ok) {
                const result = await response.json();
                irStatus = result.data ?? result;
            }
        } catch (error) {
            console.error("Error fetching IR status:", error);
        }
    }

    function wait(milliseconds: number) {
        return new Promise((resolve) => setTimeout(resolve, milliseconds));
    }

    async function waitForIrTaskStopped() {
        for (let index = 0; index < 20; index++) {
            await wait(500);
            await getIrStatus();
            if (!irStatus?.running) {
                return true;
            }
        }
        return false;
    }

    async function startIrTask(action: "activate" | "scan") {
        if (irStatus?.running) {
            const runningLabel = irStatus.type || "task";
            const current = irStatus.currentId ? ` at module ${irStatus.currentId}` : "";
            const shouldStop = confirm(
                `Task ${runningLabel}${current} is running. Stop it and start ${action}?`,
            );
            if (!shouldStop) return;
            await stopIrTask(false);
            irRequestRunning = true;
            const stopped = await waitForIrTaskStopped();
            if (!stopped) {
                showToast("Unable to stop the running IR task yet", "error");
                irRequestRunning = false;
                return;
            }
        }

        irRequestRunning = true;
        try {
            const response = await fetch(`/rest/ir-test/${action}`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    serialPort: irSerialPort,
                    startId: Number(irStartId),
                    endId: Number(irEndId),
                }),
            });
            const result = await response.json();
            irStatus = result.data ?? irStatus;
            if (response.ok && result.success) {
                showToast(result.description || "IR task started", "success");
            } else {
                showToast(result.description || "IR task failed", "error");
            }
        } catch (error: any) {
            showToast(`IR task failed: ${error.message}`, "error");
        } finally {
            irRequestRunning = false;
            getIrStatus();
        }
    }

    async function stopIrTask(showMessage = true) {
        irRequestRunning = true;
        try {
            const response = await fetch("/rest/ir-test/stop", {
                method: "POST",
            });
            const result = await response.json();
            irStatus = result.data ?? irStatus;
            if (showMessage) {
                showToast(result.description || "Stop requested", response.ok ? "success" : "error");
            }
        } catch (error: any) {
            if (showMessage) {
                showToast(`Stop failed: ${error.message}`, "error");
            }
        } finally {
            irRequestRunning = false;
            getIrStatus();
        }
    }

    function idList(ids?: number[]): string {
        return ids && ids.length > 0 ? ids.join(", ") : "-";
    }

    async function getTcStatus() {
        try {
            const response = await fetch("/rest/tc-calibration");
            if (response.ok) {
                const result = await response.json();
                tcStatus = result.data ?? result;
            }
        } catch (error) {
            console.error("Error fetching TC status:", error);
        }
    }

    async function getConfiguredStringCount(): Promise<number> {
        const response = await fetch("/rest/devices");
        if (!response.ok) {
            throw new Error("Cannot check configured strings");
        }
        const json = await response.json();
        const devices = Array.isArray(json?.devices) ? json.devices : [];
        const stringIds: Record<string, boolean> = {};

        devices.forEach((deviceId: string) => {
            const match = String(deviceId).match(/^str(\d+)_(modbus|virtual)$/);
            if (match) {
                stringIds[match[1]] = true;
            }
        });

        return Object.keys(stringIds).length;
    }

    async function refreshTcConfiguredStringCount() {
        tcStringCheckRunning = true;
        try {
            tcConfiguredStringCount = await getConfiguredStringCount();
        } catch (error: any) {
            tcConfiguredStringCount = null;
            showToast(error.message || "Cannot verify string configuration", "error");
        } finally {
            tcStringCheckRunning = false;
        }
    }

    async function ensureTcActionAllowed() {
        try {
            const count = await getConfiguredStringCount();
            tcConfiguredStringCount = count;
            if (count > 0) {
                showToast(
                    `Remove all configured strings before using Calibrate TC. Found ${count} string(s).`,
                    "error",
                );
                return false;
            }
            return true;
        } catch (error: any) {
            showToast(error.message || "Cannot verify string configuration", "error");
            return false;
        }
    }

    async function startTcTask(action: "read-range" | "read-current" | "calibrate") {
        if (tcStatus?.running) {
            showToast("A TC task is already running", "error");
            return;
        }

        if (!(await ensureTcActionAllowed())) {
            return;
        }

        tcRequestRunning = true;
        try {
            const body: { serialPort: string; calibration?: number } = {
                serialPort: tcSerialPort,
            };
            if (action === "calibrate") {
                body.calibration = Number(tcCalibrationValue);
            }

            const response = await fetch(`/rest/tc-calibration/${action}`, {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            const result = await response.json();
            tcStatus = result.data ?? tcStatus;
            if (response.ok && result.success) {
                showToast(result.description || "TC task started", "success");
            } else {
                showToast(result.description || "TC task failed", "error");
            }
        } catch (error: any) {
            showToast(`TC task failed: ${error.message}`, "error");
        } finally {
            tcRequestRunning = false;
            getTcStatus();
        }
    }

    async function stopTcTask() {
        tcRequestRunning = true;
        try {
            const response = await fetch("/rest/tc-calibration/stop", {
                method: "POST",
            });
            const result = await response.json();
            tcStatus = result.data ?? tcStatus;
            showToast(result.description || "Stop requested", response.ok ? "success" : "error");
        } catch (error: any) {
            showToast(`Stop failed: ${error.message}`, "error");
        } finally {
            tcRequestRunning = false;
            getTcStatus();
        }
    }

    function hex16(value?: number | null): string {
        if (value === null || value === undefined) return "-";
        let hex = (Number(value) & 0xffff).toString(16).toUpperCase();
        while (hex.length < 4) {
            hex = "0" + hex;
        }
        return "0x" + hex;
    }

    function valueOrDash(value?: number | null): string {
        return value === null || value === undefined ? "-" : String(value);
    }

    function currentValueOrDash(value?: number | null): string {
        if (value === null || value === undefined) return "-";
        return `${Number(value).toFixed(1)} A`;
    }

    async function getGpioAlarmStatus() {
        try {
            const response = await fetch("/rest/gpio-alarm");
            if (response.ok) {
                const result = await response.json();
                gpioAlarmStatus = result.data ?? result;
                const string1 = gpioAlarmStatus?.strings?.["1"];
                const string2 = gpioAlarmStatus?.strings?.["2"];
                if (!gpioSettingsDirty) {
                    if (string1) gpioString1AlarmValue = string1.alarmValue;
                    if (string2) gpioString2AlarmValue = string2.alarmValue;
                    if (string1?.direction) gpioString1Direction = string1.direction;
                    if (string2?.direction) gpioString2Direction = string2.direction;
                }
            }
        } catch (error) {
            console.error("Error fetching GPIO alarm status:", error);
        }
    }

    async function saveGpioAlarmSettings() {
        gpioRequestRunning = true;
        try {
            const response = await fetch("/rest/gpio-alarm", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    string1AlarmValue: Number(gpioString1AlarmValue),
                    string2AlarmValue: Number(gpioString2AlarmValue),
                    string1Direction: gpioString1Direction,
                    string2Direction: gpioString2Direction,
                }),
            });
            const result = await response.json();
            gpioAlarmStatus = result.data ?? gpioAlarmStatus;
            if (response.ok) {
                gpioSettingsDirty = false;
            }
            showToast(result.description || "GPIO alarm settings saved", response.ok ? "success" : "error");
        } catch (error: any) {
            showToast(`GPIO alarm save failed: ${error.message}`, "error");
        } finally {
            gpioRequestRunning = false;
            getGpioAlarmStatus();
        }
    }

    function gpioStateFor(stringIndex: number): GpioOutputState | null {
        return gpioAlarmStatus?.strings?.[String(stringIndex)] ?? null;
    }

    function gpioDisplayValue(value?: number | null): string {
        return value === null || value === undefined ? "-" : String(value);
    }

    function gpioAlarmText(state: GpioOutputState | null): string {
        if (!state) return "Unknown";
        return state.alarm ? "Alarm" : "Normal";
    }

    function tcActionDisabled() {
        return tcRequestRunning || Boolean(tcStatus?.running) || tcStringCheckRunning || tcConfiguredStringCount !== 0;
    }
</script>

<input
    type="file"
    accept=".csv"
    style="display: none"
    bind:this={fileInput}
    onchange={handleFileSelect}
    use:kbd
/>

<div class="maintenance-page">
    <div class="title-row">
        <i class="bi bi-tools title-icon"></i>
        <h1>Maintenance</h1>
    </div>
    <p class="subtitle">
        System maintenance actions.
    </p>

    <div class="maintenance-tabs">
        <button
            class:active={activeMaintenanceTab === "system"}
            onclick={() => (activeMaintenanceTab = "system")}
        >
            <i class="bi bi-sliders"></i>
            System
        </button>
        <button
            class:active={activeMaintenanceTab === "wifi"}
            onclick={() => (activeMaintenanceTab = "wifi")}
        >
            <i class="bi bi-wifi"></i>
            WiFi
        </button>
        <button
            class:active={activeMaintenanceTab === "ir"}
            onclick={() => (activeMaintenanceTab = "ir")}
        >
            <i class="bi bi-lightning-charge"></i>
            IR Test
        </button>
        <button
            class:active={activeMaintenanceTab === "tc"}
            onclick={() => {
                activeMaintenanceTab = "tc";
                refreshTcConfiguredStringCount();
            }}
        >
            <i class="bi bi-sliders2-vertical"></i>
            Calibrate TC
        </button>
        <button
            class:active={activeMaintenanceTab === "gpio"}
            onclick={() => {
                activeMaintenanceTab = "gpio";
                getGpioAlarmStatus();
            }}
        >
            <i class="bi bi-hdd-network"></i>
            GPIO Alarm
        </button>
    </div>

    {#if activeMaintenanceTab === "system"}
    <div class="cards">
        <div class="action-card">
            <div class="card-header">
                <i class="bi bi-arrow-repeat"></i>
                <div class="title">Import / Export Configuration</div>
            </div>
            <div class="card-content">
                <p>
                    Import an existing configuration file or export the current
                    configuration for backup.
                </p>
            </div>
            <div class="card-actions">
                <button class="btn-primary" onclick={handleImportConfig}>
                    <i class="bi bi-upload"></i>
                    Import Config
                </button>
                <button class="btn-secondary" onclick={handleExportConfig}>
                    <i class="bi bi-download"></i>
                    Export Config
                </button>
            </div>
        </div>

        <div class="action-card">
            <div class="card-header">
                <i class="bi bi-arrow-counterclockwise"></i>
                <div class="title">Reset Configuration</div>
            </div>
            <div class="card-content">
                <p>
                    Reset all settings to factory defaults. This will not
                    perform any action yet.
                </p>
            </div>
            <div class="card-actions">
                <button class="btn-warn" onclick={handleResetConfig}>
                    <i class="bi bi-arrow-clockwise"></i>
                    Reset to Default
                </button>
            </div>
        </div>

        <div class="action-card">
            <div class="card-header">
                <i class="bi bi-arrow-repeat"></i>
                <div class="title">Reboot Device</div>
            </div>
            <div class="card-content">
                <p>Reboot the device.</p>
            </div>
            <div class="card-actions">
                <button class="btn-accent" onclick={handleReboot}>
                    <i class="bi bi-power"></i>
                    Reboot
                </button>
            </div>
        </div>
    </div>
    {/if}

    <!-- WiFi Configuration Section -->
    {#if activeMaintenanceTab === "wifi"}
    <div class="wifi-section">
        <div class="action-card wifi-card">
            <div class="card-header">
                <i class="bi bi-wifi"></i>
                <div class="title">WiFi Configuration</div>
            </div>
            <div class="card-content">
                <p>Scan for available WiFi networks and connect to them.</p>

                <div class="scan-button-container">
                    <button
                        class="btn-primary"
                        onclick={scanWifiNetworks}
                        disabled={isScanning}
                    >
                        <i class="bi bi-search"></i>
                        {isScanning ? "Scanning..." : "Scan WiFi Networks"}
                    </button>
                    {#if wifiStatus?.connected}
                        <div class="current-ip">
                            <i class="bi bi-info-circle"></i>
                            Connected to WiFi (IP: {wifiStatus.ip})
                        </div>
                    {/if}
                </div>

                {#if isScanning}
                    <div class="loading-container">
                        <div class="spinner"></div>
                        <p>Scanning for WiFi networks...</p>
                    </div>
                {/if}

                {#if !isScanning && wifiNetworks.length > 0}
                    <div class="wifi-table-container">
                        <table class="wifi-table">
                            <thead>
                                <tr>
                                    <th>Network Name</th>
                                    <th>Signal</th>
                                    <th>Security</th>
                                    <th>Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                {#each wifiNetworks as network}
                                    <tr>
                                        <td>
                                            <div class="ssid-cell">
                                                {#if network.inUse}
                                                    <i
                                                        class="bi bi-check-circle connected-icon"
                                                    ></i>
                                                {/if}
                                                <span
                                                    class:connected-text={network.inUse}
                                                    >{network.ssid}</span
                                                >
                                            </div>
                                        </td>
                                        <td>
                                            <div class="signal-cell">
                                                <i
                                                    class="bi {getSignalIcon(
                                                        network.signal,
                                                    )} {getSignalColor(
                                                        network.signal,
                                                    )}"
                                                ></i>
                                                <span>{network.signal}%</span>
                                            </div>
                                        </td>
                                        <td>
                                            {#if network.security}
                                                <i
                                                    class="bi bi-lock security-icon"
                                                ></i>
                                            {/if}
                                            <span
                                                >{network.security ||
                                                    "Open"}</span
                                            >
                                        </td>
                                        <td>
                                            <button
                                                class="btn-secondary btn-sm"
                                                onclick={() =>
                                                    connectToNetwork(network)}
                                                disabled={network.inUse}
                                            >
                                                {network.inUse
                                                    ? "Connected"
                                                    : "Connect"}
                                            </button>
                                        </td>
                                    </tr>
                                {/each}
                            </tbody>
                        </table>
                    </div>
                {/if}

                {#if !isScanning && wifiNetworks.length === 0}
                    <div class="no-networks">
                        <i class="bi bi-wifi-off"></i>
                        <p>
                            No WiFi networks found. Click "Scan WiFi Networks"
                            to search.
                        </p>
                    </div>
                {/if}
            </div>
        </div>
    </div>
    {/if}

    {#if activeMaintenanceTab === "ir"}
        <div class="ir-test-section">
            <div class="action-card">
                <div class="card-header">
                    <i class="bi bi-lightning-charge"></i>
                    <div class="title">IR Test Tasks</div>
                </div>
                <div class="card-content">
                    <div class="ir-form-grid">
                        <label>
                            Serial Port
                            <input bind:value={irSerialPort} use:kbd />
                        </label>
                        <label>
                            Start ID
                            <input type="number" min="1" max="247" bind:value={irStartId} use:kbd />
                        </label>
                        <label>
                            End ID
                            <input type="number" min="1" max="247" bind:value={irEndId} use:kbd />
                        </label>
                    </div>

                    <div class="ir-status-panel">
                        <div class="ir-status-line">
                            <span>Status</span>
                            <strong>{irStatus?.state ?? "idle"}</strong>
                        </div>
                        {#if irStatus?.type}
                            <div class="ir-status-line">
                                <span>Task</span>
                                <strong>{irStatus.type}</strong>
                            </div>
                        {/if}
                        {#if irStatus?.running && irStatus.type === "activate"}
                            <div class="ir-status-line highlight">
                                <span>Activating</span>
                                <strong>Module {irStatus.currentId} / {irStatus.endId}</strong>
                            </div>
                        {:else if irStatus?.running}
                            <div class="ir-status-line highlight">
                                <span>Scanning</span>
                                <strong>Module {irStatus.currentId} / {irStatus.endId}</strong>
                            </div>
                        {/if}
                        {#if irStatus?.error}
                            <div class="ir-error">{irStatus.error}</div>
                        {/if}
                    </div>

                    <div class="ir-activated-panel">
                        <span>Activated IR ({irStatus?.activatedIds?.length ?? 0})</span>
                        <strong>{idList(irStatus?.activatedIds)}</strong>
                    </div>

                    <div class="ir-result-grid">
                        <div>
                            <span>Not Activated IR ({irStatus?.notActivatedIds?.length ?? 0})</span>
                            <strong>{idList(irStatus?.notActivatedIds)}</strong>
                        </div>
                        <div>
                            <span>Active TA ({irStatus?.activeIds?.length ?? 0})</span>
                            <strong>{idList(irStatus?.activeIds)}</strong>
                        </div>
                        <div>
                            <span>Missing TA ({irStatus?.missingIds?.length ?? 0})</span>
                            <strong>{idList(irStatus?.missingIds)}</strong>
                        </div>
                    </div>

                    <div class="ir-log">
                        <div class="ir-log-title">Task Log</div>
                        <pre>{irStatus?.output?.join("\n") || "No log yet."}</pre>
                    </div>
                </div>
                <div class="card-actions">
                    <button
                        class="btn-primary"
                        onclick={() => startIrTask("activate")}
                        disabled={irRequestRunning}
                    >
                        <i class="bi bi-play-fill"></i>
                        Activate IR
                    </button>
                    <button
                        class="btn-secondary"
                        onclick={() => startIrTask("scan")}
                        disabled={irRequestRunning}
                    >
                        <i class="bi bi-search"></i>
                        Scan Missing TA
                    </button>
                    <button
                        class="btn-warn"
                        onclick={() => stopIrTask()}
                        disabled={irRequestRunning || !irStatus?.running}
                    >
                        <i class="bi bi-stop-fill"></i>
                        Stop
                    </button>
                </div>
            </div>
        </div>
    {/if}

    {#if activeMaintenanceTab === "tc"}
        <div class="tc-calibration-section">
            <div class="action-card">
                <div class="card-header">
                    <i class="bi bi-sliders2-vertical"></i>
                    <div class="title">Calibrate TC</div>
                </div>
                <div class="card-content">
                    <p>
                        Fixed TC module ID 206. Range is read from register 400015 / 0x000E,
                        calibration is written to 400016 / 0x000F, and current is sampled
                        from 400004 / 0x0003.
                    </p>

                    {#if tcConfiguredStringCount === null}
                        <div class="tc-warning">
                            <i class="bi bi-exclamation-triangle"></i>
                            Cannot verify whether battery strings exist. Refresh the check before running TC actions.
                        </div>
                    {:else if tcConfiguredStringCount > 0}
                        <div class="tc-warning">
                            <i class="bi bi-exclamation-triangle"></i>
                            <div>
                                <strong>{tcConfiguredStringCount} configured string(s) found.</strong>
                                Remove all strings before using Calibrate TC to avoid RS-485/Modbus conflicts.
                                <a href="/setting/strings">Open Strings</a>
                            </div>
                        </div>
                    {/if}

                    <div class="tc-form-grid">
                        <label>
                            Serial Port
                            <input bind:value={tcSerialPort} use:kbd />
                        </label>
                        <label>
                            Calibration Adjustment
                            <input
                                type="number"
                                min="-32767"
                                max="32767"
                                bind:value={tcCalibrationValue}
                                use:kbd
                            />
                        </label>
                        <label>
                            Module ID
                            <input value="206" disabled />
                        </label>
                    </div>

                    <div class="tc-status-panel">
                        <div class="tc-status-line">
                            <span>Status</span>
                            <strong>{tcStatus?.state ?? "idle"}</strong>
                        </div>
                        {#if tcStatus?.type}
                            <div class="tc-status-line">
                                <span>Task</span>
                                <strong>{tcStatus.type}</strong>
                            </div>
                        {/if}
                        {#if tcStatus?.running && (tcStatus.type === "read-current" || tcStatus.type === "calibrate")}
                            <div class="tc-status-line highlight">
                                <span>Current Samples</span>
                                <strong>{tcStatus.currentSampleIndex || 0} / 20</strong>
                            </div>
                        {/if}
                        {#if tcStatus?.error}
                            <div class="ir-error">{tcStatus.error}</div>
                        {/if}
                    </div>

                    <div class="tc-card-grid">
                        <div class="tc-value-card">
                            <span>Transformer Range</span>
                            <strong>{valueOrDash(tcStatus?.transformerRange)}</strong>
                            <small>{hex16(tcStatus?.transformerRange)}</small>
                        </div>
                        <div class="tc-value-card">
                            <span>Calibration</span>
                            <strong>{valueOrDash(tcStatus?.calibrationSigned)}</strong>
                            <small>{hex16(tcStatus?.calibrationRaw)}</small>
                        </div>
                        <div class="tc-value-card current">
                            <span>Latest Current</span>
                            <strong>{currentValueOrDash(tcStatus?.latestCurrentAmps)}</strong>
                            <small>raw {hex16(tcStatus?.latestCurrentRaw)}</small>
                        </div>
                        <div class="tc-value-card">
                            <span>Current Changed</span>
                            <strong>{tcStatus?.currentChanged ? "Yes" : "No"}</strong>
                            <small>{tcStatus?.successfulCurrentSamples ?? 0} successful sample(s)</small>
                        </div>
                    </div>

                    <div class="ir-log">
                        <div class="ir-log-title">TC Task Log</div>
                        <pre>{tcStatus?.output?.join("\n") || "No log yet."}</pre>
                    </div>
                </div>
                <div class="card-actions">
                    <button
                        class="btn-secondary"
                        onclick={refreshTcConfiguredStringCount}
                        disabled={tcStringCheckRunning}
                    >
                        <i class="bi bi-arrow-repeat"></i>
                        {tcStringCheckRunning ? "Checking..." : "Check Strings"}
                    </button>
                    <button
                        class="btn-secondary"
                        onclick={() => startTcTask("read-range")}
                        disabled={tcActionDisabled()}
                    >
                        <i class="bi bi-search"></i>
                        Read Range
                    </button>
                    <button
                        class="btn-primary"
                        onclick={() => startTcTask("read-current")}
                        disabled={tcActionDisabled()}
                    >
                        <i class="bi bi-activity"></i>
                        Read Current 20s
                    </button>
                    <button
                        class="btn-accent"
                        onclick={() => startTcTask("calibrate")}
                        disabled={tcActionDisabled()}
                    >
                        <i class="bi bi-check2-circle"></i>
                        Calibrate TC
                    </button>
                    <button
                        class="btn-warn"
                        onclick={stopTcTask}
                        disabled={tcRequestRunning || !tcStatus?.running}
                    >
                        <i class="bi bi-stop-fill"></i>
                        Stop
                    </button>
                </div>
            </div>
        </div>
    {/if}

    {#if activeMaintenanceTab === "gpio"}
        <div class="gpio-alarm-section">
            <div class="action-card">
                <div class="card-header">
                    <i class="bi bi-hdd-network"></i>
                    <div class="title">GPIO Alarm Output</div>
                </div>
                <div class="card-content">
                    <p>
                        Configure the output value written when each string has an alarm.
                        The normal value is the opposite value.
                    </p>

                    <div class="gpio-script-path">
                        <span>Script</span>
                        <code>{gpioAlarmStatus?.scriptPath ?? "scripts/set_gpio_output.sh"}</code>
                    </div>

                    {#if !gpioAlarmStatus}
                        <div class="tc-warning">
                            <i class="bi bi-exclamation-triangle"></i>
                            GPIO alarm status has not loaded yet. Check that the REST bundle is running.
                        </div>
                    {/if}

                    <div class="gpio-config-grid">
                        <div class:alarm={gpioStateFor(1)?.alarm} class="gpio-string-card">
                            <div class="gpio-card-title">
                                <div>
                                    <strong>String 1</strong>
                                    <span>IO0 / gpio33</span>
                                </div>
                                <em>{gpioAlarmText(gpioStateFor(1))}</em>
                            </div>
                            <label>
                                IO Direction
                                <select
                                    bind:value={gpioString1Direction}
                                    onchange={() => (gpioSettingsDirty = true)}
                                    use:kbd
                                >
                                    <option value="output">Output</option>
                                    <option value="input">Input</option>
                                </select>
                            </label>
                            <label>
                                Alarm Output Value
                                <select
                                    bind:value={gpioString1AlarmValue}
                                    onchange={() => (gpioSettingsDirty = true)}
                                    use:kbd
                                >
                                    <option value={1}>1</option>
                                    <option value={0}>0</option>
                                </select>
                            </label>
                            <div class="gpio-status-grid">
                                <div>
                                    <span>Direction</span>
                                    <strong>{gpioStateFor(1)?.direction ?? gpioString1Direction}</strong>
                                </div>
                                <div>
                                    <span>Normal Value</span>
                                    <strong>{Number(gpioString1AlarmValue) === 1 ? 0 : 1}</strong>
                                </div>
                                <div>
                                    <span>str1 Alarm</span>
                                    <strong>{gpioDisplayValue(gpioStateFor(1)?.alarmRegisterValue)}</strong>
                                </div>
                                <div>
                                    <span>Target Output</span>
                                    <strong>{gpioDisplayValue(gpioStateFor(1)?.desiredValue)}</strong>
                                </div>
                                <div>
                                    <span>Last Written</span>
                                    <strong>{gpioDisplayValue(gpioStateFor(1)?.lastWrittenValue)}</strong>
                                </div>
                                <div>
                                    <span>Applied Direction</span>
                                    <strong>{gpioStateFor(1)?.lastDirection ?? "-"}</strong>
                                </div>
                            </div>
                            {#if gpioStateFor(1)?.alarmReadError}
                                <div class="gpio-error">{gpioStateFor(1)?.alarmReadError}</div>
                            {/if}
                            {#if gpioStateFor(1)?.lastError}
                                <div class="gpio-error">{gpioStateFor(1)?.lastError}</div>
                            {/if}
                        </div>

                        <div class:alarm={gpioStateFor(2)?.alarm} class="gpio-string-card">
                            <div class="gpio-card-title">
                                <div>
                                    <strong>String 2</strong>
                                    <span>IO1 / gpio32</span>
                                </div>
                                <em>{gpioAlarmText(gpioStateFor(2))}</em>
                            </div>
                            <label>
                                IO Direction
                                <select
                                    bind:value={gpioString2Direction}
                                    onchange={() => (gpioSettingsDirty = true)}
                                    use:kbd
                                >
                                    <option value="output">Output</option>
                                    <option value="input">Input</option>
                                </select>
                            </label>
                            <label>
                                Alarm Output Value
                                <select
                                    bind:value={gpioString2AlarmValue}
                                    onchange={() => (gpioSettingsDirty = true)}
                                    use:kbd
                                >
                                    <option value={1}>1</option>
                                    <option value={0}>0</option>
                                </select>
                            </label>
                            <div class="gpio-status-grid">
                                <div>
                                    <span>Direction</span>
                                    <strong>{gpioStateFor(2)?.direction ?? gpioString2Direction}</strong>
                                </div>
                                <div>
                                    <span>Normal Value</span>
                                    <strong>{Number(gpioString2AlarmValue) === 1 ? 0 : 1}</strong>
                                </div>
                                <div>
                                    <span>str2 Alarm</span>
                                    <strong>{gpioDisplayValue(gpioStateFor(2)?.alarmRegisterValue)}</strong>
                                </div>
                                <div>
                                    <span>Target Output</span>
                                    <strong>{gpioDisplayValue(gpioStateFor(2)?.desiredValue)}</strong>
                                </div>
                                <div>
                                    <span>Last Written</span>
                                    <strong>{gpioDisplayValue(gpioStateFor(2)?.lastWrittenValue)}</strong>
                                </div>
                                <div>
                                    <span>Applied Direction</span>
                                    <strong>{gpioStateFor(2)?.lastDirection ?? "-"}</strong>
                                </div>
                            </div>
                            {#if gpioStateFor(2)?.alarmReadError}
                                <div class="gpio-error">{gpioStateFor(2)?.alarmReadError}</div>
                            {/if}
                            {#if gpioStateFor(2)?.lastError}
                                <div class="gpio-error">{gpioStateFor(2)?.lastError}</div>
                            {/if}
                        </div>
                    </div>
                </div>
                <div class="card-actions">
                    <button
                        class="btn-secondary"
                        onclick={getGpioAlarmStatus}
                        disabled={gpioRequestRunning}
                    >
                        <i class="bi bi-arrow-repeat"></i>
                        Refresh Status
                    </button>
                    <button
                        class="btn-primary"
                        onclick={saveGpioAlarmSettings}
                        disabled={gpioRequestRunning}
                    >
                        <i class="bi bi-save"></i>
                        {gpioRequestRunning ? "Saving..." : "Save GPIO Alarm"}
                    </button>
                </div>
            </div>
        </div>
    {/if}
</div>

<!-- Password Dialog -->
{#if showPasswordDialog}
    <div
        class="dialog-overlay"
        role="button"
        tabindex="0"
        onclick={cancelPasswordDialog}
        onkeydown={(e) => e.key === "Escape" && cancelPasswordDialog()}
    >
        <div
            class="dialog"
            role="dialog"
            tabindex="-1"
            onclick={(e) => e.stopPropagation()}
            onkeydown={(e) => e.stopPropagation()}
        >
            <h2>Connect to {selectedNetwork?.ssid}</h2>
            <div class="dialog-content">
                <label for="wifi-password">Password</label>
                <input
                    id="wifi-password"
                    type="password"
                    bind:value={wifiPassword}
                    onkeydown={(e) =>
                        e.key === "Enter" && handlePasswordSubmit()}
                    placeholder="Enter WiFi password"
                    use:kbd
                />
            </div>
            <div class="dialog-actions">
                <button class="btn-secondary" onclick={cancelPasswordDialog}
                    >Cancel</button
                >
                <button
                    class="btn-primary"
                    onclick={handlePasswordSubmit}
                    disabled={!wifiPassword}>Connect</button
                >
            </div>
        </div>
    </div>
{/if}

<style>
    .maintenance-page {
        padding: 24px;
    }

    .title-row {
        display: flex;
        align-items: center;
        gap: 12px;
        margin-bottom: 8px;
    }

    .title-row h1 {
        margin: 0;
        color: var(--primary-color);
        font-size: 1.8rem;
        font-weight: 500;
    }

    .title-icon {
        font-size: 2rem;
        color: var(--accent-color);
    }

    .subtitle {
        color: rgba(0, 0, 0, 0.6);
        margin: 8px 0 24px 0;
        font-size: 1rem;
    }

    .maintenance-tabs {
        display: flex;
        gap: 8px;
        margin-bottom: 18px;
        border-bottom: 1px solid #e5e7eb;
    }

    .maintenance-tabs button {
        background: transparent;
        color: #4b5563;
        border-radius: 0;
        border-bottom: 2px solid transparent;
        padding: 12px 16px;
    }

    .maintenance-tabs button.active {
        color: var(--primary-color);
        border-bottom-color: var(--primary-color);
    }

    .cards {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
        gap: 16px;
        align-items: stretch;
    }

    .action-card {
        background: white;
        border-radius: 12px;
        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
        display: flex;
        flex-direction: column;
        overflow: hidden;
        transition: box-shadow 0.2s;
    }

    .action-card:hover {
        box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
    }

    .card-header {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 16px;
        border-bottom: 1px solid #eee;
        background-color: #fafafa;
    }

    .card-header i {
        font-size: 1.5rem;
        color: var(--primary-color);
    }

    .card-header .title {
        font-size: 1.1rem;
        font-weight: 600;
        color: #333;
    }

    .card-content {
        padding: 16px;
        flex: 1;
    }

    .card-content p {
        margin: 0;
        color: #666;
        font-size: 0.9rem;
        line-height: 1.5;
    }

    .card-actions {
        display: flex;
        gap: 8px;
        padding: 12px 16px;
        border-top: 1px solid #eee;
        background-color: #fafafa;
    }

    button {
        display: flex;
        align-items: center;
        gap: 6px;
        padding: 10px 16px;
        border: none;
        border-radius: 6px;
        font-size: 0.875rem;
        font-weight: 500;
        cursor: pointer;
        transition: all 0.2s;
    }

    .btn-sm {
        padding: 6px 12px;
        font-size: 0.8rem;
    }

    .btn-primary {
        background-color: var(--primary-color);
        color: white;
    }

    .btn-primary:hover:not(:disabled) {
        background-color: #013bb5;
    }

    .btn-primary:disabled {
        opacity: 0.6;
        cursor: not-allowed;
    }

    .btn-secondary {
        background-color: white;
        color: var(--primary-color);
        border: 1px solid var(--primary-color);
    }

    .btn-secondary:hover:not(:disabled) {
        background-color: #f5f5f5;
    }

    .btn-secondary:disabled {
        opacity: 0.6;
        cursor: not-allowed;
    }

    .btn-warn {
        background-color: var(--warn-color);
        color: white;
    }

    .btn-warn:hover:not(:disabled) {
        background-color: #d32f2f;
    }

    .btn-warn:disabled {
        opacity: 0.6;
        cursor: not-allowed;
    }

    .btn-accent {
        background-color: var(--accent-color);
        color: white;
    }

    .btn-accent:hover:not(:disabled) {
        background-color: #ff6f00;
    }

    .btn-accent:disabled {
        opacity: 0.6;
        cursor: not-allowed;
    }

    .ir-test-section {
        margin-top: 8px;
    }

    .ir-form-grid {
        display: grid;
        grid-template-columns: minmax(220px, 2fr) minmax(120px, 1fr) minmax(120px, 1fr);
        gap: 12px;
        margin-bottom: 16px;
    }

    .ir-form-grid label {
        display: flex;
        flex-direction: column;
        gap: 6px;
        color: #374151;
        font-size: 0.85rem;
        font-weight: 600;
    }

    .ir-form-grid input {
        padding: 10px 12px;
        border: 1px solid #d1d5db;
        border-radius: 6px;
        font-size: 0.95rem;
    }

    .ir-status-panel {
        display: grid;
        gap: 8px;
        padding: 12px;
        border: 1px solid #e5e7eb;
        border-radius: 8px;
        background: #f9fafb;
        margin-bottom: 16px;
    }

    .ir-status-line {
        display: flex;
        justify-content: space-between;
        gap: 12px;
        color: #4b5563;
        font-size: 0.9rem;
    }

    .ir-status-line strong {
        color: #111827;
    }

    .ir-status-line.highlight strong {
        color: var(--primary-color);
    }

    .ir-error {
        color: #b91c1c;
        font-weight: 600;
    }

    .ir-result-grid {
        display: grid;
        grid-template-columns: repeat(3, minmax(0, 1fr));
        gap: 12px;
        margin-bottom: 16px;
    }

    .ir-activated-panel {
        padding: 12px;
        border: 1px solid #bfdbfe;
        border-left: 4px solid var(--primary-color);
        border-radius: 8px;
        background: #eff6ff;
        margin-bottom: 12px;
        min-height: 70px;
    }

    .ir-activated-panel span {
        display: block;
        color: var(--primary-color);
        font-size: 0.78rem;
        font-weight: 700;
        margin-bottom: 6px;
        text-transform: uppercase;
    }

    .ir-activated-panel strong {
        display: block;
        color: #111827;
        font-size: 0.95rem;
        font-weight: 600;
        line-height: 1.4;
        word-break: break-word;
    }

    .ir-result-grid > div {
        padding: 12px;
        border: 1px solid #e5e7eb;
        border-radius: 8px;
        background: #ffffff;
        min-height: 78px;
    }

    .ir-result-grid span {
        display: block;
        color: #6b7280;
        font-size: 0.78rem;
        font-weight: 600;
        margin-bottom: 6px;
        text-transform: uppercase;
    }

    .ir-result-grid strong {
        display: block;
        color: #111827;
        font-size: 0.9rem;
        font-weight: 600;
        line-height: 1.4;
        word-break: break-word;
    }

    .ir-log {
        border: 1px solid #e5e7eb;
        border-radius: 8px;
        overflow: hidden;
        background: #111827;
    }

    .ir-log-title {
        padding: 10px 12px;
        color: #f9fafb;
        background: #1f2937;
        font-weight: 600;
        font-size: 0.85rem;
    }

    .ir-log pre {
        margin: 0;
        padding: 12px;
        color: #d1d5db;
        font-size: 0.8rem;
        line-height: 1.5;
        max-height: 320px;
        overflow: auto;
        white-space: pre-wrap;
    }

    .tc-calibration-section {
        margin-top: 8px;
    }

    .tc-warning {
        display: flex;
        align-items: flex-start;
        gap: 10px;
        margin: 16px 0;
        padding: 12px;
        border: 1px solid #fed7aa;
        border-left: 4px solid #f97316;
        border-radius: 8px;
        background: #fff7ed;
        color: #9a3412;
        font-size: 0.9rem;
        line-height: 1.45;
    }

    .tc-warning i {
        margin-top: 2px;
        color: #f97316;
    }

    .tc-warning a {
        display: inline-block;
        margin-left: 6px;
        color: var(--primary-color);
        font-weight: 700;
        text-decoration: none;
    }

    .tc-warning a:hover {
        text-decoration: underline;
    }

    .tc-form-grid {
        display: grid;
        grid-template-columns: minmax(220px, 2fr) minmax(170px, 1fr) minmax(120px, 1fr);
        gap: 12px;
        margin: 16px 0;
    }

    .tc-form-grid label {
        display: flex;
        flex-direction: column;
        gap: 6px;
        color: #374151;
        font-size: 0.85rem;
        font-weight: 600;
    }

    .tc-form-grid input {
        padding: 10px 12px;
        border: 1px solid #d1d5db;
        border-radius: 6px;
        font-size: 0.95rem;
    }

    .tc-form-grid input:disabled {
        background: #f3f4f6;
        color: #6b7280;
    }

    .tc-status-panel {
        display: grid;
        gap: 8px;
        padding: 12px;
        border: 1px solid #e5e7eb;
        border-radius: 8px;
        background: #f9fafb;
        margin-bottom: 16px;
    }

    .tc-status-line {
        display: flex;
        justify-content: space-between;
        gap: 12px;
        color: #4b5563;
        font-size: 0.9rem;
    }

    .tc-status-line strong {
        color: #111827;
    }

    .tc-status-line.highlight strong {
        color: var(--primary-color);
    }

    .tc-card-grid {
        display: grid;
        grid-template-columns: repeat(4, minmax(0, 1fr));
        gap: 12px;
        margin-bottom: 16px;
    }

    .tc-value-card {
        padding: 14px;
        border: 1px solid #e5e7eb;
        border-radius: 8px;
        background: #ffffff;
        min-height: 90px;
    }

    .tc-value-card.current {
        border-color: #bfdbfe;
        background: #eff6ff;
    }

    .tc-value-card span {
        display: block;
        color: #6b7280;
        font-size: 0.76rem;
        font-weight: 700;
        margin-bottom: 8px;
        text-transform: uppercase;
    }

    .tc-value-card strong {
        display: block;
        color: #111827;
        font-size: 1.4rem;
        font-weight: 700;
        line-height: 1.2;
    }

    .tc-value-card small {
        display: block;
        color: #6b7280;
        margin-top: 6px;
        font-size: 0.78rem;
        word-break: break-word;
    }

    .gpio-alarm-section {
        margin-top: 8px;
    }

    .gpio-script-path {
        display: flex;
        align-items: center;
        gap: 10px;
        margin: 16px 0;
        padding: 10px 12px;
        border: 1px solid #e5e7eb;
        border-radius: 8px;
        background: #f9fafb;
        color: #4b5563;
        font-size: 0.85rem;
    }

    .gpio-script-path span {
        font-weight: 700;
        color: #374151;
    }

    .gpio-script-path code {
        color: #111827;
        word-break: break-word;
    }

    .gpio-config-grid {
        display: grid;
        grid-template-columns: repeat(2, minmax(0, 1fr));
        gap: 12px;
    }

    .gpio-string-card {
        padding: 14px;
        border: 1px solid #e5e7eb;
        border-radius: 10px;
        background: #ffffff;
    }

    .gpio-string-card.alarm {
        border-color: #fecaca;
        background: #fef2f2;
    }

    .gpio-card-title {
        display: flex;
        justify-content: space-between;
        gap: 12px;
        margin-bottom: 14px;
    }

    .gpio-card-title strong,
    .gpio-card-title span {
        display: block;
    }

    .gpio-card-title strong {
        color: #111827;
        font-size: 1rem;
    }

    .gpio-card-title span {
        color: #6b7280;
        font-size: 0.82rem;
        margin-top: 2px;
    }

    .gpio-card-title em {
        align-self: flex-start;
        padding: 4px 8px;
        border-radius: 999px;
        background: #dcfce7;
        color: #166534;
        font-size: 0.78rem;
        font-style: normal;
        font-weight: 700;
    }

    .gpio-string-card.alarm .gpio-card-title em {
        background: #fee2e2;
        color: #991b1b;
    }

    .gpio-string-card label {
        display: flex;
        flex-direction: column;
        gap: 6px;
        color: #374151;
        font-size: 0.85rem;
        font-weight: 600;
        margin-bottom: 12px;
    }

    .gpio-string-card select {
        padding: 10px 12px;
        border: 1px solid #d1d5db;
        border-radius: 6px;
        font-size: 0.95rem;
        background: #ffffff;
    }

    .gpio-status-grid {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
        gap: 8px;
    }

    .gpio-status-grid > div {
        padding: 10px;
        border: 1px solid #e5e7eb;
        border-radius: 8px;
        background: #ffffff;
    }

    .gpio-status-grid span {
        display: block;
        color: #6b7280;
        font-size: 0.72rem;
        font-weight: 700;
        margin-bottom: 4px;
        text-transform: uppercase;
    }

    .gpio-status-grid strong {
        color: #111827;
        font-size: 1.1rem;
    }

    .gpio-error {
        margin-top: 10px;
        color: #b91c1c;
        font-weight: 600;
        font-size: 0.84rem;
    }

    /* WiFi Section Styles */
    .wifi-section {
        margin-top: 24px;
    }

    .wifi-card {
        max-width: 100%;
    }

    .scan-button-container {
        margin: 16px 0;
        display: flex;
        align-items: center;
        gap: 16px;
    }

    .current-ip {
        display: inline-flex;
        align-items: center;
        gap: 8px;
        color: #4caf50;
        font-weight: 500;
        padding: 8px 12px;
        background-color: rgba(76, 175, 80, 0.1);
        border-radius: 6px;
    }

    .loading-container {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        padding: 32px;
        gap: 16px;
    }

    .spinner {
        width: 40px;
        height: 40px;
        border: 4px solid #f3f3f3;
        border-top: 4px solid var(--primary-color);
        border-radius: 50%;
        animation: spin 1s linear infinite;
    }

    @keyframes spin {
        0% {
            transform: rotate(0deg);
        }
        100% {
            transform: rotate(360deg);
        }
    }

    .wifi-table-container {
        margin-top: 16px;
        overflow-x: auto;
    }

    .wifi-table {
        width: 100%;
        border-collapse: collapse;
    }

    .wifi-table th {
        text-align: left;
        padding: 12px;
        background-color: #f5f5f5;
        font-weight: 600;
        border-bottom: 2px solid #ddd;
    }

    .wifi-table td {
        padding: 12px;
        border-bottom: 1px solid #eee;
    }

    .wifi-table tr:hover {
        background-color: #fafafa;
    }

    .ssid-cell {
        display: flex;
        align-items: center;
        gap: 8px;
    }

    .connected-icon {
        color: #4caf50;
        font-size: 1.2rem;
    }

    .connected-text {
        font-weight: 600;
        color: #4caf50;
    }

    .signal-cell {
        display: flex;
        align-items: center;
        gap: 8px;
    }

    .signal-excellent {
        color: #4caf50;
    }

    .signal-good {
        color: #ff9800;
    }

    .signal-weak {
        color: #f44336;
    }

    .security-icon {
        margin-right: 4px;
        color: #666;
    }

    .no-networks {
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        padding: 48px 16px;
        text-align: center;
        color: rgba(0, 0, 0, 0.54);
    }

    .no-networks i {
        font-size: 4rem;
        margin-bottom: 16px;
        opacity: 0.5;
    }

    /* Dialog Styles */
    .dialog-overlay {
        position: fixed;
        top: 0;
        left: 0;
        right: 0;
        bottom: 0;
        background-color: rgba(0, 0, 0, 0.5);
        display: flex;
        align-items: center;
        justify-content: center;
        z-index: 1000;
    }

    .dialog {
        background: white;
        border-radius: 12px;
        padding: 24px;
        min-width: 400px;
        max-width: 90%;
        box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
    }

    .dialog h2 {
        margin: 0 0 16px 0;
        font-size: 1.3rem;
        color: #333;
    }

    .dialog-content {
        margin-bottom: 24px;
    }

    .dialog-content label {
        display: block;
        margin-bottom: 8px;
        font-weight: 500;
        color: #555;
    }

    .dialog-content input {
        width: 100%;
        padding: 10px;
        border: 1px solid #ddd;
        border-radius: 6px;
        font-size: 1rem;
        box-sizing: border-box;
    }

    .dialog-content input:focus {
        outline: none;
        border-color: var(--primary-color);
    }

    .dialog-actions {
        display: flex;
        gap: 8px;
        justify-content: flex-end;
    }

    @media (max-width: 900px) {
        .cards {
            grid-template-columns: 1fr;
        }

        .dialog {
            min-width: 300px;
        }

        .ir-form-grid,
        .ir-result-grid,
        .tc-form-grid,
        .tc-card-grid,
        .gpio-config-grid,
        .gpio-status-grid {
            grid-template-columns: 1fr;
        }
    }
</style>
