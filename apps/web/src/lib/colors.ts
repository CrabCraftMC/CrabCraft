/**
 * Shared color utility functions.
 *
 * Includes sRGB helpers (extracted from RGBGenerator) and
 * OkLAB perceptual color-space conversions.
 *
 * OkLAB reference: https://bottosson.github.io/posts/oklab/
 */

// ---------------------------------------------------------------------------
// sRGB helpers (originally in RGBGenerator.tsx)
// ---------------------------------------------------------------------------

export function hexToRgb(hex: string): [number, number, number] {
    const h = hex.replace("#", "");
    return [
        parseInt(h.substring(0, 2), 16),
        parseInt(h.substring(2, 4), 16),
        parseInt(h.substring(4, 6), 16),
    ];
}

export function rgbToHex(r: number, g: number, b: number): string {
    return (
        "#" +
        [r, g, b]
            .map((v) =>
                Math.round(v)
                    .toString(16)
                    .padStart(2, "0")
            )
            .join("")
    );
}

export function interpolateColors(colors: string[], steps: number): string[] {
    if (steps <= 0) return [];
    if (steps === 1) return [colors[0]];
    if (colors.length === 1) return Array(steps).fill(colors[0]);

    const result: string[] = [];
    const segments = colors.length - 1;

    for (let i = 0; i < steps; i++) {
        const t = i / (steps - 1);
        const segment = Math.min(Math.floor(t * segments), segments - 1);
        const localT = (t * segments) - segment;

        const [r1, g1, b1] = hexToRgb(colors[segment]);
        const [r2, g2, b2] = hexToRgb(colors[segment + 1]);

        result.push(
            rgbToHex(
                r1 + (r2 - r1) * localT,
                g1 + (g2 - g1) * localT,
                b1 + (b2 - b1) * localT
            )
        );
    }

    return result;
}

// ---------------------------------------------------------------------------
// OkLAB colour-space conversions
// ---------------------------------------------------------------------------

/** Convert an sRGB component (0-255) to linear RGB (0-1). */
function srgbComponentToLinear(c: number): number {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
}

/** Convert a linear RGB component (0-1) back to sRGB (0-255). */
function linearToSrgbComponent(c: number): number {
    const s = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    return Math.round(Math.min(255, Math.max(0, s * 255)));
}

/**
 * Convert sRGB (0-255 per channel) to OkLAB [L, a, b].
 *
 * Pipeline: sRGB -> linear RGB -> LMS (via matrix) -> cube-root -> OkLAB (via matrix)
 */
export function srgbToOklab(r: number, g: number, b: number): [number, number, number] {
    const lr = srgbComponentToLinear(r);
    const lg = srgbComponentToLinear(g);
    const lb = srgbComponentToLinear(b);

    // Linear RGB -> LMS
    const l = 0.4122214708 * lr + 0.5363325363 * lg + 0.0514459929 * lb;
    const m = 0.2119034982 * lr + 0.6806995451 * lg + 0.1073969566 * lb;
    const s = 0.0883024619 * lr + 0.2817188376 * lg + 0.6299787005 * lb;

    // Cube root
    const lc = Math.cbrt(l);
    const mc = Math.cbrt(m);
    const sc = Math.cbrt(s);

    // LMS (cube-root) -> OkLAB
    const L = 0.2104542553 * lc + 0.7936177850 * mc - 0.0040720468 * sc;
    const a = 1.9779984951 * lc - 2.4285922050 * mc + 0.4505937099 * sc;
    const bOut = 0.0259040371 * lc + 0.7827717662 * mc - 0.8086757660 * sc;

    return [L, a, bOut];
}

/**
 * Convert OkLAB [L, a, b] back to sRGB (0-255 per channel, clamped).
 *
 * Pipeline: OkLAB -> LMS (cube-root, via inverse matrix) -> cube -> linear RGB -> sRGB
 */
export function oklabToSrgb(L: number, a: number, b: number): [number, number, number] {
    // OkLAB -> LMS (cube-root)
    const lc = L + 0.3963377774 * a + 0.2158037573 * b;
    const mc = L - 0.1055613458 * a - 0.0638541728 * b;
    const sc = L - 0.0894841775 * a - 1.2914855480 * b;

    // Cube (undo cube root)
    const l = lc * lc * lc;
    const m = mc * mc * mc;
    const s = sc * sc * sc;

    // LMS -> linear RGB
    const lr =  4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
    const lg = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
    const lb = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;

    return [
        linearToSrgbComponent(lr),
        linearToSrgbComponent(lg),
        linearToSrgbComponent(lb),
    ];
}

