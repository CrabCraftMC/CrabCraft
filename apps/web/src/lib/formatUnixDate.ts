const shortDateFormatter = new Intl.DateTimeFormat("en-GB", {
  day: "numeric",
  month: "short",
  year: "numeric",
});

export function formatUnixDate(timestamp: number): string {
  return shortDateFormatter.format(new Date(timestamp * 1000));
}
