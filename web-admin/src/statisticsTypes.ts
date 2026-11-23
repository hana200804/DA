import { dateRangeUtc } from './auditTypes.ts';
import type { MapCluster } from './mapTypes';

export interface StatisticsFilters {
  fromDate?: string;
  toDate?: string;
}
export interface DailySubmissions {
  date: string;
  count: number;
}
export interface Statistics {
  submissionsByDay: DailySubmissions[];
  decisions: { approved: number; rejected: number };
  processingTime: { averageMs: number | null; samples: number };
  signsByCategory: { categoryCode: string; name: string; count: number }[];
  densityCells: MapCluster[];
}

export const statisticsRange = dateRangeUtc;

export function decisionRates({ approved, rejected }: Statistics['decisions']) {
  const total = approved + rejected;
  return total
    ? { approved: approved / total, rejected: rejected / total }
    : null;
}

export function dailyChartRows(
  rows: DailySubmissions[],
  filters: StatisticsFilters,
) {
  const sorted = [...rows].sort((a, b) => a.date.localeCompare(b.date));
  const start = filters.fromDate || sorted.at(0)?.date;
  const end = filters.toDate || sorted.at(-1)?.date;
  const days =
    (Date.parse(end ?? '') - Date.parse(start ?? '')) / 86_400_000 + 1;
  if (!Number.isInteger(days) || days < 1 || days > 366) return sorted;
  const counts = new Map(rows.map(({ date, count }) => [date, count]));
  return Array.from({ length: days }, (_, index) => {
    const date = new Date(Date.parse(start!) + index * 86_400_000)
      .toISOString()
      .slice(0, 10);
    return { date, count: counts.get(date) ?? 0 };
  });
}
