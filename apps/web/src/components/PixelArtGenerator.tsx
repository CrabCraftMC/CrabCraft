"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import {
  Download,
  FileImage,
  LoaderCircle,
  RefreshCw,
  ShieldCheck,
  Upload,
} from "lucide-react";
import Squircle from "@/components/Squircle";
import blocks from "@/data/blocks.json";
import { isBlockAllowedForPresets } from "@/lib/blockGradientPresets";
import {
  fitGridDimensions,
  makePixelArtFilename,
  mapPixelsToBlocks,
  MAX_PIXEL_ART_DETAIL,
  MIN_PIXEL_ART_DETAIL,
  prepareBlockPalette,
  type PixelArtResult,
} from "@/lib/pixelArt";

const DEFAULT_DETAIL = 64;
const MAX_FILE_SIZE = 20 * 1024 * 1024;
const MAX_SOURCE_PIXELS = 25_000_000;
const OUTPUT_BLOCK_SIZE = 16;
const TEXTURE_BASE = "/textures/blocks";
const ACCEPTED_FILE_TYPES = new Set([
  "image/jpeg",
  "image/png",
  "image/webp",
]);

const BLOCK_PALETTE = prepareBlockPalette(
  blocks.filter(
    (block) =>
      isBlockAllowedForPresets(block, ["no_transparent"]) &&
      !block.id.endsWith("_glazed_terracotta"),
  ),
);

const textureCache = new Map<string, Promise<HTMLImageElement>>();

interface SourceImage {
  bitmap: ImageBitmap;
  filename: string;
  fileSize: number;
  width: number;
  height: number;
}

type GeneratorStatus =
  | "idle"
  | "decoding"
  | "mapping"
  | "rendering"
  | "ready";

function loadTexture(texture: string) {
  const cached = textureCache.get(texture);
  if (cached) return cached;

  const promise = new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image();
    image.decoding = "async";
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error(`Could not load ${texture}`));
    image.src = `${TEXTURE_BASE}/${texture}.png`;
  });

  textureCache.set(texture, promise);
  void promise.catch(() => {
    if (textureCache.get(texture) === promise) {
      textureCache.delete(texture);
    }
  });
  return promise;
}

function drawMosaic(
  canvas: HTMLCanvasElement,
  result: PixelArtResult,
  textures: ReadonlyMap<string, HTMLImageElement>,
) {
  canvas.width = result.width * OUTPUT_BLOCK_SIZE;
  canvas.height = result.height * OUTPUT_BLOCK_SIZE;

  const context = canvas.getContext("2d");
  if (!context) {
    throw new Error("Canvas rendering is unavailable in this browser");
  }

  context.clearRect(0, 0, canvas.width, canvas.height);
  context.imageSmoothingEnabled = false;

  result.cells.forEach((block, index) => {
    if (!block) return;

    const x = (index % result.width) * OUTPUT_BLOCK_SIZE;
    const y = Math.floor(index / result.width) * OUTPUT_BLOCK_SIZE;
    const texture = textures.get(block.texture);

    if (!texture) {
      context.fillStyle = block.color;
      context.fillRect(x, y, OUTPUT_BLOCK_SIZE, OUTPUT_BLOCK_SIZE);
      return;
    }

    const frameSize = Math.min(texture.naturalWidth, texture.naturalHeight);
    context.drawImage(
      texture,
      0,
      0,
      frameSize,
      frameSize,
      x,
      y,
      OUTPUT_BLOCK_SIZE,
      OUTPUT_BLOCK_SIZE,
    );
  });
}

