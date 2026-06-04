<script lang="ts">
    import { page } from "$app/stores";
    import {
        stringsState,
        stringsLoaded,
        isLoadingFromApi,
        loadStringConfig,
    } from "$lib/services/battery-string";
    import { getSummaryData, getCellsData } from "$lib/services/openmuc";
    import { onDestroy } from "svelte";
    import type { Subscription } from "rxjs";
    import type { StringSummaryData, CellData } from "$lib/services/openmuc";
    import { Chart, registerables } from "chart.js";
    import type { ChartConfiguration } from "chart.js";
    import ScheduleDialog from "$lib/components/ScheduleDialog.svelte";
    import CsvExportDialog from "$lib/components/CsvExportDialog.svelte";
    import type { Schedule } from "$lib/services/schedule";
    import { loadSchedules, stopSchedule } from "$lib/services/schedule";
    import { getCellAlarmReasons, getPrimaryAlarmLabel } from "$lib/services/alarm";

    Chart.register(...registerables);

    const stringId = $derived($page.params.stringId ?? "");

    let showScheduleDialog = $state(false);
    let showCsvExportDialog = $state(false);

    let schedules: Schedule[] = $state([]);
    let schedulesLoading = $state(false);
    let schedulesError: string | null = $state(null);

    // Extract the string index from the ID (e.g., "str1" -> 1)
    let stringIndexMatch = $derived(stringId.match(/^str(\d+)$/));
    let stringIndex = $derived(
        stringIndexMatch ? parseInt(stringIndexMatch[1], 10) : null,
    );

    // Reactive battery string derived from store
    let batteryString = $derived(
        stringIndex
            ? $stringsState.find((s) => s.stringIndex === stringIndex)
            : null,
    );
    // let cells: CellData[] = $state([]);
    // let visibleCellCount = $state(20);
    let cellGroups: CellData[][] = $derived.by(() => {
        const groups: CellData[][] = [[], [], []];

        const limit = Math.min(visibleCellCount, cells.length);

        for (let i = 0; i < limit; i++) {
            groups[i % 3].push(cells[i]);
        }

        return groups;
    });




    const activeSchedule = $derived(
        schedules.find((s) => s.status === "running") ?? null,
    );

    let summary: StringSummaryData | null = $state(null);
    let cells: CellData[] = $state([]);

    let summarySubscription: Subscription | null = null;
    let cellsSubscription: Subscription | null = null;

    let isDirectLoading = $state(false);
    let attemptedLoadIndex: number | null = $state(null);

    let volRstChart: Chart | null = null;
    let tempIrChart: Chart | null = null;
    let socSohChart: Chart | null = null;

    let volRstCanvas: HTMLCanvasElement | undefined = $state();
    let tempIrCanvas: HTMLCanvasElement | undefined = $state();
    let socSohCanvas: HTMLCanvasElement | undefined = $state();

    let isChartsInitialized = false;

    let activeTab = $state("vol-rst");
    let visibleCellCount = $state(20);
    let renderInterval: any;

    function cellAlarmReasons(cell: CellData): string[] {
        return batteryString ? getCellAlarmReasons(cell, batteryString) : [];
    }

    /**
     * Effects
     */

    // Effect to load string config if missing
    $effect(() => {
        if (
            stringIndex &&
            !batteryString &&
            !isDirectLoading &&
            !$isLoadingFromApi &&
            attemptedLoadIndex !== stringIndex
        ) {
            isDirectLoading = true;
            attemptedLoadIndex = stringIndex;
            loadStringConfig(stringIndex).finally(() => {
                isDirectLoading = false;
            });
        }
    });

    // Effect to subscribe to data when batteryString becomes available
    $effect(() => {
        if (batteryString) {
            const baseStringName = `str${batteryString.stringIndex}`;

            // Unsubscribe previous subscriptions if any
            if (summarySubscription) summarySubscription.unsubscribe();
            if (cellsSubscription) cellsSubscription.unsubscribe();

            summarySubscription = getSummaryData(baseStringName).subscribe(
                (data) => {
                    summary = data;
                },
            );

            if (batteryString.cellQty > 0) {
                cellsSubscription = getCellsData(
                    baseStringName,
                    batteryString.cellQty,
                ).subscribe((data) => {
                    cells = data;
                    updateCharts(data);
                });
            }
        }

        // Cleanup function
        return () => {
            if (summarySubscription) summarySubscription.unsubscribe();
            if (cellsSubscription) cellsSubscription.unsubscribe();
        };
    });

    // Effect to initialize charts when canvases are ready
    $effect(() => {
        if (
            volRstCanvas &&
            tempIrCanvas &&
            socSohCanvas &&
            !isChartsInitialized
        ) {
            initCharts();
            isChartsInitialized = true;
        }
    });

    $effect(() => {
        if (batteryString) {
            refreshSchedules();
        }
    });

    // progressive render for table
    let lastCellLength = 0;

    $effect(() => {
        if (cells.length === 0) {
            clearInterval(renderInterval);
            lastCellLength = 0;
            return;
        }

        // Only restart progressive render if cell count changes
        if (cells.length !== lastCellLength) {
            lastCellLength = cells.length;
            visibleCellCount = Math.min(20, cells.length);

            clearInterval(renderInterval);
            renderInterval = setInterval(() => {
                if (visibleCellCount < cells.length) {
                    visibleCellCount += 20;
                } else {
                    clearInterval(renderInterval);
                }
            }, 100);
        }
    });


    // Update charts when activeTab changes
    $effect(() => {
        if (cells.length > 0) {
            updateActiveChart(cells);
        }
    });
     $effect(() => {
        const hasPending = hasPendingSchedule(schedules);

        if (hasPending) {
            lastScheduleSignature = scheduleSignature(schedules);
            startPendingWatcher();
        } else {
            stopPendingWatcher();
        }

        return stopPendingWatcher;
    });


    onDestroy(() => {
        summarySubscription?.unsubscribe();
        cellsSubscription?.unsubscribe();
        volRstChart?.destroy();
        tempIrChart?.destroy();
        socSohChart?.destroy();
        clearInterval(renderInterval);
    });

    /**
     * Functions
     */
    function scheduleSignature(list: Schedule[]) {
        return list
            .map(s =>
                [
                    s.id,
                    s.status,
                    s.startTime,
                    s.endTime,
                    s.ratedCurrent,
                    s.soh,
                ].join("|")
            )
            .sort()
            .join("::");
    }

    let lastScheduleSignature = "";
    let pendingWatcherTimer: ReturnType<typeof setInterval> | null = null;

    async function refreshSchedulesIfChanged() {
        if (!batteryString) return;

        const next = await loadSchedules(`str${batteryString.stringIndex}`);
        const nextSignature = scheduleSignature(next);

        if (nextSignature !== lastScheduleSignature) {
            schedules = next;
            lastScheduleSignature = nextSignature;
        }
    }
    
    function openScheduleDialog() {
        // if (!batteryString) return;
        showScheduleDialog = true;
    }

    function closeScheduleDialog() {
        showScheduleDialog = false;
    }
    function hasRunningSchedule(list: Schedule[]) {
        return list.some((s) => s.status === "running");
    }
    function hasPendingSchedule(list: Schedule[]) {
        return list.some(
            (s) => s.status === "pending"
        );
    }
    function startPendingWatcher() {
        if (pendingWatcherTimer) return;

        pendingWatcherTimer = setInterval(refreshSchedulesIfChanged, 15000);
    }

    function stopPendingWatcher() {
        if (pendingWatcherTimer) {
            clearInterval(pendingWatcherTimer);
            pendingWatcherTimer = null;
        }
    }

    function formatDate(ms: number | null) {
        if (!ms) return "-";
        return new Date(ms).toLocaleString();
    }

    async function refreshSchedules() {
        if (!batteryString) return;
        schedulesLoading = true;
        schedulesError = null;
        try {
            schedules = await loadSchedules(`str${batteryString.stringIndex}`);
        } catch (e) {
            schedulesError = "Failed to load discharge history";
        } finally {
            schedulesLoading = false;
        }
    }

    async function handleStop(scheduleId: string) {
        if (!batteryString) return;
        try {
            await stopSchedule(`str${batteryString.stringIndex}`, scheduleId);
            await refreshSchedules();
            setTimeout(() => refreshSchedules(), 1500);
        } catch {
            schedulesError = "Failed to stop discharge";
        }
    }

    function initCharts() {
        if (!volRstCanvas || !tempIrCanvas || !socSohCanvas) return;

        const chartOptions: ChartConfiguration["options"] = {
            responsive: true,
            maintainAspectRatio: false,
            interaction: {
                mode: "index",
                intersect: false,
            },
            scales: {
                x: {
                    display: true,
                    title: {
                        display: true,
                        text: "Cell ID",
                    },
                },
                "y-axis-l": {
                    type: "linear",
                    position: "left",
                    display: true,
                    grid: {
                        drawOnChartArea: false,
                    },
                },
                "y-axis-r": {
                    type: "linear",
                    position: "right",
                    display: true,
                    grid: {
                        drawOnChartArea: false,
                    },
                },
            },
        };

        // Voltage & Resistance Chart
        volRstChart = new Chart(volRstCanvas, {
            type: "bar",
            data: {
                labels: [],
                datasets: [
                    {
                        data: [],
                        label: "Voltage (V)",
                        yAxisID: "y-axis-l",
                        borderColor: "#4CAF50",
                        backgroundColor: "rgba(76, 175, 80, 0.7)",
                        type: "bar",
                        order: 2,
                    },
                    {
                        data: [],
                        label: "Resistance (uOhm)",
                        yAxisID: "y-axis-r",
                        borderColor: "#FF9800",
                        backgroundColor: "rgba(255, 152, 0, 0.1)",
                        fill: "origin",
                        type: "line",
                        order: 1,
                    },
                ],
            },
            options: {
                ...chartOptions,
                scales: {
                    ...chartOptions.scales,
                    "y-axis-l": {
                        ...chartOptions.scales?.["y-axis-l"],
                        title: { display: true, text: "Voltage (V)" },
                    },
                    "y-axis-r": {
                        ...chartOptions.scales?.["y-axis-r"],
                        title: { display: true, text: "Resistance (uOhm)" },
                    },
                },
            },
        });

        // Temperature & IR Chart
        tempIrChart = new Chart(tempIrCanvas, {
            type: "bar",
            data: {
                labels: [],
                datasets: [
                    {
                        data: [],
                        label: "Temperature (°C)",
                        yAxisID: "y-axis-l",
                        borderColor: "#F44336",
                        backgroundColor: "rgba(244, 67, 54, 0.7)",
                        type: "bar",
                        order: 2,
                    },
                    {
                        data: [],
                        label: "IR (U)",
                        yAxisID: "y-axis-r",
                        borderColor: "#2196F3",
                        backgroundColor: "rgba(33, 150, 243, 0.1)",
                        fill: "origin",
                        type: "line",
                        order: 1,
                    },
                ],
            },
            options: {
                ...chartOptions,
                scales: {
                    ...chartOptions.scales,
                    "y-axis-l": {
                        ...chartOptions.scales?.["y-axis-l"],
                        title: { display: true, text: "Temperature (°C)" },
                    },
                    "y-axis-r": {
                        ...chartOptions.scales?.["y-axis-r"],
                        title: { display: true, text: "IR (U)" },
                    },
                },
            },
        });

        // SoC & SoH Chart
        socSohChart = new Chart(socSohCanvas, {
            type: "bar",
            data: {
                labels: [],
                datasets: [
                    {
                        data: [],
                        label: "SoC (%)",
                        yAxisID: "y-axis-l",
                        borderColor: "#00BCD4",
                        backgroundColor: "rgba(0, 188, 212, 0.7)",
                        type: "bar",
                        order: 2,
                    },
                    {
                        data: [],
                        label: "SoH (%)",
                        yAxisID: "y-axis-r",
                        borderColor: "#9C27B0",
                        backgroundColor: "rgba(156, 39, 176, 0.1)",
                        fill: "origin",
                        type: "line",
                        order: 1,
                    },
                ],
            },
            options: {
                ...chartOptions,
                scales: {
                    ...chartOptions.scales,
                    "y-axis-l": {
                        ...chartOptions.scales?.["y-axis-l"],
                        title: { display: true, text: "SoC (%)" },
                    },
                    "y-axis-r": {
                        ...chartOptions.scales?.["y-axis-r"],
                        title: { display: true, text: "SoH (%)" },
                    },
                },
            },
        });

        if (cells.length > 0) updateCharts(cells);
    }

    function updateCharts(cellData: CellData[]) {
        updateActiveChart(cellData);
    }

    function updateActiveChart(cellData: CellData[]) {
        if (!cellData.length) return;
        const labels = cellData.map((c) => `${c.ID}`);

        if (activeTab === "vol-rst" && volRstChart) {
            volRstChart.data.labels = labels;
            volRstChart.data.datasets[0].data = cellData.map((c) => c.Vol ?? 0);
            volRstChart.data.datasets[1].data = cellData.map((c) => c.Rst ?? 0);
            volRstChart.update();
        } else if (activeTab === "temp-ir" && tempIrChart) {
            tempIrChart.data.labels = labels;
            tempIrChart.data.datasets[0].data = cellData.map((c) => c.Temp ?? 0);
            tempIrChart.data.datasets[1].data = cellData.map((c) => c.IR ?? 0);
            tempIrChart.update();
        } else if (activeTab === "soc-soh" && socSohChart) {
            socSohChart.data.labels = labels;
            socSohChart.data.datasets[0].data = cellData.map((c) => c.SoC ?? 0);
            socSohChart.data.datasets[1].data = cellData.map((c) => c.SoH ?? 0);
            socSohChart.update();
        }
    }
    function formatNumber(value: unknown, digits: number) {
        if (value === null || value === undefined || value === "") return "-";
        const n = typeof value === "number" ? value : Number(value);
        if (!Number.isFinite(n)) return "-";
        return n.toFixed(digits);
    }
    function getActiveTabLabel() {
        return activeTab === "vol-rst"
            ? "voltage_resistance"
            : activeTab === "temp-ir"
            ? "temperature_ir"
            : "soc_soh";
    }

    function openCsvExportDialog() {
        showCsvExportDialog = true;
    }

    async function handleCsvExport(event: CustomEvent<{ start: string; end: string }>) {
        if (!batteryString) return;
        const { start, end } = event.detail;
        const stringId = batteryString.stringIndex;
        
        const url = `/rest/export_csv_range?stringId=${stringId}&start=${start}&end=${end}`;
        
        try {
            const response = await fetch(url);
            if (!response.ok) {
                const errorText = await response.text();
                alert(`Export failed: ${errorText || response.statusText}`);
                return;
            }

            const blob = await response.blob();
            const downloadUrl = URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = downloadUrl;
            
            // Try to get filename from content-disposition header
            const contentDisposition = response.headers.get('Content-Disposition');
            let filename = `export_str${stringId}_${start}_to_${end}.zip`;
            if (contentDisposition && contentDisposition.includes('filename=')) {
                const match = contentDisposition.match(/filename="?([^"]+)"?/);
                if (match && match[1]) {
                    filename = match[1];
                }
            }
            
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(downloadUrl);
        } catch (error) {
            console.error('Export error:', error);
            alert('An unexpected error occurred during export.');
        }
    }

    function beforePrint() {
        // force render all cells for print
        visibleCellCount = cells.length;
    }

    function afterPrint() {
        // restore progressive render after print
        visibleCellCount = Math.min(20, cells.length);
    }


    function printData() {
        beforePrint();
        window.print();
        afterPrint(); 
    }


