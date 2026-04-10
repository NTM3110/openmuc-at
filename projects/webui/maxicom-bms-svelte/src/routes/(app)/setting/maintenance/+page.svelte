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

    let isLoading = $state(false);
    let wifiNetworks = $state<WifiNetwork[]>([]);
    let isScanning = $state(false);
    let showPasswordDialog = $state(false);
    let selectedNetwork = $state<WifiNetwork | null>(null);
    let wifiPassword = $state("");
    let wifiStatus = $state<WifiStatus | null>(null);
    let fileInput: HTMLInputElement;

    onMount(() => {
        getWifiStatus();
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
        System maintenance actions. These actions are UI-only placeholders for
        now.
    </p>

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

    <!-- WiFi Configuration Section -->
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
    }
</style>
