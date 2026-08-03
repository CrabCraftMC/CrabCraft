"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import Image from "next/image";
import {
  ChevronLeft,
  ChevronRight,
  LoaderCircle,
  Maximize2,
  X,
} from "lucide-react";

interface GalleryViewerImage {
  id: string;
  alt: string;
  detailUrl: string;
  lightboxUrl: string;
}

export default function GalleryMediaViewer({
  images,
}: {
  images: GalleryViewerImage[];
}) {
  const [carouselIndex, setCarouselIndex] = useState(0);
  const [carouselLoading, setCarouselLoading] = useState(true);
  const [selectedIndex, setSelectedIndex] = useState<number | null>(null);
  const [lightboxLoading, setLightboxLoading] = useState(false);
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const isOpen = selectedIndex !== null;

  const close = () => {
    setSelectedIndex(null);
    requestAnimationFrame(() => triggerRef.current?.focus());
  };

  const moveLightbox = (direction: -1 | 1) => {
    setLightboxLoading(true);
    setSelectedIndex((current) => {
      if (current === null) return null;
      return (current + direction + images.length) % images.length;
    });
  };

  const moveCarousel = (direction: -1 | 1) => {
    setCarouselLoading(true);
    setCarouselIndex(
      (current) => (current + direction + images.length) % images.length,
    );
  };

  useEffect(() => {
    if (images.length < 2) return;

    for (const direction of [-1, 1]) {
      const index =
        (carouselIndex + direction + images.length) % images.length;
      const preload = new window.Image();
      preload.src = images[index].detailUrl;
    }

    const lightboxPreload = new window.Image();
    lightboxPreload.src = images[carouselIndex].lightboxUrl;
  }, [carouselIndex, images]);

  useEffect(() => {
    if (selectedIndex === null || images.length < 2) return;

    for (const direction of [-1, 1]) {
      const index =
        (selectedIndex + direction + images.length) % images.length;
      const preload = new window.Image();
      preload.src = images[index].lightboxUrl;
    }
  }, [images, selectedIndex]);

  useEffect(() => {
    if (!isOpen) return;

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    closeButtonRef.current?.focus();

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") close();
      if (event.key === "ArrowLeft" && images.length > 1) moveLightbox(-1);
      if (event.key === "ArrowRight" && images.length > 1) moveLightbox(1);
      if (event.key === "Tab") {
        const controls = Array.from(
          dialogRef.current?.querySelectorAll<HTMLButtonElement>("button:not([disabled])") ?? [],
        );
        const first = controls[0];
        const last = controls[controls.length - 1];
        if (event.shiftKey && document.activeElement === first) {
          event.preventDefault();
          last?.focus();
        } else if (!event.shiftKey && document.activeElement === last) {
          event.preventDefault();
          first?.focus();
        }
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [images.length, isOpen]);

  const carouselImage = images[carouselIndex];

  return (
    <>
      <div className="relative aspect-[16/9] overflow-hidden rounded-2xl bg-[#130f0c]">
        <button
          ref={triggerRef}
          type="button"
          onClick={() => {
            setLightboxLoading(true);
            setSelectedIndex(carouselIndex);
          }}
          aria-label={`Open image ${carouselIndex + 1} of ${images.length}: ${carouselImage.alt}`}
          aria-busy={carouselLoading}
          className="group absolute inset-0 cursor-zoom-in focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-orange-500/60 focus-visible:ring-inset"
        >
          <Image
            key={carouselImage.id}
            src={carouselImage.detailUrl}
            alt={carouselImage.alt}
            fill
            sizes="(max-width: 1024px) 100vw, 70vw"
            className="object-contain"
            priority={carouselIndex === 0}
            unoptimized
            onLoad={() => setCarouselLoading(false)}
            onError={() => setCarouselLoading(false)}
          />
          {carouselLoading ? (
            <span className="absolute inset-0 flex items-center justify-center bg-[#130f0c] text-white/70">
              <LoaderCircle className="h-6 w-6 animate-spin" />
              <span className="sr-only">Loading image</span>
            </span>
          ) : null}
          <span className="absolute bottom-3 right-3 flex h-9 w-9 items-center justify-center rounded-full bg-black/60 text-white opacity-0 backdrop-blur-md group-hover:opacity-100 group-focus-visible:opacity-100">
            <Maximize2 className="h-4 w-4" />
          </span>
        </button>

        {images.length > 1 ? (
          <div className="absolute bottom-3 left-1/2 z-10 flex -translate-x-1/2 items-center gap-2 rounded-full border border-white/10 bg-black/65 p-1 text-white backdrop-blur-md">
            <button
              type="button"
              onClick={() => moveCarousel(-1)}
              aria-label="Previous image"
              className="flex h-10 w-10 cursor-pointer items-center justify-center rounded-full transition-colors hover:bg-white/15 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white"
            >
              <ChevronLeft className="h-5 w-5" />
            </button>
            <span
              className="min-w-12 text-center text-xs font-bold"
              aria-live="polite"
            >
              {carouselIndex + 1} / {images.length}
            </span>
            <button
              type="button"
              onClick={() => moveCarousel(1)}
              aria-label="Next image"
              className="flex h-10 w-10 cursor-pointer items-center justify-center rounded-full transition-colors hover:bg-white/15 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white"
            >
              <ChevronRight className="h-5 w-5" />
            </button>
          </div>
        ) : null}
      </div>

      {selectedIndex !== null
        ? createPortal(
            <div
              ref={dialogRef}
              role="dialog"
              aria-modal="true"
              aria-label={`Image ${selectedIndex + 1} of ${images.length}`}
              className="fixed inset-0 z-[100] flex overscroll-contain items-center justify-center bg-black/90 p-4 backdrop-blur-sm"
              onMouseDown={(event) => {
                if (event.target === event.currentTarget) close();
              }}
            >
              <button
                ref={closeButtonRef}
                type="button"
                onClick={close}
                aria-label="Close image viewer"
                className="absolute right-4 top-4 flex h-11 w-11 items-center justify-center rounded-full bg-white/10 text-white transition-colors hover:bg-white/20 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white"
              >
                <X className="h-5 w-5" />
              </button>

              <div className="relative h-[calc(100dvh-8rem)] w-[calc(100vw-2rem)] max-w-7xl">
                <Image
                  key={images[selectedIndex].id}
                  src={images[selectedIndex].lightboxUrl}
                  alt={images[selectedIndex].alt}
                  fill
                  sizes="100vw"
                  className="object-contain"
                  priority
                  unoptimized
                  onLoad={() => setLightboxLoading(false)}
                  onError={() => setLightboxLoading(false)}
                />
                {lightboxLoading ? (
                  <span className="absolute inset-0 flex items-center justify-center bg-black text-white/70">
                    <LoaderCircle className="h-7 w-7 animate-spin" />
                    <span className="sr-only">Loading full-size image</span>
                  </span>
                ) : null}
              </div>

              {images.length > 1 ? (
                <div className="absolute bottom-4 left-1/2 z-10 flex -translate-x-1/2 items-center gap-2 rounded-full border border-white/10 bg-black/65 p-1.5 text-white backdrop-blur-md sm:bottom-6">
                  <button
                    type="button"
                    onClick={() => moveLightbox(-1)}
                    aria-label="Previous image"
                    className="flex h-11 w-11 items-center justify-center rounded-full transition-colors hover:bg-white/15 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white"
                  >
                    <ChevronLeft className="h-6 w-6" />
                  </button>
                  <span
                    className="min-w-14 text-center text-xs font-bold"
                    aria-live="polite"
                  >
                    {selectedIndex + 1} / {images.length}
                  </span>
                  <button
                    type="button"
                    onClick={() => moveLightbox(1)}
                    aria-label="Next image"
                    className="flex h-11 w-11 items-center justify-center rounded-full transition-colors hover:bg-white/15 cursor-pointer focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white"
                  >
                    <ChevronRight className="h-6 w-6" />
                  </button>
                </div>
              ) : (
                <span className="absolute bottom-5 left-1/2 -translate-x-1/2 rounded-full bg-black/50 px-3 py-1.5 text-xs font-bold text-white">
                  1 / 1
                </span>
              )}
            </div>,
            document.body,
          )
        : null}
    </>
  );
}