/**
 * Interpolate between two hex colours through OkLAB space,
 * producing `steps` evenly-spaced hex values (inclusive of both endpoints).
 */
export function interpolateOklab(hex1: string, hex2: string, steps: number): string[] {
    if (steps <= 0) return [];
    if (steps === 1) return [hex1];

    const [L1, a1, b1] = srgbToOklab(...hexToRgb(hex1));
    const [L2, a2, b2] = srgbToOklab(...hexToRgb(hex2));

    const result: string[] = [];
    for (let i = 0; i < steps; i++) {
        const t = i / (steps - 1);
        const L = L1 + (L2 - L1) * t;
        const a = a1 + (a2 - a1) * t;
        const b = b1 + (b2 - b1) * t;
        const [r, g, bVal] = oklabToSrgb(L, a, b);
        result.push(rgbToHex(r, g, bVal));
    }
    return result;
}

/**
 * Perceptual colour distance (Euclidean distance in OkLAB space).
 */
export function colorDistance(hex1: string, hex2: string): number {
    const [L1, a1, b1] = srgbToOklab(...hexToRgb(hex1));
    const [L2, a2, b2] = srgbToOklab(...hexToRgb(hex2));
    return Math.sqrt((L1 - L2) ** 2 + (a1 - a2) ** 2 + (b1 - b2) ** 2);
}

/**
 * Perceptual colour distance from pre-computed OkLAB values.
 * Avoids hex parsing and conversion overhead in hot loops.
 */
export function colorDistanceLab(
    lab1: [number, number, number],
    lab2: [number, number, number]
): number {
    return Math.sqrt(
        (lab1[0] - lab2[0]) ** 2 +
        (lab1[1] - lab2[1]) ** 2 +
        (lab1[2] - lab2[2]) ** 2
    );
}

/**
 * Interpolate between two OkLAB colours at position t (0-1).
 * Returns [L, a, b].
 */
export function interpolateOklabValues(
    lab1: [number, number, number],
    lab2: [number, number, number],
    t: number
): [number, number, number] {
    return [
        lab1[0] + (lab2[0] - lab1[0]) * t,
        lab1[1] + (lab2[1] - lab1[1]) * t,
        lab1[2] + (lab2[2] - lab1[2]) * t,
    ];
}

// ---------------------------------------------------------------------------
// OkLCH (cylindrical) interpolation
// ---------------------------------------------------------------------------

/** Convert OkLAB [L, a, b] to OkLCH [L, C, H] (H in radians). */
export function oklabToOklch(L: number, a: number, b: number): [number, number, number] {
    const C = Math.sqrt(a * a + b * b);
    const H = Math.atan2(b, a);
    return [L, C, H];
}

/** Convert OkLCH [L, C, H] back to OkLAB [L, a, b]. */
export function oklchToOklab(L: number, C: number, H: number): [number, number, number] {
    return [L, C * Math.cos(H), C * Math.sin(H)];
}

/**
 * Interpolate between two OkLAB colours through OkLCH (cylindrical) space.
 * Keeps chroma high by interpolating hue along the shortest arc instead of
 * cutting through the desaturated center.  Returns [L, a, b].
 */
export function interpolateOklchValues(
    lab1: [number, number, number],
    lab2: [number, number, number],
    t: number
): [number, number, number] {
    const [L1, C1, H1] = oklabToOklch(...lab1);
    const [L2, C2, H2] = oklabToOklch(...lab2);

    const L = L1 + (L2 - L1) * t;
    const C = C1 + (C2 - C1) * t;

    // Achromatic edge case: if either endpoint has near-zero chroma,
    // atan2 is meaningless — use the other endpoint's hue.
    const ACHROMATIC = 0.01;
    let H: number;
    if (C1 < ACHROMATIC && C2 < ACHROMATIC) {
        H = 0; // both achromatic, hue irrelevant
    } else if (C1 < ACHROMATIC) {
        H = H2;
    } else if (C2 < ACHROMATIC) {
        H = H1;
    } else {
        // Shortest-arc hue interpolation
        let dh = H2 - H1;
        if (dh > Math.PI) dh -= 2 * Math.PI;
        if (dh < -Math.PI) dh += 2 * Math.PI;
        H = H1 + dh * t;
    }

    return oklchToOklab(L, C, H);
}
