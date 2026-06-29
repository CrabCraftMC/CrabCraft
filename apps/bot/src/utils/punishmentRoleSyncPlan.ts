export interface LinkedPunishmentAccount {
  discordId: string;
  minecraftUuid: string;
}

export interface PunishmentRoleChangePlan {
  add: string[];
  remove: string[];
}

export function normalizeMinecraftUuidKey(uuid: string): string {
  return uuid.replaceAll("-", "").toLowerCase();
}

export function planPunishmentRoleChanges(
  accounts: LinkedPunishmentAccount[],
  punishedUuids: Iterable<string> | null,
  currentRoleHolders: Iterable<string>,
): PunishmentRoleChangePlan {
  if (punishedUuids === null) {
    return { add: [], remove: [] };
  }

  const punishedUuidKeys = new Set(
    Array.from(punishedUuids, normalizeMinecraftUuidKey),
  );
  const shouldHaveRole = new Set<string>();
  for (const account of accounts) {
    if (punishedUuidKeys.has(normalizeMinecraftUuidKey(account.minecraftUuid))) {
      shouldHaveRole.add(account.discordId);
    }
  }

  const current = new Set(currentRoleHolders);
  return {
    add: Array.from(shouldHaveRole).filter((discordId) => !current.has(discordId)),
    remove: Array.from(current).filter((discordId) => !shouldHaveRole.has(discordId)),
  };
}