function formatFileSize(bytes: number) {
  return bytes < 1024 * 1024
    ? `${Math.max(1, Math.round(bytes / 1024))} KB`
    : `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export default function PixelArtGenerator() {
  const [source, setSource] = useState<SourceImage | null>(null);
  const [detail, setDetail] = useState(DEFAULT_DETAIL);
  const [result, setResult] = useState<PixelArtResult | null>(null);
  const [status, setStatus] = useState<GeneratorStatus>("idle");
  const [error, setError] = useState<string | null>(null);
  const [dragActive, setDragActive] = useState(false);
  const [previewBlockPopover, setPreviewBlockPopover] = useState<{
    index: number;
    name: string;
    texture: string | null;
    x: number;
    y: number;
  } | null>(null);
  const [downloadUrl, setDownloadUrl] = useState<string | null>(null);

  const inputRef = useRef<HTMLInputElement>(null);
  const sourceCanvasRef = useRef<HTMLCanvasElement>(null);
  const outputCanvasRef = useRef<HTMLCanvasElement>(null);
  const decodeRunRef = useRef(0);
  const generationRunRef = useRef(0);
  const renderRunRef = useRef(0);
  const downloadUrlRef = useRef<string | null>(null);

  const targetDimensions = useMemo(
    () =>
      source
        ? fitGridDimensions(source.width, source.height, detail)
        : null,
    [detail, source],
  );

  const downloadFilename = useMemo(
    () =>
      source && result
        ? makePixelArtFilename(source.filename, result.width, result.height)
        : null,
    [result, source],
  );

  const clearDownloadUrl = useCallback(() => {
    if (downloadUrlRef.current) {
      URL.revokeObjectURL(downloadUrlRef.current);
      downloadUrlRef.current = null;
    }
    setDownloadUrl(null);
  }, []);

  useEffect(() => {
    return () => source?.bitmap.close();
  }, [source]);

  useEffect(() => {
    return () => {
      decodeRunRef.current++;
      generationRunRef.current++;
      renderRunRef.current++;
      if (downloadUrlRef.current) {
        URL.revokeObjectURL(downloadUrlRef.current);
      }
    };
  }, []);

  useEffect(() => {
    if (!source || !sourceCanvasRef.current) return;

    const canvas = sourceCanvasRef.current;
    const longestSide = 480;
    const scale = Math.min(1, longestSide / Math.max(source.width, source.height));
    canvas.width = Math.max(1, Math.round(source.width * scale));
    canvas.height = Math.max(1, Math.round(source.height * scale));

    const context = canvas.getContext("2d");
    if (!context) return;
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.drawImage(source.bitmap, 0, 0, canvas.width, canvas.height);
  }, [source]);

  useEffect(() => {
    if (!source) return;

    const runId = ++generationRunRef.current;
    const dimensions = fitGridDimensions(
      source.width,
      source.height,
      detail,
    );

    renderRunRef.current++;
    clearDownloadUrl();
    setStatus("mapping");
    setError(null);

    const timer = window.setTimeout(() => {
      try {
        const sampleCanvas = document.createElement("canvas");
        sampleCanvas.width = dimensions.width;
        sampleCanvas.height = dimensions.height;

        const context = sampleCanvas.getContext("2d", {
          willReadFrequently: true,
        });
        if (!context) {
          throw new Error("Canvas processing is unavailable in this browser");
        }

        context.imageSmoothingEnabled = true;
        context.imageSmoothingQuality = "high";
        context.clearRect(0, 0, dimensions.width, dimensions.height);
        context.drawImage(
          source.bitmap,
          0,
          0,
          dimensions.width,
          dimensions.height,
        );

        const pixels = context.getImageData(
          0,
          0,
          dimensions.width,
          dimensions.height,
        );
        const nextResult = mapPixelsToBlocks(
          pixels.data,
          dimensions.width,
          dimensions.height,
          BLOCK_PALETTE,
        );

        if (generationRunRef.current === runId) {
          setPreviewBlockPopover(null);
          setResult(nextResult);
        }
      } catch {
        if (generationRunRef.current === runId) {
          setError("We couldn't turn that image into blocks. Please try another image.");
          setStatus("idle");
        }
      }
    }, 100);

    return () => window.clearTimeout(timer);
  }, [clearDownloadUrl, detail, source]);

  useEffect(() => {
    const canvas = outputCanvasRef.current;
    if (!result || !canvas) return;

    const runId = ++renderRunRef.current;
    setStatus("rendering");

    // Paint average block colours immediately while the small texture set loads.
    drawMosaic(canvas, result, new Map());

    const uniqueTextures = [
      ...new Set(
        result.cells.flatMap((block) => (block ? [block.texture] : [])),
      ),
    ];

    void Promise.all(
      uniqueTextures.map(async (texture) => {
        try {
          return [texture, await loadTexture(texture)] as const;
        } catch {
          return [texture, null] as const;
        }
      }),
    ).then((loadedTextures) => {
      if (renderRunRef.current !== runId) return;

      const textureMap = new Map(
        loadedTextures.filter(
          (entry): entry is readonly [string, HTMLImageElement] =>
            entry[1] !== null,
        ),
      );
      drawMosaic(canvas, result, textureMap);
      canvas.toBlob((blob) => {
        if (renderRunRef.current !== runId) return;
        if (!blob) {
          setError("We couldn't prepare the download. Please try again.");
          setStatus("idle");
          return;
        }

        const url = URL.createObjectURL(blob);
        downloadUrlRef.current = url;
        setDownloadUrl(url);
        setStatus("ready");
      }, "image/png");
    });

    return () => {
      if (renderRunRef.current === runId) renderRunRef.current++;
    };
  }, [result]);

  const handleFile = useCallback(async (file: File) => {
    const runId = ++decodeRunRef.current;
    setPreviewBlockPopover(null);
    setError(null);

    if (!ACCEPTED_FILE_TYPES.has(file.type)) {
      setError("Choose a PNG, JPEG, or WebP image.");
      setStatus((current) => current === "decoding" ? "idle" : current);
      return;
    }
    if (file.size > MAX_FILE_SIZE) {
      setError("Choose an image smaller than 20 MB.");
      setStatus((current) => current === "decoding" ? "idle" : current);
      return;
    }

    clearDownloadUrl();
    setSource(null);
    setResult(null);
    setStatus("decoding");

    try {
      const bitmap = await createImageBitmap(file, {
        imageOrientation: "from-image",
      });

      if (decodeRunRef.current !== runId) {
        bitmap.close();
        return;
      }
      if (bitmap.width * bitmap.height > MAX_SOURCE_PIXELS) {
        bitmap.close();
        setError("Choose an image smaller than 25 megapixels.");
        setStatus("idle");
        return;
      }

      setSource({
        bitmap,
        filename: file.name,
        fileSize: file.size,
        width: bitmap.width,
        height: bitmap.height,
      });
    } catch {
      if (decodeRunRef.current === runId) {
        setError("We couldn't read that image. Try a different PNG, JPEG, or WebP file.");
        setStatus("idle");
      }
    }
  }, [clearDownloadUrl]);

  const handleInputChange = useCallback(
    (event: React.ChangeEvent<HTMLInputElement>) => {
      const file = event.currentTarget.files?.[0];
      if (file) void handleFile(file);
      event.currentTarget.value = "";
    },
    [handleFile],
  );

  const handlePreviewClick = useCallback(
    (event: React.MouseEvent<HTMLCanvasElement>) => {
      if (!result) return;

      const rect = event.currentTarget.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) return;

      const column = Math.min(
        result.width - 1,
        Math.max(
          0,
          Math.floor(((event.clientX - rect.left) / rect.width) * result.width),
        ),
      );
      const row = Math.min(
        result.height - 1,
        Math.max(
          0,
          Math.floor(((event.clientY - rect.top) / rect.height) * result.height),
        ),
      );
      const index = row * result.width + column;
      const block = result.cells[index];
      const popoverHalfWidth = Math.min(
        160,
        Math.max(0, window.innerWidth / 2 - 8),
      );

      setPreviewBlockPopover((current) =>
        current?.index === index
          ? null
          : {
              index,
              name: block?.name ?? "Air",
              texture: block?.texture ?? null,
              x: Math.min(
                window.innerWidth - popoverHalfWidth,
                Math.max(popoverHalfWidth, event.clientX),
              ),
              y: event.clientY - 8,
            },
      );
    },
    [result],
  );

  const statusMessage =
    status === "decoding"
      ? "Reading image…"
      : status === "mapping"
        ? "Matching colours to blocks…"
        : status === "rendering"
          ? "Placing block textures…"
          : status === "ready"
            ? "Your block art is ready."
            : "";

  return (
    <div className="min-h-screen pt-24 pb-16">
      <div className="container mx-auto px-4 max-w-6xl">
        <div className="text-center mb-10 animate-in">
          <h1 className="text-4xl lg:text-5xl font-bold text-orange-600 dark:text-orange-500 font-mc">
            Pixel Art Generator
          </h1>
          <p className="mt-2 text-gray-600 dark:text-gray-400">
            Turn any image into a Minecraft block mosaic
          </p>
        </div>

        <p className="sr-only" aria-live="polite">
          {statusMessage}
        </p>

        {error && (
          <div
            role="alert"
            className="mb-6 rounded-2xl border border-red-300 bg-red-50 px-4 py-3 text-sm font-bold text-red-700 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-300"
          >
            {error}
          </div>
        )}

        {!source ? (
          <Squircle
            cornerRadius={32}
            className="mx-auto max-w-3xl bg-paper-2 p-6 shadow-sm animate-in"
            style={{ animationDelay: "0.1s" }}
          >
            <div className="mb-3">
              <h2 className="text-sm font-bold text-gray-700 dark:text-gray-300">
                Upload Image
              </h2>
            </div>

            <input
              ref={inputRef}
              id="pixel-art-upload"
              type="file"
              accept="image/png,image/jpeg,image/webp"
              className="peer sr-only"
              onChange={handleInputChange}
            />
            <label
              htmlFor="pixel-art-upload"
              onDragEnter={(event) => {
                event.preventDefault();
                setDragActive(true);
              }}
              onDragOver={(event) => event.preventDefault()}
              onDragLeave={() => setDragActive(false)}
              onDrop={(event) => {
                event.preventDefault();
                setDragActive(false);
                const file = event.dataTransfer.files[0];
                if (file) void handleFile(file);
              }}
              className={`group flex min-h-40 w-full cursor-pointer flex-col items-center justify-center rounded-2xl border-2 border-dashed px-6 py-8 text-center transition-[border-color,background-color] peer-focus-visible:border-orange-500 peer-focus-visible:outline-none peer-focus-visible:ring-2 peer-focus-visible:ring-orange-500 peer-focus-visible:ring-offset-2 peer-focus-visible:ring-offset-paper-2 ${
                dragActive
                  ? "border-orange-500 bg-orange-500/10"
                  : "border-line bg-paper hover:border-orange-400 hover:bg-orange-500/[0.03]"
              }`}
            >
              {status === "decoding" ? (
                <LoaderCircle className="h-7 w-7 animate-spin text-orange-600 dark:text-orange-500" />
              ) : (
                <Upload className="h-7 w-7 text-orange-600 transition-transform group-hover:-translate-y-0.5 dark:text-orange-400" />
              )}
              <span className="mt-3 block text-base font-bold text-gray-800 transition-colors group-hover:text-orange-600 dark:text-gray-100 dark:group-hover:text-orange-400">
                {status === "decoding"
                  ? "Reading your image…"
                  : "Upload or drop an image here"}
              </span>
              <span className="mt-1 block text-xs text-gray-500 dark:text-gray-400">
                Choose a photo, logo, or drawing
              </span>
            </label>

            <p className="mt-3 flex items-center justify-end gap-1.5 text-xs font-medium text-green-700 dark:text-green-400">
              <ShieldCheck className="h-3.5 w-3.5" />
              Private: stays in your browser
            </p>
          </Squircle>
        ) : (
          <div className="grid grid-cols-1 items-start gap-6 lg:grid-cols-[300px_minmax(0,1fr)]">
            <Squircle
              cornerRadius={32}
              className="min-w-0 self-start bg-paper-2 p-6 shadow-sm animate-in lg:h-[580px]"
              style={{ animationDelay: "0.1s" }}
            >
              <div className="mb-3 flex items-center justify-between gap-3">
                <h2 className="text-sm font-bold text-gray-700 dark:text-gray-300">
                  Original image
                </h2>
                <FileImage className="h-4 w-4 text-gray-400" />
              </div>

              <div className="checkerboard flex min-h-44 items-center justify-center overflow-hidden rounded-2xl border border-line p-2">
                <canvas
                  ref={sourceCanvasRef}
                  role="img"
                  aria-label={`Original image, ${source.width} by ${source.height} pixels`}
                  className="block h-auto max-h-48 w-auto max-w-full"
                />
              </div>

              <div className="mt-3 min-w-0">
                <p className="truncate text-sm font-bold text-gray-800 dark:text-gray-100">
                  {source.filename}
                </p>
                <p className="mt-1 text-xs text-gray-600 dark:text-gray-400">
                  {source.width}×{source.height} · {formatFileSize(source.fileSize)}
                </p>
              </div>

              <input
                ref={inputRef}
                type="file"
                accept="image/png,image/jpeg,image/webp"
                hidden
                onChange={handleInputChange}
              />

              <button
                type="button"
                onClick={() => inputRef.current?.click()}
                className="mt-4 inline-flex w-full cursor-pointer items-center justify-center gap-2 whitespace-nowrap rounded-xl bg-paper px-3 py-2.5 text-sm font-bold text-gray-700 transition-colors hover:bg-line focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-orange-600 dark:text-gray-300"
              >
                <RefreshCw className="h-4 w-4 shrink-0" />
                Choose another image
              </button>

              <div className="my-6 h-px bg-line" />

              <div className="flex items-end justify-between gap-4">
                <label
                  htmlFor="pixel-art-detail"
                  className="text-sm font-bold text-gray-700 dark:text-gray-300"
                >
                  Detail
                </label>
                <span
                  id="pixel-art-detail-value"
                  className="text-xs font-bold text-orange-700 dark:text-orange-400"
                >
                  {targetDimensions?.width}×{targetDimensions?.height} blocks
                </span>
              </div>
              <input
                id="pixel-art-detail"
                type="range"
                min={MIN_PIXEL_ART_DETAIL}
                max={MAX_PIXEL_ART_DETAIL}
                step={4}
                value={detail}
                aria-describedby="pixel-art-detail-value"
                aria-valuetext={
                  targetDimensions
                    ? `${targetDimensions.width} by ${targetDimensions.height} blocks`
                    : undefined
                }
                onChange={(event) => {
                  setPreviewBlockPopover(null);
                  setDetail(Number(event.target.value));
                }}
                className="mt-3 w-full cursor-pointer rounded-full focus-visible:ring-2 focus-visible:ring-orange-600 focus-visible:ring-offset-4 focus-visible:ring-offset-paper-2"
              />
              <div className="mt-2 flex justify-between text-[10px] font-bold text-gray-600 dark:text-gray-400">
                <span>Chunky</span>
                <span>Detailed</span>
              </div>

              <p className="mt-5 text-xs leading-relaxed text-gray-500 dark:text-gray-400">
                Colours are matched to opaque Minecraft blocks. Transparent
                parts of your image stay empty.
              </p>
            </Squircle>

            <Squircle
              cornerRadius={32}
              className="min-w-0 bg-paper-2 p-6 shadow-sm animate-in"
              style={{ animationDelay: "0.15s" }}
            >
              <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                <div>
                  <h2 className="text-sm font-bold text-gray-700 dark:text-gray-300">
                    Block mosaic
                  </h2>
                  <p className="mt-1 text-xs text-gray-600 dark:text-gray-400">
                    {result
                      ? `${result.width}×${result.height} · ${result.blockCount.toLocaleString()} blocks · ${result.uniqueBlocks} types`
                      : "Preparing your mosaic…"}
                  </p>
                </div>
                {status === "ready" && downloadUrl && downloadFilename ? (
                  <a
                    href={downloadUrl}
                    download={downloadFilename}
                    data-umami-event="tool-result-downloaded"
                    data-umami-event-tool="pixel-art-generator"
                    data-umami-event-result="png"
                    className="inline-flex min-h-11 cursor-pointer items-center justify-center gap-2 whitespace-nowrap rounded-xl bg-orange-700 px-4 py-2.5 text-sm font-bold text-white transition-colors hover:bg-orange-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-orange-600 focus-visible:ring-offset-2 focus-visible:ring-offset-paper-2"
                  >
                    <Download className="h-4 w-4" />
                    Download PNG
                  </a>
                ) : (
                  <button
                    type="button"
                    disabled
                    className="inline-flex min-h-11 cursor-not-allowed items-center justify-center gap-2 rounded-xl bg-orange-700 px-4 py-2.5 text-sm font-bold text-white opacity-50"
                  >
                    <LoaderCircle className="h-4 w-4 animate-spin" />
                    Download PNG
                  </button>
                )}
              </div>

              <div className="checkerboard relative flex min-h-[360px] items-center justify-center overflow-auto rounded-2xl border border-line p-3 sm:min-h-[480px]">
                {result ? (
                  <canvas
                    ref={outputCanvasRef}
                    role="img"
                    aria-label={`${result.width} by ${result.height} Minecraft block mosaic using ${result.blockCount} blocks across ${result.uniqueBlocks} block types. Click a tile to identify its block.`}
                    title="Click a tile to identify its block"
                    onClick={handlePreviewClick}
                    className="pixelated mx-auto block h-auto max-h-[70vh] w-auto max-w-full cursor-crosshair"
                  />
                ) : (
                  <div className="flex flex-col items-center gap-3 text-sm font-bold text-gray-500 dark:text-gray-400">
                    <LoaderCircle className="h-6 w-6 animate-spin text-orange-600 dark:text-orange-500" />
                    {statusMessage}
                  </div>
                )}

                {result && status !== "ready" && (
                  <div className="absolute inset-0 flex items-center justify-center bg-paper/70 backdrop-blur-[2px]">
                    <div className="flex items-center gap-2 rounded-xl bg-paper-2 px-4 py-3 text-sm font-bold text-gray-700 shadow-lg dark:text-gray-300">
                      <LoaderCircle className="h-4 w-4 animate-spin text-orange-600 dark:text-orange-500" />
                      {statusMessage}
                    </div>
                  </div>
                )}
              </div>

              <p className="mt-4 text-center text-xs text-gray-600 dark:text-gray-400">
                Click any tile to identify its block. Every downloaded tile is one Minecraft block texture.
              </p>
            </Squircle>
          </div>
        )}
      </div>

      {previewBlockPopover && source && (
        <div
          className="pointer-events-none fixed z-[60] -translate-x-1/2 -translate-y-full animate-[scaleIn_0.1s_ease-out]"
          style={{ left: previewBlockPopover.x, top: previewBlockPopover.y }}
        >
          <div className="flex w-max max-w-[calc(100vw-1rem)] items-center gap-3 rounded-xl border border-line bg-paper-2 p-3 shadow-xl">
            {previewBlockPopover.texture ? (
              <div className="h-8 w-8 shrink-0 overflow-hidden rounded">
                <img
                  src={`${TEXTURE_BASE}/${previewBlockPopover.texture}.png`}
                  alt=""
                  className="block-texture h-full w-full"
                />
              </div>
            ) : (
              <div className="checkerboard h-8 w-8 shrink-0 rounded border border-line" />
            )}
            <p className="min-w-0 max-w-64 text-sm font-bold whitespace-normal text-gray-800 dark:text-gray-200">
              {previewBlockPopover.name}
            </p>
          </div>
          <div className="flex justify-center">
            <div className="-mt-1.5 h-2 w-2 rotate-45 border-b border-r border-line bg-paper-2" />
          </div>
        </div>
      )}
    </div>
  );
}
