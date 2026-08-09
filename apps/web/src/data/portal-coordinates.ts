export type PortalCoordinates = {
  x: number;
  z: number;
};

const INTEGER_COORDINATE = /^-?\d+$/;

export function parsePortalCoordinates(
  raw: string,
): PortalCoordinates | null {
  const parts = raw.trim().split(/[\s,]+/);
  if (parts.length !== 2 && parts.length !== 3) return null;
  if (!parts.every((part) => INTEGER_COORDINATE.test(part))) return null;

  const coordinates = parts.map(Number);
  if (!coordinates.every(Number.isSafeInteger)) return null;

  return {
    x: coordinates[0],
    z: coordinates.length === 3 ? coordinates[2] : coordinates[1],
  };
}
