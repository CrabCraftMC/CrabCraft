import { describe, expect, test } from "bun:test";
import { renderToStaticMarkup } from "react-dom/server";
import { hiddenPlayersUrl } from "../src/lib/leaderboardVisibility";
import { LeaderboardPlayerAvatar, LeaderboardPlayerLink, leaderboardPlayerName } from "../src/components/LeaderboardPlayer";

describe("hidden leaderboard entries", () => {
  test("hidden rows cannot expose identities, skin URLs or profile links", () => {
    const player = { hidden: true, uuid: crypto.randomUUID(), username: `Fixture_${crypto.randomUUID()}`, nickname: `Alias_${crypto.randomUUID()}` };
    const html = renderToStaticMarkup(
      <LeaderboardPlayerLink player={player} className="cursor-pointer">
        <LeaderboardPlayerAvatar player={player} size={32} />
        {leaderboardPlayerName(player)}
      </LeaderboardPlayerLink>,
    );
    expect(html).toContain("Hidden Player");
    for (const identity of [player.uuid, player.username, player.nickname]) expect(html).not.toContain(identity);
    expect(html).not.toContain("href=");
    expect(html).not.toContain("<img");
    expect(html).not.toContain("cursor-pointer");
  });

  test("visible rows retain their profile links and heads", () => {
    const player = { hidden: false, uuid: crypto.randomUUID(), username: `Fixture_${crypto.randomUUID()}`, nickname: null };
    const html = renderToStaticMarkup(
      <LeaderboardPlayerLink player={player} className="">
        <LeaderboardPlayerAvatar player={player} size={32} />
        {leaderboardPlayerName(player)}
      </LeaderboardPlayerLink>,
    );
    expect(html).toContain(`/stats/${player.uuid}`);
    expect(html).toContain(`https://mc-heads.net/avatar/${player.uuid}/100.png`);
    expect(html).toContain(player.username);
  });

  test("visibility changes preserve filters, reset pagination and remove the flag when off", () => {
    expect(hiddenPlayersUrl("/leaderboard/advancements", "?category=adventure&page=3", true))
      .toBe("/leaderboard/advancements?category=adventure&show_hidden=true");
    expect(hiddenPlayersUrl("/leaderboard/advancements", "?category=adventure&page=2&show_hidden=true", false))
      .toBe("/leaderboard/advancements?category=adventure");
    expect(hiddenPlayersUrl("/leaderboard", "?show_hidden=true", false)).toBe("/leaderboard");
  });
});
