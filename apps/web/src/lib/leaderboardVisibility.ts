export function hiddenPlayersUrl(pathname: string, search: string, showHidden: boolean): string {
  const params = new URLSearchParams(search);
  if (showHidden) params.set("show_hidden", "true");
  else params.delete("show_hidden");
  params.delete("page");
  const query = params.toString();
  return query ? `${pathname}?${query}` : pathname;
}
