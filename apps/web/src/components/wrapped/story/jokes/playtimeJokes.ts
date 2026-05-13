/**
 * Snarky playtime joke ladder, crab-flavoured. Ported in spirit from the
 * reference repo; tone is gentle ribbing, not mean.
 */
export function getPlaytimeJoke(hours: number): string {
  if (hours <= 0) return "Did you actually log in this season?";
  if (hours < 1) return "Less than an hour. Bold strategy.";
  if (hours < 5) return "Just a casual stroll along the beach.";
  if (hours < 10) return "Dipping a claw in. Respectable.";
  if (hours < 25) return "Now we're talking — a proper weekend campaign.";
  if (hours < 50) return "Reliable, focused. The crab way.";
  if (hours < 100) return "Triple digits incoming. The sea is patient.";
  if (hours < 150) return "You've out-grinded most of the server.";
  if (hours < 200) return "200 hours? You ARE the server, friend.";
  if (hours < 300) return "Your real-life shadow looks a little blocky.";
  if (hours < 500) return "Mojang owes you commission at this point.";
  if (hours < 750) return "Sleep is a server-side mechanic. So you tell yourself.";
  if (hours < 1000) return "One thousand hours feels like the ocean. You are the tide.";
  if (hours < 1500) return "Touch grass? You ARE the grass block. And the dirt. And the mycelium.";
  if (hours < 2000) return "We've notified your loved ones. They send their regards.";
  return "If hours were lives, you'd be a god. Carry on, eternal one.";
}
