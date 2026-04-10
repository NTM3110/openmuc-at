<script lang="ts">
    import { onMount, onDestroy } from "svelte";
    import type { Subscription } from "rxjs";

    import { getDashboardStatus } from "$lib/services/openmuc";
    import { stringsState } from "$lib/services/battery-string";
    import type { DashboardItem } from "$lib/interfaces/dashboard.interface";

    import { getSiteName, setSiteName } from "$lib/services/site";
    import { showToast } from "$lib/services/toast.store";
    import { kbd } from "$lib/actions/kbd";

    /* ================= Site name ================= */
    let siteName = "My Monitoring Site";
    let siteNameDraft = "";
    let editingSite = false;
    let savingSiteName = false;

    function startEditSiteName() {
        siteNameDraft = siteName;
        editingSite = true;
    }

    async function saveSiteName() {
        const newName = siteNameDraft.trim();
        if (!newName) {
            showToast("Site name cannot be empty.", "error");
            return;
        }

        savingSiteName = true;

        try {
            await setSiteName(newName);

            try {
                const loadedName = await getSiteName();
                siteName = loadedName;
                siteNameDraft = loadedName;
                showToast("Site name updated.", "success");
            } catch {
                siteName = newName;
                siteNameDraft = newName;
                showToast(
                    "Site name updated (but could not verify).",
                    "info"
                );
            }

            editingSite = false;
        } catch (err) {
            console.error("[Site] Save failed:", err);
            showToast("Error saving site name.", "error");
        } finally {
            savingSiteName = false;
        }
    }

    function cancelEditSiteName() {
        siteNameDraft = siteName;
        editingSite = false;
    }

    /* ================= Thermal camera ================= */
    let cameraEnabled = false;
    function toggleCamera() {
        cameraEnabled = !cameraEnabled;
    }

    /* ================= Dashboard data ================= */
    let items: DashboardItem[] = [];
    let subscription: Subscription | null = null;

    onMount(async () => {
        siteName = await getSiteName();

        subscription = getDashboardStatus().subscribe((data) => {
            items = data.map((item) => {
                const stringIndex = parseInt(
                    item.id.replace("str", ""),
                    10
                );
                const stringConfig = $stringsState.find(
                    (s) => s.stringIndex === stringIndex
                );

                return {
                    ...item,
                    stringName:
                        stringConfig?.stringName || item.stringName
                };
            });
        });
    });

    onDestroy(() => {
        subscription?.unsubscribe();
    });

    /* ================= Helpers ================= */
    function statusClass(value: "On" | "Off") {
        return value === "On" ? "status on" : "status off";
    }

    function formatTime(t: any) {
        try {
            const d = t instanceof Date ? t : new Date(t);
            return isNaN(d.getTime()) ? "N/A" : d.toLocaleString();
        } catch {
            return "N/A";
        }
    }

    function goToStringDetail(id: string) {
        window.location.href = `/setting/string/${id}`;
    }
</script>

