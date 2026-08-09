export function playerDisplayName(
  nickname: string | null | undefined,
  username: string | null | undefined,
  fallback = "Unknown",
): string {
  return nickname?.trim() || username?.trim() || fallback;
}
