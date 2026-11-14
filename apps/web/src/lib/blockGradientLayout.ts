export const BLOCK_GRADIENT_STORAGE_KEY = "crabcraft-block-gradient";
export const BLOCK_GRADIENT_LAYOUT_KEY = "crabcraft-block-gradient-layout-v2";

export function getBlockGradientLoadingHeight(
  value: unknown,
  width: number,
  viewportHeight: number,
  route: string,
  settings: string | null,
): number | null {
  if (!value || typeof value !== "object") return null;
  const cached = value as Record<string, unknown>;
  return cached.width === width && cached.viewportHeight === viewportHeight &&
    cached.route === route && cached.settings === settings &&
    typeof cached.height === "number" && Number.isFinite(cached.height) && cached.height > 0
    ? cached.height : null;
}

// Run before the guide paints so restored scroll positions see the previous tool height.
export const blockGradientLayoutScript = `(()=>{try{document.documentElement.style.removeProperty('--block-gradient-loading-height');var height=(${getBlockGradientLoadingHeight.toString()})(JSON.parse(sessionStorage.getItem('${BLOCK_GRADIENT_LAYOUT_KEY}')||'null'),innerWidth,innerHeight,location.pathname+location.search,localStorage.getItem('${BLOCK_GRADIENT_STORAGE_KEY}'));if(height!==null)document.documentElement.style.setProperty('--block-gradient-loading-height',height+'px')}catch{}})()`;
