export type Units = Record<string, string> | null;

export function formatValue(
  value: number,
  key: string,
  units: Units
): string {
  const unit = units?.[key];

  if (unit === "cm") {
    const meters = value / 100;
    if (meters >= 1000) {
      return `${(meters / 1000).toFixed(1)} km`;
    }
    return `${Math.round(meters)} m`;
  }

  if (unit === "ticks") {
    const totalSeconds = Math.floor(value / 20);
    const days = Math.floor(totalSeconds / 86400);
    const hours = Math.floor((totalSeconds % 86400) / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    if (days > 0) return `${days}d ${hours}h`;
    if (hours > 0) return `${hours}h ${minutes}min`;
    return `${minutes}min`;
  }

  if (unit === "tenths_of_heart") {
    const hearts = value / 10;
    return `${hearts.toLocaleString(undefined, { maximumFractionDigits: 1 })} ❤`;
  }

  return value.toLocaleString();
}
