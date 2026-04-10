<script lang="ts">
  import { createEventDispatcher } from "svelte";

  let { open = $bindable(false) } = $props();

  const dispatch = createEventDispatcher<{
    export: { start: string; end: string };
  }>();

  // Initialize with today's date
  const today = new Date().toISOString().split("T")[0];
  let startDate = $state(today);
  let endDate = $state(today);
  let error: string | null = $state(null);

  function close() {
    open = false;
    error = null;
  }

  function formatToDdMMyy(dateStr: string) {
    const [year, month, day] = dateStr.split("-");
    return `${day}${month}${year.slice(-2)}`;
  }

  function handleExport() {
    error = null;

    if (!startDate || !endDate) {
      error = "Both start and end dates are required";
      return;
    }

    if (new Date(endDate) < new Date(startDate)) {
      error = "End date cannot be before start date";
      return;
    }

    dispatch("export", {
      start: formatToDdMMyy(startDate),
      end: formatToDdMMyy(endDate),
    });

    close();
  }
</script>

{#if open}
  <div class="modal-backdrop">
    <div class="export-modal" onclick={(e) => e.stopPropagation()}>
      <div class="modal-header">
        <h5>Export CSV Data</h5>
        <button class="close-btn" onclick={close}>×</button>
      </div>

      <div class="modal-body">
        <p class="text-muted small mb-3">
          Select the date range for the data you want to export. Multiple days
          will be bundled into a ZIP file.
        </p>

        <div class="form-group mb-3">
          <label class="form-label" for="start-date">Start Date</label>
          <input
            id="start-date"
            type="date"
            bind:value={startDate}
            class="form-control"
          />
        </div>

        <div class="form-group mb-3">
          <label class="form-label" for="end-date">End Date</label>
          <input
            id="end-date"
            type="date"
            bind:value={endDate}
            class="form-control"
          />
        </div>

        {#if error}
          <div class="error-text">{error}</div>
        {/if}
      </div>

      <div class="modal-footer">
        <button class="btn btn-outline-secondary btn-sm" onclick={close}>
          Cancel
        </button>
        <button class="btn btn-primary btn-sm" onclick={handleExport}>
          <i class="bi bi-download me-1"></i>
          Download
        </button>
      </div>
    </div>
  </div>
{/if}

<style>
  .modal-backdrop {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.4);
    display: flex;
    align-items: center;
    justify-content: center;
    z-index: 1050;
  }

  .export-modal {
    background: #fff;
    border-radius: 8px;
    width: 400px;
    max-width: calc(100% - 2rem);
    max-height: 85vh;
    display: flex;
    flex-direction: column;
    box-shadow: 0 10px 30px rgba(0, 0, 0, 0.2);
    overflow: hidden;
  }

  .modal-header {
    padding: 1rem 1.25rem;
    border-bottom: 1px solid #eee;
    display: flex;
    justify-content: space-between;
    align-items: center;
    flex-shrink: 0;
  }

  .modal-header h5 {
    margin: 0;
    font-size: 1.1rem;
    font-weight: 600;
  }

  .close-btn {
    background: none;
    border: none;
    font-size: 1.5rem;
    line-height: 1;
    color: #999;
    cursor: pointer;
    padding: 0;
  }

  .modal-body {
    padding: 1.25rem;
    overflow-y: auto;
    flex-grow: 1;
  }

  .modal-footer {
    padding: 1rem 1.25rem;
    background: #f8f9fa;
    display: flex;
    justify-content: flex-end;
    gap: 0.75rem;
    border-top: 1px solid #eee;
    flex-shrink: 0;
  }

  .form-label {
    font-weight: 500;
    font-size: 0.9rem;
    color: #444;
    margin-bottom: 0.4rem;
  }

  .error-text {
    color: #dc3545;
    font-size: 0.85rem;
    margin-top: 0.5rem;
  }
</style>
