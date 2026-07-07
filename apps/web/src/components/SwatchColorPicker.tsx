"use client";

import { useState, useRef, useEffect, useLayoutEffect } from "react";
import { createPortal } from "react-dom";
import { HexColorPicker, HexColorInput } from "react-colorful";
import { hexToRgb } from "@/lib/colors";

interface SwatchColorPickerProps {
    color: string;
    onChange: (color: string) => void;
    /** Override the trigger button's classes (defaults to the full-width bar). */
    triggerClassName?: string;
    /** Custom trigger content. When omitted, a full-width colour bar with the
     *  hex overlaid is rendered. */
    children?: React.ReactNode;
    /** Accessible name for the trigger button. */
    ariaLabel?: string;
}

const PANEL_WIDTH = 216;
const DEFAULT_TRIGGER = "block w-full h-8 rounded-lg relative overflow-hidden border border-black/10 dark:border-white/15 cursor-pointer";

// Colour picker: a swatch trigger plus a themed popover rendered in a portal
// (the swatch lives inside a clip-path Squircle card, so an in-flow popover
// would be clipped). react-colorful is pure React — no DOM takeover — so it
// plays nicely with the drag-to-reorder list around it. The trigger can be
// customised (triggerClassName + children) for callers that need a different
// look; by default it's a full-width colour bar with the hex overlaid.
export default function SwatchColorPicker({ color, onChange, triggerClassName, children, ariaLabel = "Pick colour" }: SwatchColorPickerProps) {
    const [open, setOpen] = useState(false);
    const [pos, setPos] = useState<{ top: number; left: number } | null>(null);
    const triggerRef = useRef<HTMLButtonElement>(null);
    const popRef = useRef<HTMLDivElement>(null);

    const [r, g, b] = hexToRgb(color);
    const textClass = (r * 0.299 + g * 0.587 + b * 0.114) > 150 ? "text-gray-900" : "text-white";

    useLayoutEffect(() => {
        if (!open) return;
        const reposition = () => {
            const el = triggerRef.current;
            if (!el) return;
            const rect = el.getBoundingClientRect();
            const left = Math.max(8, Math.min(rect.left, window.innerWidth - PANEL_WIDTH - 8));
            setPos({ top: rect.bottom + 6, left });
        };
        reposition();
        window.addEventListener("scroll", reposition, true);
        window.addEventListener("resize", reposition);
        return () => {
            window.removeEventListener("scroll", reposition, true);
            window.removeEventListener("resize", reposition);
        };
    }, [open]);

    useEffect(() => {
        if (!open) return;
        const onDown = (e: PointerEvent) => {
            const target = e.target as Node;
            if (popRef.current?.contains(target) || triggerRef.current?.contains(target)) return;
            setOpen(false);
        };
        const onKey = (e: KeyboardEvent) => {
            if (e.key === "Escape") setOpen(false);
        };
        document.addEventListener("pointerdown", onDown);
        document.addEventListener("keydown", onKey);
        return () => {
            document.removeEventListener("pointerdown", onDown);
            document.removeEventListener("keydown", onKey);
        };
    }, [open]);

    return (
        <>
            <button
                type="button"
                ref={triggerRef}
                onClick={() => setOpen((o) => !o)}
                aria-label={ariaLabel}
                className={triggerClassName ?? DEFAULT_TRIGGER}
                style={triggerClassName ? undefined : { backgroundColor: color }}
            >
                {children ?? (
                    <span className={`absolute inset-0 flex items-center pl-2 text-xs font-bold ${textClass}`}>
                        {color.toUpperCase()}
                    </span>
                )}
            </button>
            {open && pos && createPortal(
                <div
                    ref={popRef}
                    className="rgb-swatch-picker fixed z-50 p-3 rounded-2xl bg-paper border border-line shadow-xl"
                    style={{ top: pos.top, left: pos.left, width: PANEL_WIDTH }}
                >
                    <HexColorPicker color={color} onChange={onChange} />
                    <HexColorInput
                        prefixed
                        color={color}
                        onChange={onChange}
                        className="w-full mt-2 px-2 py-1.5 rounded-lg border border-line bg-paper-2 text-sm font-mono uppercase text-gray-700 dark:text-gray-200 focus:outline-none focus:ring-2 focus:ring-orange-400"
                    />
                </div>,
                document.body
            )}
        </>
    );
}
