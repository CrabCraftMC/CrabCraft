export interface BackfillArguments {
  dryRun: boolean;
  help: boolean;
  seasonIds: Set<string>;
}

export function parseBackfillArguments(argv: readonly string[]): BackfillArguments {
  const result: BackfillArguments = {
    dryRun: false,
    help: false,
    seasonIds: new Set<string>(),
  };

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === "--") continue;
    if (argument === "--dry-run") {
      result.dryRun = true;
      continue;
    }
    if (argument === "--help" || argument === "-h") {
      result.help = true;
      continue;
    }

    let seasonId: string | undefined;
    if (argument === "--season") {
      seasonId = argv[index + 1];
      index += 1;
    } else if (argument.startsWith("--season=")) {
      seasonId = argument.slice("--season=".length);
    } else {
      throw new Error(`Unknown argument: ${argument}`);
    }

    if (!seasonId || !/^[1-7]$/.test(seasonId)) {
      throw new Error("--season must be a numeric value from 1 to 7.");
    }
    result.seasonIds.add(seasonId);
  }

  return result;
}
