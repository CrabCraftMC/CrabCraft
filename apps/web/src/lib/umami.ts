"use client";

type UmamiEventData = Record<string, string | number | boolean>;
const trackedOnce = new Set<string>();

declare global {
  interface Window {
    umami?: {
      track: (eventName: string, data?: UmamiEventData) => void;
    };
  }
}

export function trackUmamiEvent(
  eventName: string,
  data?: UmamiEventData,
): boolean {
  try {
    if (!window.umami) return false;
    window.umami.track(eventName, data);
    return true;
  } catch {
    // Analytics must never interfere with a website action.
    return false;
  }
}

export function trackUmamiEventOnce(
  key: string,
  eventName: string,
  data?: UmamiEventData,
): void {
  if (trackedOnce.has(key)) return;
  if (trackUmamiEvent(eventName, data)) trackedOnce.add(key);
}
