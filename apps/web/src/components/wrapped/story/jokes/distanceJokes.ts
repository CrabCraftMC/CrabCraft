export function getDistanceJoke(km: number): string {
  if (km <= 0) return "You stood very, very still.";
  if (km < 1) return "Half a kilometre. The couch must be comfortable.";
  if (km < 10) return "A leisurely beach scuttle.";
  if (km < 50) return "You wandered. You discovered. You complained about diorite.";
  if (km < 100) return "A proper expedition. Map up to date?";
  if (km < 250) return "You've seen biomes most have only heard rumours of.";
  if (km < 500) return "Five hundred kilometres. Cartographer's union called.";
  if (km < 1000) return "Approaching escape velocity from the spawn region.";
  if (km < 5000) return "You could legally claim 'world traveller' on LinkedIn.";
  if (km < 10000) return "The map renderer needs a coffee break.";
  if (km < 40075) return "Almost a full Earth-circumference. Almost.";
  if (km < 100000) return "Around the world is a Tuesday for you.";
  return "We need to talk about your fuel budget.";
}
