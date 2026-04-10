// src/lib/services/schedule.ts
import { writable } from "svelte/store";

/* ---------- types ---------- */
export type ScheduleStatus =
  | "pending"
  | "running"
  | "finished"
  | "stopped"
  | "failed";

export interface Schedule {
  id: string;
  stringId: string;
  startTime: number;
  endTime: number | null;
  ratedCurrent: number;
  realTimeCurrent: number | null;
  soh: number | null;
  status: ScheduleStatus;
  createdAt: number;
  updatedAt: number;
}

interface ScheduleDTO {
  id: number;
  strId: string;
  startTime: string;
  endTime: string | null;
  current: number | null;
  soh: number | null;
  state?: string | null;
  dischargeState?: number | null;
  DischargeState?: number | null;
}

interface ScheduleApiResponse {
  success: boolean;
  data: ScheduleDTO[];
}

/* ---------- config ---------- */
const BASE_URL = "/rest/soh-schedule";

/* ---------- store ---------- */
const schedulesStore = writable<Map<string, Schedule[]>>(new Map());

/* ---------- helpers ---------- */
function parseBackendDate(dateStr: string | null): number | null {
  if (!dateStr) return null;

  if (dateStr.includes("Z") || dateStr.includes("+")) {
    return new Date(dateStr).getTime();
  }

  const [d, t] = dateStr.split("T");
  if (!d || !t) return null;

  const [y, m, day] = d.split("-").map(Number);
  const [hh, mm, ss = "0"] = t.split(":");
  const date = new Date(y, m - 1, day, Number(hh), Number(mm), Number(ss));
  return isNaN(date.getTime()) ? null : date.getTime();
}

function mapBackendStatus(v: string | number | null | undefined): ScheduleStatus {
  if (v == null) return "pending";

  if (typeof v === "number") {
    return (
      {
        1: "pending",
        2: "running",
        3: "stopped",
        4: "finished",
        5: "failed",
      } as Record<number, ScheduleStatus>
    )[v] ?? "pending";
  }

  switch (v.toUpperCase()) {
    case "RUNNING":
      return "running";
    case "STOPPED":
      return "stopped";
    case "FINISHED":
    case "SUCCESS":
      return "finished";
    case "FAILED":
      return "failed";
    default:
      return "pending";
  }
}

function dtoToSchedule(dto: ScheduleDTO): Schedule {
  const soh =
    dto.soh == null ? null : dto.soh <= 1 ? dto.soh * 100 : dto.soh;

  const backendState =
    dto.state ?? dto.dischargeState ?? dto.DischargeState;

  return {
    id: String(dto.id),
    stringId: dto.strId,
    startTime: parseBackendDate(dto.startTime) ?? Date.now(),
    endTime: parseBackendDate(dto.endTime),
    ratedCurrent: dto.current ?? 0,
    realTimeCurrent: dto.current,
    soh,
    status: mapBackendStatus(backendState),
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };
}

/* ---------- API ---------- */
export async function loadSchedules(stringId: string): Promise<Schedule[]> {
  const res = await fetch(`${BASE_URL}/get-list`);
  const json = (await res.json()) as ScheduleApiResponse;

  if (!json.success) return [];

  const data = json.data || [];
  const list = data
    .filter((d) => d.strId === stringId)
    .map((dto) => dtoToSchedule(dto))
    .sort((a, b) => b.startTime - a.startTime);

  schedulesStore.update((map) => {
    map.set(stringId, list);
    return map;
  });

  return list;
}

export function getSchedulesStore(stringId: string) {
  return {
    subscribe: schedulesStore.subscribe,
    refresh: () => loadSchedules(stringId),
  };
}

export async function createSchedule(
  stringId: string,
  startTime: Date,
  ratedCurrent?: number,
) {
  const startTimeISO = startTime.toISOString();

  const params = new URLSearchParams({
    strId: stringId,
    startTime: startTimeISO,
  });

  if (Number.isFinite(ratedCurrent)) {
    params.set("current", String(ratedCurrent));
  }

  const res = await fetch(
    `${BASE_URL}/create?${params.toString()}`,
    { method: "POST" },
  );

  if (!res.ok) {
    throw new Error("Failed to create schedule");
  }

  // Angular behavior: reload schedules after creation
  await loadSchedules(stringId);
}


export async function stopSchedule(
  stringId: string,
  scheduleId: string,
): Promise<void> {
  await fetch(`${BASE_URL}/stop?id=${encodeURIComponent(scheduleId)}`, {
    method: "POST",
  });

  await loadSchedules(stringId);
}