</script>

<div class="container-fluid page-shell">
    <div id="print-area">
    {#if !batteryString}
        {#if $isLoadingFromApi || !$stringsLoaded || isDirectLoading}
            <div class="text-center py-5">
                <div class="spinner-border text-primary" role="status">
                    <span class="visually-hidden">Loading configuration...</span>
                </div>
                <p class="mt-2 text-muted">Loading string configuration...</p>
            </div>
        {:else}
            <div class="alert alert-warning">
                Battery string not found.
            </div>
            <div class="mb-3">
                <a href="/dashboard" class="back-link">
                    <i class="bi bi-arrow-left"></i>
                    <span>Back to list</span>
                </a>
            </div>
        {/if}
    {:else}
        <div class="mb-3">
            <a href="/dashboard" class="back-link">
                <i class="bi bi-arrow-left"></i>
                <span>Back to list</span>
            </a>
        </div>


        {#if activeSchedule}
            <div class="alert alert-danger d-flex justify-content-between align-items-center mb-3">
                <div>
                    <strong>Active discharge cycle:</strong>
                    {formatDate(activeSchedule.startTime)}
                    —
                    Discharge Current: {activeSchedule.ratedCurrent ?? "-"} A
                </div>

                <button
                    class="btn btn-sm btn-light"
                    onclick={() => handleStop(activeSchedule.id)}
                >
                    Stop
                </button>
            </div>
        {/if}

        <!-- GENERAL INFO (wide, like reference) -->
        <div class="section-card">
            <div class="section-body">
                <div class="d-flex align-items-center gap-5 flex-wrap">
                    <div>
                        <div class="k-label">STRING</div>
                        <div class="k-value">
                            {batteryString.stringName}
                        </div>
                    </div>

                    <div>
                        <div class="k-label">CELL QTY</div>
                        <div class="k-value">
                            {batteryString.cellQty}
                        </div>
                    </div>

                    <div>
                        <div class="k-label">CELL BRAND</div>
                        <div class="k-value">
                            {batteryString.cellBrand ?? "-"}
                        </div>
                    </div>

                    <div>
                        <div class="k-label">CELL MODEL</div>
                        <div class="k-value">
                            {batteryString.cellModel ?? "-"}
                        </div>
                    </div>

                    <!-- push buttons to the far right -->
                    <div class="ms-auto d-flex gap-2">
                        <button class="btn btn-outline-primary btn-sm"
                            onclick={openScheduleDialog}
                            disabled={!batteryString || hasRunningSchedule(schedules)}
                        >
                            <i class="bi bi-clock me-1"></i>
                            Schedule
                        </button>
                        <button
                            class="btn btn-outline-secondary btn-sm"
                            onclick={openCsvExportDialog}
                        >
                            <i class="bi bi-download me-1"></i>
                            Export
                        </button>

                        <button
                            class="btn btn-outline-secondary btn-sm"
                            onclick={printData}
                        >
                            <i class="bi bi-printer me-1"></i>
                            Print
                        </button>
                    </div>

                </div>
            </div>
        </div>


        <!-- STRING DATA -->
        <div class="section-card">
            <div class="section-header">String Data</div>

            {#if summary}
                <div class="section-body p-0">
                    <div class="table-responsive">
                        <table class="table bms-table mb-0 align-middle">
                            <thead class="k-table-head">
                                <tr>
                                    <th>String Vol (V)</th>
                                    <th>Curr (A)</th>
                                    <th>String SoC (%)</th>
                                    <th>String SoH (%)</th>
                                    <th>Max Vol ID</th>
                                    <th>Max Vol Val (V)</th>
                                    <th>Min Vol ID</th>
                                    <th>Min Vol Val (V)</th>
                                    <th>Max Temp ID</th>
                                    <th>Max Temp Val (°C)</th>
                                    <th>Min Temp ID</th>
                                    <th>Min Temp Val (°C)</th>
                                    <th>Max Rst ID</th>
                                    <th>Max Rst Val (uOhm)</th>
                                    <th>Min Rst ID</th>
                                    <th>Min Rst Val (uOhm)</th>
                                    <th>Avg Vol (V)</th>
                                    <th>Avg Temp (°C)</th>
                                    <th>Avg Rst (uOhm)</th>
                                </tr>
                            </thead>
                            <tbody class="k-table-values">
                                <tr>
                                    <td>{summary.totalVoltage?.toFixed(3) ?? "N/A"}</td>
                                    <td>{summary.stringCurrent?.toFixed(3) ?? "N/A"}</td>
                                    <td>{summary.stringSoC?.toFixed(3) ?? "N/A"}</td>
                                    <td>{summary.stringSoH?.toFixed(3) ?? "N/A"}</td>
                                    <td>{summary.maxVolId ?? "N/A"}</td>
                                    <td>{summary.maxVoltageValue?.toFixed(3) ?? "N/A"}</td>
                                    <td>{summary.minVolId ?? "N/A"}</td>
                                    <td>{summary.minVoltageValue?.toFixed(3) ?? "N/A"}</td>
                                    <td>{summary.maxTempId ?? "N/A"}</td>
                                    <td>{summary.maxTempValue?.toFixed(1) ?? "N/A"}</td>
                                    <td>{summary.minTempId ?? "N/A"}</td>
                                    <td>{summary.minTempValue?.toFixed(1) ?? "N/A"}</td>
                                    <td>{summary.maxRstId ?? "N/A"}</td>
                                    <td>{summary.maxRstValue?.toFixed(1) ?? "N/A"}</td>
                                    <td>{summary.minRstId ?? "N/A"}</td>
                                    <td>{summary.minRstValue?.toFixed(1) ?? "N/A"}</td>
                                    <td>{summary.avgVoltage?.toFixed(3) ?? "N/A"}</td>
                                    <td>{summary.avgTemp?.toFixed(1) ?? "N/A"}</td>
                                    <td>{summary.avgRst?.toFixed(1) ?? "N/A"}</td>
                                </tr>
                            </tbody>
                        </table>
                    </div>
                </div>
            {:else}
                <div class="text-center py-4">
                    <div class="spinner-border text-primary" role="status">
                        <span class="visually-hidden">Loading...</span>
                    </div>
                </div>
            {/if}
        </div>

        <!-- DISCHARGE HISTORY -->
        <div class="section-card">
            <div class="section-header d-flex align-items-center justify-content-between">
                <span>Discharge History</span>
                <button
                    class="btn btn-sm btn-outline-secondary"
                    onclick={refreshSchedules}
                    disabled={schedulesLoading}
                >
                    Refresh
                </button>
            </div>

            <div class="section-body p-0">
                <div class="table-responsive schedule-table-scroll">
                    <table class="table table-hover bms-table discharge-table mb-0 align-middle">
                        <thead class="table-light">
                            <tr>
                                <th class="col-status">Status</th>
                                <th class="col-time">Start Time</th>
                                <th class="col-time">End Time</th>
                                <th class="col-current">Discharge Current (A)</th>
                                <th class="col-soh">SoH (%)</th>
                                <th class="col-actions text-end">Actions</th>
                            </tr>
                        </thead>

                        <tbody>
                            {#if schedulesLoading}
                                <tr>
                                    <td colspan="6" class="text-center py-3 text-muted">
                                        Loading discharge history…
                                    </td>
                                </tr>
                            {:else if schedulesError}
                                <tr>
                                    <td colspan="6" class="text-center py-3 text-danger">
                                        {schedulesError}
                                    </td>
                                </tr>
                            {:else if schedules.length === 0}
                                <tr>
                                    <td colspan="6" class="text-center py-3 text-muted">
                                        There is no discharge history yet.
                                    </td>
                                </tr>
                            {:else}
                                {#each schedules as s (s.id)}
                                    <tr class={s.status === "running" ? "table-primary" : ""}>
                                        <td class="col-status">
                                            <span
                                                class="badge px-3 py-2 rounded-pill
                                                {s.status === 'running' ? 'bg-success running-badge-blink' :
                                                s.status === 'pending' ? 'bg-secondary' :
                                                s.status === 'finished' ? 'bg-primary' :
                                                s.status === 'stopped' ? 'bg-warning text-dark' :
                                                'bg-danger'}"
                                            >
                                                {s.status.toUpperCase()}
                                            </span>
                                        </td>

                                        <td class="col-time">{formatDate(s.startTime)}</td>
                                        <td class="col-time">{formatDate(s.endTime)}</td>
                                        <td class="col-current">{formatNumber(s.ratedCurrent, 1)}</td>
                                        <td class="col-soh">
                                            {s.soh ? s.soh.toFixed(3) : "-"}
                                        </td>

                                        <td class="col-actions text-end">
                                            {#if s.status === "running"}
                                                <button
                                                    class="btn btn-sm btn-outline-danger"
                                                    onclick={() => handleStop(s.id)}
                                                >
                                                    Stop
                                                </button>
                                            {:else}
                                                <span class="text-muted">—</span>
                                            {/if}
                                        </td>
                                    </tr>
                                {/each}
                            {/if}
                        </tbody>
                    </table>
                </div>
            </div>
        </div>

        <!-- Tabs -->
        <ul class="nav nav-tabs mb-3 bms-tabs">
            <li class="nav-item">
                <button
                    class="nav-link {activeTab === 'vol-rst' ? 'active' : ''}"
                    onclick={() => (activeTab = "vol-rst")}
                >
                    Vol_Rst
                </button>
            </li>
            <li class="nav-item">
                <button
                    class="nav-link {activeTab === 'temp-ir' ? 'active' : ''}"
                    onclick={() => (activeTab = "temp-ir")}
                >
                    T_IR
                </button>
            </li>
            <li class="nav-item">
                <button
                    class="nav-link {activeTab === 'soc-soh' ? 'active' : ''}"
                    onclick={() => (activeTab = "soc-soh")}
                >
                    SoC_SoH
                </button>
            </li>
        </ul>

        <!-- CELL DATA (RESTORED) -->
        <div class="section-card">
    <div class="section-header">
        Cell Data:
        {activeTab === "vol-rst"
            ? "Vol_Rst"
            : activeTab === "temp-ir"
              ? "T_IR"
              : "SoC_SoH"} ({batteryString.cellQty} Cells)
    </div>

    {#if cells.length > 0}
        <div class="section-body">
            <div class="cell-card-grid">

                {#each cellGroups as group}
                    <div class="cell-card">
                        <table class="table cell-mini-table mb-0">
                            <thead>
                                <tr>
                                    <th>ID</th>
                                    {#if activeTab === "vol-rst"}
                                        <th>Voltage (V)</th>
                                        <th>Resistance (μΩ)</th>
                                    {:else if activeTab === "temp-ir"}
                                        <th>Temperature (°C)</th>
                                        <th>IR (U)</th>
                                    {:else}
                                        <th>SoC (%)</th>
                                        <th>SoH (%)</th>
                                    {/if}
                                </tr>
                            </thead>

                            <tbody>
                                {#each group as cell}
                                    {@const alarmReasons = cellAlarmReasons(cell)}
                                    {@const alarmLabel = getPrimaryAlarmLabel(alarmReasons)}
                                    <tr class:cell-alarm-row={alarmReasons.length > 0} title={alarmReasons.join(", ")}>
                                        <td class="cell-id">
                                            {#if alarmReasons.length > 0}
                                                <span
                                                    class="cell-alarm-led"
                                                    aria-label={`Cell alarm ${alarmLabel}`}
                                                ></span>
                                                <span class="cell-alarm-label">{alarmLabel}</span>
                                            {/if}
                                            {cell.ID}
                                        </td>

                                        {#if activeTab === "vol-rst"}
                                            <td>{cell.Vol?.toFixed(3) ?? "-"}</td>
                                            <td>{cell.Rst ?? "-"}</td>
                                        {:else if activeTab === "temp-ir"}
                                            <td>{cell.Temp?.toFixed(1) ?? "-"}</td>
                                            <td>{cell.IR ?? "-"}</td>
                                        {:else}
                                            <td>{cell.SoC?.toFixed(3) ?? "-"}</td>
                                            <td>{cell.SoH?.toFixed(3) ?? "-"}</td>
                                        {/if}
                                    </tr>
                                {/each}
                            </tbody>
                        </table>
                    </div>
                {/each}


            </div>
        </div>
    {:else}
        <div class="text-center py-4">
            <div class="spinner-border text-primary"></div>
        </div>
    {/if}
</div>


        <!-- CHART (RESTORED, ALL 3 CANVASES) -->
        <div class="section-card">
            <div class="section-header">Chart</div>
            <div class="section-body">
                <div class="chart-container">
                    <canvas
                        bind:this={volRstCanvas}
                        class:chart-hidden={activeTab !== 'vol-rst'}
                    ></canvas>

                    <canvas
                        bind:this={tempIrCanvas}
                        class:chart-hidden={activeTab !== 'temp-ir'}
                    ></canvas>

                    <canvas
                        bind:this={socSohCanvas}
                        class:chart-hidden={activeTab !== 'soc-soh'}
                    ></canvas>
                </div>
            </div>
        </div>
    {/if}

    <!-- Schedule Dialog -->
    {#if batteryString}
        <ScheduleDialog
            bind:open={showScheduleDialog}
            stringId={`str${batteryString.stringIndex}`}
            on:created={async () => {
                showScheduleDialog = false;
                await refreshSchedules();
            }}
        />

        <CsvExportDialog
            bind:open={showCsvExportDialog}
            on:export={handleCsvExport}
        />
    {/if}
    </div>
</div>

<style>
        /* Wider content feel + closer to reference spacing */
    /* ===============================
    PAGE SPACING
    ================================ */
    .page-shell {
        padding-left: 28px;
        padding-right: 28px;
    }

    /* ===============================
    BACK LINK
    ================================ */
    .back-link {
        display: inline-flex;
        align-items: center;
        gap: 8px;
        font-size: 14px;
        font-weight: 500;
        color: #1e40af; /* enterprise blue */
        text-decoration: none;
    }

    .back-link i {
        font-size: 18px;
        margin-top: -1px; /* optical alignment */
    }

    .back-link:hover {
        text-decoration: underline;
    }

    /* ===============================
    CARD BLOCKS
    ================================ */
    .section-card {
        background: #ffffff;
        border: 1px solid rgba(0, 0, 0, 0.08);
        border-radius: 10px;
        box-shadow: 0 1px 2px rgba(0,0,0,0.06);
        margin-bottom: 22px;
        overflow: hidden;
    }

    .section-header {
        padding: 16px 20px;
        border-bottom: 1px solid rgba(0,0,0,0.08);
        font-size: 18px;
        font-weight: 600;          /* calmer than 700 */
        color: #111827;
        background: #ffffff;
    }

    .section-body {
        padding: 16px 20px;
    }

    /* ===============================
    GENERAL INFO (TOP CARD)
    ================================ */
    .k-label {
        font-size: 12px;
        font-weight: 500;
        color: #6b7280;
        letter-spacing: 0.04em;
        text-transform: uppercase;
        margin-bottom: 6px;
    }

    .k-value {
        font-size: 16px;           /* readable, not cute */
        font-weight: 600;
        line-height: 1.4;
        letter-spacing: 0.01em;
        color: #1e3a8a;            /* deep enterprise blue */
    }

    /* STRING DATA: make header labels look like General Info labels */
    .k-table-head th {
    font-size: 12px !important;
    font-weight: 600 !important;
    color: #6b7280 !important;
    letter-spacing: 0.04em !important;
    text-transform: uppercase !important;
    background: #ffffff !important;
    border-bottom: 1px solid rgba(0,0,0,0.08) !important;
    text-align: center !important; /* center header labels */
    }

    /* STRING DATA: value row bigger + bolder + centered */
    .k-table-values td {
    font-size: 15px !important;     /* bigger */
    font-weight: 600 !important;    /* bolder */
    color: #111827 !important;      /* strong */
    text-align: center !important;  /* centered */
    border-top: 1px solid rgba(0,0,0,0.06) !important;
    }

    /* Optional: slightly more breathing room for String Data specifically */
    .k-table-head th,
    .k-table-values td {
    padding-top: 14px !important;
    padding-bottom: 14px !important;
    }


    /* ===============================
    TABS
    ================================ */
    .bms-tabs {
    display: flex;
    width: 100%;
    border-bottom: 1px solid rgba(0,0,0,0.12);
    }

    .bms-tabs .nav-item {
    flex: 1; /* each tab = 1/3 */
    text-align: center;
    }

    .bms-tabs .nav-link {
    width: 100%;
    border: none !important;
    padding: 14px 0;
    font-size: 14px;
    font-weight: 600;
    color: #6b7280;
    }

    .bms-tabs .nav-link.active {
    color: #1e40af;
    border-bottom: 2px solid #1e40af !important;
    background: transparent;
    }

    /* ===============================
    TABLE BASE STYLE
    ================================ */
    .bms-table {
        width: 100%;
        border-collapse: separate;
        border-spacing: 0;
    }

    .bms-table-fixed {
        table-layout: fixed;
    }

    /* column padding / indent consistency */
    .bms-table th,
    .bms-table td {
        padding: 12px 16px !important;
        white-space: nowrap;
        vertical-align: middle;
    }

    /* table headers */
    .bms-table thead th {
        font-size: 13px;
        font-weight: 600;
        color: #374151;
        background: #ffffff;
        border-bottom: 1px solid rgba(0,0,0,0.08);
    }

    /* table body text (faded like reference) */
    .bms-table tbody td {
        font-size: 13px;
        font-weight: 400;
        color: #6b7280;
        border-top: 1px solid rgba(0,0,0,0.06);
    }

    /* highlight important values only when needed */
    .bms-table .strong-cell {
        font-weight: 600;
        color: #111827;
    }


    /* Status: compact & centered */
        /* ===============================
    DISCHARGE HISTORY COLUMN LAYOUT
    Responsive + balanced
    =============================== */
    .bms-table.discharge-table thead th,
    .bms-table.discharge-table tbody td {
    text-align: center !important;
    }

    /* Keep the badge centered nicely */
    .bms-table.discharge-table .badge {
    display: inline-flex;
    justify-content: center;
    min-width: 92px; /* optional: keeps badge same size */
    }

    /* Actions should stay right aligned (like reference) */
    .bms-table.discharge-table thead th.col-actions,
    .bms-table.discharge-table tbody td.col-actions {
    text-align: right !important;
    }
/* Status: compact */
    .col-status {
    min-width: 120px;
    }

    /* Start / End time: wide */
    .col-time {
    min-width: 260px;
    padding-left: 24px !important;
    padding-right: 24px !important;
    }

    /* Current: medium */
    .col-current {
    min-width: 180px;
    }

    /* SoH: narrow */
    .col-soh {
    min-width: 100px;
    text-align: left;
    }

    /* Actions: very narrow */
    .col-actions {
    min-width: 72px;
    padding-left: 12px !important;
    padding-right: 12px !important;
    }


    /* ===============================
    STRING DATA COLUMN WIDTH
    ================================ */

    /* ===============================
    SCROLL AREAS
    ================================ */
    .cell-scroll {
        max-height: 320px;
        overflow-y: auto;
    }

    .schedule-table-scroll {
        max-height: 320px;
        overflow-y: auto;
    }

    .schedule-table-scroll thead th {
        position: sticky;
        top: 0;
        z-index: 1;
        background: #ffffff;
    }

    /* ===============================
    CELL DATA TABLE (3-COLUMN GROUPS)
    =============================== */

    /* ===============================
CELL DATA – 3 CARD LAYOUT
=============================== */

/* Grid of 3 cards + scrollable */
.cell-card-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 16px;

    max-height: 420px;     /* adjust if you want taller/shorter */
    overflow-y: auto;
    overflow-x: hidden;
    padding-right: 6px;    /* keep scrollbar from covering content */
}

/* Responsive fallback */
@media (max-width: 992px) {
    .cell-card-grid {
        grid-template-columns: repeat(2, minmax(0, 1fr));
    }
}

@media (max-width: 576px) {
    .cell-card-grid {
        grid-template-columns: 1fr;
    }
}

/* Each card */
.cell-card {
    background: #ffffff;
    border: 1px solid rgba(0,0,0,0.08);
    border-radius: 8px;
    box-shadow: 0 1px 2px rgba(0,0,0,0.05);
    overflow: hidden;
}

/* Mini table */
.cell-mini-table {
    width: 100%;
    border-collapse: collapse;
    table-layout: fixed; /* important: equal column widths */
}

/* Force symmetric 3 columns + center align */
.cell-mini-table th,
.cell-mini-table td {
    width: 33.333%;
    text-align: center !important;
    vertical-align: middle;
}

/* Header */
.cell-mini-table thead th {
    background: #f3f4f6;
    font-size: 13px;
    font-weight: 600;
    color: #374151;
    padding: 10px 8px;
    border-bottom: 1px solid rgba(0,0,0,0.12);
}

/* Body */
.cell-mini-table tbody td {
    font-size: 14px;
    font-weight: 500;
    padding: 8px 8px;
    border-top: 1px solid rgba(0,0,0,0.06);
}

.cell-alarm-led {
    display: inline-flex;
    width: 10px;
    height: 10px;
    margin-right: 8px;
    border-radius: 50%;
    background: #dc2626;
    box-shadow: 0 0 0 3px rgba(220, 38, 38, 0.18);
    vertical-align: middle;
    animation: alarmBlink 1s ease-in-out infinite;
}

.cell-alarm-label {
    display: inline-flex;
    min-width: 24px;
    margin-right: 8px;
    color: #b91c1c;
    font-size: 12px;
    font-weight: 700;
    vertical-align: middle;
}

.cell-mini-table tbody tr.cell-alarm-row td {
    color: #b91c1c;
    background: rgba(254, 226, 226, 0.65);
    animation: alarmRowBlink 1s ease-in-out infinite;
}

/* ID emphasis */
.cell-id {
    font-weight: 600;
    color: #1e40af;
}

@keyframes alarmBlink {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.18; }
}

@keyframes alarmRowBlink {
    0%, 100% { background: rgba(254, 226, 226, 0.8); }
    50% { background: rgba(254, 226, 226, 0.25); }
}


/* ===============================
RESPONSIVE
=============================== */
@media (max-width: 1200px) {
    .cell-card-grid {
        grid-template-columns: repeat(2, 1fr);
    }
}

@media (max-width: 768px) {
    .cell-card-grid {
        grid-template-columns: 1fr;
    }
}


    


    /* ===============================
    CHART
    ================================ */
    .chart-container {
         width: 100%;
        height: 420px;
        position: relative;
    }
    .chart-hidden {
        position: absolute;
        opacity: 0;
        pointer-events: none;
    }



    /* ===============================
    RUNNING BADGE BLINK
    ================================ */
    @keyframes fadeBlink {
        0%, 100% { opacity: 1; }
        50% { opacity: 0.35; }
    }

    .running-badge-blink {
        animation: fadeBlink 2s ease-in-out infinite;
    }

    @media (prefers-reduced-motion: reduce) {
        .running-badge-blink,
        .cell-alarm-led,
        .cell-mini-table tbody tr.cell-alarm-row td {
            animation: none;
        }
    }

    @media (max-width: 1200px) {
    .col-time { min-width: 220px; padding-left: 24px !important; padding-right: 24px !important; }
    .col-number { min-width: 150px; }
    }

    @media (max-width: 992px) {
    .col-time { min-width: 180px; padding-left: 18px !important; padding-right: 18px !important; }
    .col-status { min-width: 110px; }
    .col-actions { min-width: 110px; padding-left: 18px !important; }
    }

    @media (max-width: 768px) {
    .col-time { min-width: 160px; padding-left: 14px !important; padding-right: 14px !important; }
    .col-number { min-width: 120px; }
    }

    /* ===============================
PRINT FIX — STRING DETAIL PAGE
=============================== */
   @media print {

    /* ===============================
       VISIBILITY CONTROL (SAFE)
    =============================== */
    :global(body *) {
        visibility: hidden !important;
    }

    #print-area,
    #print-area * {
        visibility: visible !important;
    }

    #print-area {
        position: absolute;
        left: 0;
        top: 0;
        width: 100%;
    }

    /* ===============================
       KILL CHARTS COMPLETELY
    =============================== */
    .chart-container,
    canvas,
    .section-card:has(canvas) {
        display: none !important;
    }

    /* ===============================
       REMOVE SCROLL / GRID
    =============================== */
    .schedule-table-scroll,
    .cell-card-grid {
        max-height: none !important;
        overflow: visible !important;
        display: block !important;
    }

    /* ===============================
       TABLE SANITY RESET
    =============================== */
    table {
        width: 100% !important;
        border-collapse: collapse !important;
        table-layout: auto !important; /* 🔑 */
        font-size: 11px !important;
    }

    th, td {
        padding: 6px 6px !important;
        white-space: normal !important; /* 🔑 */
        word-break: break-word !important;
        text-align: center !important;
        line-height: 1.3 !important;
    }

    /* ===============================
       STRING DATA TABLE (WIDE)
    =============================== */
    .section-card {
        page-break-inside: avoid;
        box-shadow: none !important;
    }

    /* Prevent Firefox header duplication chaos */
    thead {
        display: table-header-group;
    }

    tbody {
        display: table-row-group;
    }

    tr {
        page-break-inside: avoid;
    }

    /* ===============================
       CLEAN PAGE
    =============================== */
    body {
        background: #fff !important;
    }
}

</style>
