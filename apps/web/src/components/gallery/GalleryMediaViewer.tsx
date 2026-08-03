"use client";

import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import Image from "next/image";
import { ChevronLeft, ChevronRight, Maximize2, X } from "lucide-react";

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
  const [selectedIndex, setSelectedIndex] = useState<number | null>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeButtonRef = useRef<HTMLButtonElement>(null);
  const triggerRefs = useRef<Array<HTMLButtonElement | null>>([]);
  const openerIndexRef = useRef<number | null>(null);
  const isOpen = selectedIndex !== null;

  const close = () => {
    const returnTo = openerIndexRef.current;
    setSelectedIndex(null);
    openerIndexRef.current = null;
    if (returnTo !== null) {
      requestAnimationFrame(() => triggerRefs.current[returnTo]?.focus());
    }
  };

  const move = (direction: -1 | 1) => {
    setSelectedIndex((current) => {
      if (current === null) return null;
      return (current + direction + images.length) % images.length;
    });
  };

  const open = (index: number) => {
    openerIndexRef.current = index;
    setSelectedIndex(index);
  };

  useEffect(() => {
    if (!isOpen) return;

    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    closeButtonRef.current?.focus();

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") close();
      if (event.key === "ArrowLeft" && images.length > 1) move(-1);
      if (event.key === "ArrowRight" && images.length > 1) move(1);
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

  return (
    <>
      <div className={`grid gap-3 ${images.length > 1 ? "sm:grid-cols-2" : "grid-cols-1"}`}>
        {images.map((image, index) => (
          <button
            key={image.id}
            ref={(element) => {
              triggerRefs.current[index] = element;
            }}
            type="button"
            onClick={() => open(index)}
            aria-label={`Open image ${index + 1} of ${images.length}: ${image.alt}`}
            className={`group relative overflow-hidden rounded-2xl bg-[#130f0c] cursor-zoom-in focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-orange-500/60 ${
              images.length === 1 ? "aspect-[16/9]" : "aspect-[4/3]"
            }`}
          >
            <Image
              src={image.detailUrl}
              alt={image.alt}
              fill
              sizes={images.length === 1 ? "(max-width: 1024px) 100vw, 70vw" : "(max-width: 640px) 100vw, 35vw"}
              className="object-contain transition-transform duration-300 group-hover:scale-[1.015] motion-reduce:transition-none motion-reduce:group-hover:scale-100"
              priority={index === 0}
              unoptimized
            />
            <span className="absolute bottom-3 right-3 flex h-9 w-9 items-center justify-center rounded-full bg-black/60 text-white opacity-0 backdrop-blur-md transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100">
              <Maximize2 className="h-4 w-4" />
            </span>
          </button>
        ))}
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
                  src={images[selectedIndex].lightboxUrl}
                  alt={images[selectedIndex].alt}
                  fill
                  sizes="100vw"
                  className="object-contain"
                  priority
                  unoptimized
                />
              </div>

              {images.length > 1 ? (
                <div className="absolute bottom-4 left-1/2 z-10 flex -translate-x-1/2 items-center gap-2 rounded-full border border-white/10 bg-black/65 p-1.5 text-white backdrop-blur-md sm:bottom-6">
                  <button
                    type="button"
                    onClick={() => move(-1)}
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
                    onClick={() => move(1)}
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