<div class="page-container">
    <!-- ================= Header ================= -->
    <div class="site-header">
        <div class="site-left">
            {#if !editingSite}
                <span class="home-icon">🏠</span>
                <h1>{siteName}</h1>
                <button class="icon-btn" on:click={startEditSiteName}>✏️</button>
            {:else}
                <input
                    bind:value={siteNameDraft}
                    disabled={savingSiteName}
                    use:kbd
                />
                <button
                    class="icon-btn ok"
                    disabled={savingSiteName}
                    on:click={saveSiteName}
                >
                    ✔
                </button>
                <button
                    class="icon-btn"
                    disabled={savingSiteName}
                    on:click={cancelEditSiteName}
                >
                    ✖
                </button>
            {/if}
        </div>

        <!-- ================= Summary Pills ================= -->
        <div class="string-summary">
            {#each items as it (it.id)}
                {@const isLowSoh = (it.soH ?? 100) < 80}

                <div class="summary-pill">
                    <div class="pill-left">
                        <span>🔋</span>
                        <strong>{it.stringName}</strong>
                        <span>{it.totalCells ?? 110} cells</span>
                    </div>

                    <span class={"pill-pin " + (isLowSoh ? "low" : "ok")}>
                        {isLowSoh ? "LOW" : "OK"}
                    </span>
                </div>
            {/each}
        </div>
    </div>

    <!-- ================= Thermal Camera ================= -->
    <div class="thermal-section">
        <div class="thermal-header">
            <h3>🌡 Thermal Camera</h3>
            <button class="icon-btn" on:click={toggleCamera}>
                📷 {cameraEnabled ? "On" : "Off"}
            </button>
        </div>

        <div class="no-thermal">ℹ No thermal data available</div>
    </div>

    <!-- ================= Table ================= -->
    {#if items.length === 0}
        <div class="empty-card">
            <strong>No battery strings configured.</strong>
            <div class="muted">
                Please configure strings in Settings.
            </div>
        </div>
    {:else}
        <div class="table-card">
            <table>
                <thead>
                    <tr>
                        <th>String Name</th>
                        <th>Cell Vol</th>
                        <th>Cell Rst</th>
                        <th>String Vol</th>
                        <th>Current</th>
                        <th>Ambient</th>
                        <th>SoC (%)</th>
                        <th>SoH (%)</th>
                        <th>Last Update</th>
                    </tr>
                </thead>

                <tbody>
                    {#each items as item (item.id)}
                        <tr
                            class="table-row"
                            on:click={() => goToStringDetail(item.id)}
                        >
                            <td class="link">{item.stringName}</td>
                            <td><span class={statusClass(item.cellVol)}></span>{item.cellVol}</td>
                            <td><span class={statusClass(item.cellRst)}></span>{item.cellRst}</td>
                            <td><span class={statusClass(item.stringVol)}></span>{item.stringVol}</td>
                            <td><span class={statusClass(item.current)}></span>{item.current}</td>
                            <td><span class={statusClass(item.ambient)}></span>{item.ambient}</td>
                            <td>{item.soC != null ? item.soC.toFixed(3) : "N/A"}</td>
                            <td>{item.soH != null ? item.soH.toFixed(3) : "N/A"}</td>
                            <td>{formatTime(item.updateTime)}</td>
                        </tr>
                    {/each}
                </tbody>
            </table>
        </div>
    {/if}
</div>

<style>
:global(body) {
    font-family: Inter, system-ui, -apple-system, BlinkMacSystemFont,
        "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    background: #f5f7fb;
    color: #1f2937;
}

.page-container {
    padding: 24px;
}

/* ---------- Header ---------- */
.site-header {
    background: #ffffff;
    border-radius: 12px;
    padding: 16px 20px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 16px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}

.site-left {
    display: flex;
    align-items: center;
    gap: 10px;
}

.site-left h1 {
    font-size: 20px;
    font-weight: 600;
    margin: 0;
}

.home-icon {
    font-size: 20px;
    color: #2563eb;
}

.icon-btn {
    display: inline-flex;
    align-items: center;
    border: none;
    background: none;
    cursor: pointer;
    font-size: 14px;
}

.icon-btn.ok {
    color: #16a34a;
}

/* ---------- Summary Pills ---------- */
.string-summary {
    display: flex;
    gap: 12px;
    flex-wrap: wrap;
}

.summary-pill {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 8px 12px;
    border-radius: 999px;
    background: #f9fafb;
    border: 1px solid #e5e7eb;
    font-size: 13px;
    min-width: 260px;
}

.pill-left {
    display: inline-flex;
    align-items: center;
    gap: 8px;
}

.pill-pin {
    padding: 4px 10px;
    border-radius: 999px;
    font-weight: 700;
    font-size: 12px;
}

.pill-pin.ok {
    background: #ecfdf5;
    color: #16a34a;
    border: 1px solid rgba(22,163,74,0.3);
}

.pill-pin.low {
    background: #fef2f2;
    color: #dc2626;
    border: 1px solid rgba(220,38,38,0.3);
}

/* ---------- Thermal ---------- */
.thermal-section {
    background: #ffffff;
    border-radius: 12px;
    padding: 16px 20px;
    margin-bottom: 16px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}

.thermal-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
}

.thermal-header h3 {
    margin: 0;
    font-size: 16px;
    font-weight: 600;
    color: #2563eb;
}

.no-thermal {
    margin-top: 12px;
    padding: 10px 12px;
    background: #f9fafb;
    border-radius: 8px;
    color: #6b7280;
}

/* ---------- Table ---------- */
.table-card {
    background: #ffffff;
    border-radius: 12px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08);
    overflow: hidden;
}

table {
    width: 100%;
    border-collapse: collapse;
}

thead th {
    background: #ffffff;
    padding: 16px;
    font-size: 13px;
    font-weight: 600;
    border-bottom: 1px solid #e5e7eb;
    text-align: left;
}

tbody td {
    padding: 16px;
    font-size: 14px;
    font-weight: 500; /* 🔹 bolder table text */
    border-bottom: 1px solid #e5e7eb;
    vertical-align: middle;
}

.table-row {
    cursor: pointer;
}

.table-row:hover {
    background: #f9fafb;
}

td.link {
    color: #2563eb;
    font-weight: 600;
}

.status {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    display: inline-block;
    margin-right: 8px;
}

.status.on {
    background: #22c55e;
}

.status.off {
    background: #ef4444;
}

/* ---------- Empty ---------- */
.empty-card {
    background: #ffffff;
    border-radius: 12px;
    padding: 16px 20px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.08);
}

.muted {
    color: #6b7280;
}
</style>
