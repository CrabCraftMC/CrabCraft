"use client";

import { useState, useMemo, useRef, useEffect, useCallback } from "react";
import { GripVertical } from "lucide-react";
import { useWebHaptics } from "web-haptics/react";
import SwatchColorPicker from "./SwatchColorPicker";
import Squircle from "@/components/Squircle";
import { rgbToHex, interpolateColors } from "@/lib/colors";
import { captureWebToolCompleted } from "@/lib/analytics";

type Format = "minimessage" | "ampersand" | "section" | "ampersand-hex";

interface ColorStop {
    color: string;
    id: number;
}

function formatChar(char: string, hex: string, format: Format, bold: boolean, italic: boolean, underline: boolean, strikethrough: boolean): string {
    const h = hex.replace("#", "");
    let prefix = "";

    switch (format) {
        case "minimessage": {
            let tags = `<color:${hex}>`;
            if (bold) tags += "<bold>";
            if (italic) tags += "<italic>";
            if (underline) tags += "<underline>";
            if (strikethrough) tags += "<strikethrough>";
            return tags + char;
        }
        case "ampersand-hex":
            prefix = `&#${h}`;
            if (bold) prefix += "&l";
            if (italic) prefix += "&o";
            if (underline) prefix += "&n";
            if (strikethrough) prefix += "&m";
            return prefix + char;
        case "ampersand":
            prefix = `&x${h.split("").map((c) => "&" + c).join("")}`;
            if (bold) prefix += "&l";
            if (italic) prefix += "&o";
            if (underline) prefix += "&n";
            if (strikethrough) prefix += "&m";
            return prefix + char;
        case "section":
            prefix = `\u00a7x${h.split("").map((c) => "\u00a7" + c).join("")}`;
            if (bold) prefix += "\u00a7l";
            if (italic) prefix += "\u00a7o";
            if (underline) prefix += "\u00a7n";
            if (strikethrough) prefix += "\u00a7m";
            return prefix + char;
        default:
            return char;
    }
}

const FORMAT_LABELS: Record<Format, string> = {
    minimessage: "MiniMessage",
    "ampersand-hex": "&#rrggbb",
    ampersand: "&x&r&r&g&g&b&b",
    section: "\u00a7x\u00a7r\u00a7r\u00a7g\u00a7g\u00a7b\u00a7b",
};

interface CharFormat {
    bold: boolean;
    italic: boolean;
    underline: boolean;
    strikethrough: boolean;
}

const defaultFormat: CharFormat = { bold: false, italic: false, underline: false, strikethrough: false };

const PRESETS: { name: string; colors: string[] }[] = [
    { name: "CrabCraft", colors: ["#F97316", "#FB923C"] },
    { name: "Sunset", colors: ["#FF512F", "#DD2476"] },
    { name: "Ocean", colors: ["#2193b0", "#6dd5ed"] },
    { name: "Fire", colors: ["#f12711", "#f5af19"] },
    { name: "Forest", colors: ["#134E5E", "#71B280"] },
    { name: "Cotton Candy", colors: ["#D4418E", "#0652C5"] },
    { name: "Lime", colors: ["#a8e063", "#56ab2f"] },
    { name: "Neon", colors: ["#00f260", "#0575e6"] },
    { name: "Lava", colors: ["#f83600", "#fe8c00"] },
    { name: "Royal", colors: ["#141E30", "#243B55"] },
    { name: "Rose Gold", colors: ["#F4C4F3", "#FC5C7D"] },
    { name: "Rainbow", colors: ["#ff0000", "#ff8800", "#ffff00", "#00ff00", "#0000ff", "#8800ff"] },
];

const WHITESPACE_RE = /\s/g;

const STORAGE_KEY = "crabcraft-rgb";

function loadSaved() {
    if (typeof window === "undefined") return null;
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        return raw ? JSON.parse(raw) : null;
    } catch { return null; }
}

export default function RGBGenerator() {
    const [text, setText] = useState("CrabCraft");
    const [charFormats, setCharFormats] = useState<CharFormat[]>(
        Array.from({ length: 9 }, () => ({ ...defaultFormat }))
    );
    const [colorStops, setColorStops] = useState<ColorStop[]>([
        { color: "#F97316", id: 0 },
        { color: "#FB923C", id: 1 },
    ]);
    const [format] = useState<Format>("ampersand-hex");
    const [charsPerColor, setCharsPerColor] = useState(1);
    const [hydrated, setHydrated] = useState(false);

    // Drag-to-reorder. Rows never change order in the DOM — we reorder the
    // colour VALUES on drop and keep each row/id fixed, so the pickers never
    // remount. The "push down" feel during a drag is faked with CSS transforms
    // only: the dragged row follows the pointer (dragY) and the rows it passes
    // slide up/down by one rowHeight to open a gap.
    const [dragIndex, setDragIndex] = useState<number | null>(null);
    const [overIndex, setOverIndex] = useState<number | null>(null);
    const [dragY, setDragY] = useState(0);
    const [rowHeight, setRowHeight] = useState(0);
    // True for the single frame after a drop: transforms snap to their resting
    // positions (transition disabled) while the colour values commit, so the
    // rows don't visibly slide back after the reorder already happened.
    const [isDropping, setIsDropping] = useState(false);
    const colorListRef = useRef<HTMLDivElement>(null);

    // Load saved state after hydration
    useEffect(() => {
        const saved = loadSaved();
        if (saved) {
            if (saved.text) setText(saved.text);
            if (saved.charFormats) setCharFormats(saved.charFormats);
            if (saved.colorStops) {
                // Reassign fresh sequential ids on load: guarantees uniqueness
                // (heals any older saved state with duplicate ids) and keeps the
                // id counter in sync so new colours can't collide.
                const restored = saved.colorStops.map((s: ColorStop, i: number) => ({ color: s.color, id: i }));
                setColorStops(restored);
                nextIdRef.current = restored.length;
            }
            if (saved.charsPerColor) setCharsPerColor(saved.charsPerColor);
        }
        setHydrated(true);
    }, []);

    // Save to localStorage on changes (only after hydration)
    useEffect(() => {
        if (!hydrated) return;
        localStorage.setItem(STORAGE_KEY, JSON.stringify({ text, charFormats, colorStops, charsPerColor }));
    }, [text, charFormats, colorStops, charsPerColor, hydrated]);
    const [copied, setCopied] = useState<string | null>(null);
    const [showPresets, setShowPresets] = useState(false);
    const [showResetConfirm, setShowResetConfirm] = useState(false);
    const editorRef = useRef<HTMLDivElement>(null);
    const nextIdRef = useRef(Math.max(...colorStops.map(s => s.id), -1) + 1);

    const colors = colorStops.map((s) => s.color);

    useEffect(() => {
        const handler = (e: KeyboardEvent) => {
            if (e.key !== "Escape") return;
            if (showResetConfirm) setShowResetConfirm(false);
            else if (showPresets) setShowPresets(false);
        };
        document.addEventListener("keydown", handler);
        return () => document.removeEventListener("keydown", handler);
    }, [showResetConfirm, showPresets]);

    // Keep charFormats in sync with text length
    const syncFormats = useCallback((newText: string) => {
        setCharFormats((prev) => {
            if (newText.length === prev.length) return prev;
            if (newText.length > prev.length) {
                return [...prev, ...Array.from({ length: newText.length - prev.length }, () => ({ ...defaultFormat }))];
            }
            return prev.slice(0, newText.length);
        });
    }, []);

    const visibleChars = text.replace(WHITESPACE_RE, "");
    // Cap chars-per-colour so every colour stop still appears: we need at least
    // as many gradient steps as colours, i.e. charsPerColor <= chars / colours.
    // Clamp to >= 1 for short names (fewer chars than colours).
    const maxCharsPerColor = Math.max(1, Math.floor(visibleChars.length / colorStops.length));
    const numSteps = Math.ceil(visibleChars.length / charsPerColor) || 1;
    const colorsKey = colors.join(",");
    const gradientColors = useMemo(
        () => interpolateColors(colors, numSteps),
        [colorsKey, numSteps]
    );

    // Pull the slider back in when the name shrinks or colours are added.
    useEffect(() => {
        if (charsPerColor > maxCharsPerColor) setCharsPerColor(maxCharsPerColor);
    }, [maxCharsPerColor, charsPerColor]);

    const previewChars = useMemo(() => {
        const result: { char: string; color: string; fmt: CharFormat }[] = [];
        let colorIndex = 0;
        for (let i = 0; i < text.length; i++) {
            const char = text[i];
            const fmt = charFormats[i] || defaultFormat;
            if (char === " ") {
                result.push({ char: " ", color: "#ffffff", fmt });
            } else {
                const groupIndex = Math.floor(colorIndex / charsPerColor);
                result.push({
                    char,
                    color: gradientColors[Math.min(groupIndex, gradientColors.length - 1)] || colors[0],
                    fmt,
                });
                colorIndex++;
            }
        }
        return result;
    }, [text, gradientColors, charsPerColor, charFormats]);

    const output = useMemo(() => {
        let result = "";
        let colorIndex = 0;
        for (let i = 0; i < text.length; i++) {
            const char = text[i];
            const fmt = charFormats[i] || defaultFormat;
            if (char === " ") {
                result += " ";
            } else {
                const groupIndex = Math.floor(colorIndex / charsPerColor);
                const hex = gradientColors[Math.min(groupIndex, gradientColors.length - 1)] || colors[0];
                result += formatChar(char, hex, format, fmt.bold, fmt.italic, fmt.underline, fmt.strikethrough);
                colorIndex++;
            }
        }
        return result;
    }, [text, gradientColors, format, charFormats, charsPerColor]);

    // Minecraft chat caps a single message at 256 chars. The /nick command adds
    // a "/nick " prefix (6 chars), so the whole command is what must fit.
    const nickCommand = `/nick ${output}`;
    const nickTooLong = nickCommand.length > 256;

    // Get selection range as character offsets
    const getSelectionRange = useCallback((): [number, number] => {
        const el = editorRef.current;
        if (!el) return [0, 0];
        const sel = window.getSelection();
        if (!sel || sel.rangeCount === 0) return [0, 0];

        const range = sel.getRangeAt(0);

        const startRange = document.createRange();
        startRange.selectNodeContents(el);
        startRange.setEnd(range.startContainer, range.startOffset);
        const start = startRange.toString().length;

        const endRange = document.createRange();
        endRange.selectNodeContents(el);
        endRange.setEnd(range.endContainer, range.endOffset);
        const end = endRange.toString().length;

        return [start, end];
    }, []);

    const toggleFormat = (key: keyof CharFormat) => {
        const [start, end] = getSelectionRange();
        if (start === end) {
            // No selection — toggle all
            setCharFormats((prev) =>
                prev.map((f) => ({ ...f, [key]: !f[key] }))
            );
        } else {
            // Toggle selected range
            setCharFormats((prev) => {
                const allOn = prev.slice(start, end).every((f) => f[key]);
                return prev.map((f, i) =>
                    i >= start && i < end ? { ...f, [key]: !allOn } : f
                );
            });
        }
    };

    const applyPreset = (preset: typeof PRESETS[number]) => {
        setColorStops(preset.colors.map((color) => ({ color, id: nextIdRef.current++ })));
        setShowPresets(false);
    };

    const { trigger } = useWebHaptics();

    const copyToClipboard = (value: string, label: string) => {
        navigator.clipboard.writeText(value);
        captureWebToolCompleted("rgb_nickname", "copy_output", {
            format,
        });
        trigger();
        setCopied(label);
        setTimeout(() => setCopied(null), 1500);
    };

    const resetAll = () => {
        setText("CrabCraft");
        setCharFormats(Array.from({ length: 9 }, () => ({ ...defaultFormat })));
        setColorStops([
            { color: "#F97316", id: nextIdRef.current++ },
            { color: "#FB923C", id: nextIdRef.current++ },
        ]);
        setCharsPerColor(1);
    };

    const addColor = () => {
        setColorStops([...colorStops, { color: "#000000", id: nextIdRef.current++ }]);
    };

    const removeColor = (id: number) => {
        if (colorStops.length <= 1) return;
        setColorStops(colorStops.filter((s) => s.id !== id));
    };

    const updateColor = (id: number, color: string) => {
        setColorStops(colorStops.map((s) => (s.id === id ? { ...s, color } : s)));
    };

    // Reorder colour values only, keeping each row's id/DOM position fixed.
    const moveColorValue = (from: number, to: number) => {
        if (from === to) return;
        setColorStops((prev) => {
            const colors = prev.map((s) => s.color);
            const [moved] = colors.splice(from, 1);
            colors.splice(to, 0, moved);
            return prev.map((s, i) => ({ ...s, color: colors[i] }));
        });
    };

    const startColorDrag = (index: number, clientY: number) => {
        const list = colorListRef.current;
        if (!list) return;
        const rows = list.children;
        const height = rows.length > 1
            ? (rows[1] as HTMLElement).offsetTop - (rows[0] as HTMLElement).offsetTop
            : (rows[0] as HTMLElement).offsetHeight + 8;
        const count = colorStops.length;
        let currentOver = index;

        setRowHeight(height);
        setDragIndex(index);
        setOverIndex(index);
        setDragY(0);

        const onMove = (e: PointerEvent) => {
            const delta = e.clientY - clientY;
            setDragY(delta);
            const target = Math.max(0, Math.min(count - 1, index + Math.round(delta / height)));
            currentOver = target;
            setOverIndex(target);
        };
        const onUp = () => {
            window.removeEventListener("pointermove", onMove);
            window.removeEventListener("pointerup", onUp);
            setIsDropping(true);
            moveColorValue(index, currentOver);
            setDragIndex(null);
            setOverIndex(null);
            setDragY(0);
            // Re-enable transitions once the snapped-to-rest frame has painted.
            requestAnimationFrame(() => requestAnimationFrame(() => setIsDropping(false)));
        };
        window.addEventListener("pointermove", onMove);
        window.addEventListener("pointerup", onUp);
    };

    const gradientCss = `linear-gradient(90deg, ${colors.join(", ")})`;

    // Build colored HTML for the contentEditable div
    const coloredHtml = useMemo(() => {
        if (text.length === 0) return "";
        let html = "";
        let colorIndex = 0;
        for (let i = 0; i < text.length; i++) {
            const char = text[i];
            const fmt = charFormats[i] || defaultFormat;
            if (char === " ") {
                html += "&nbsp;";
            } else {
                const groupIndex = Math.floor(colorIndex / charsPerColor);
                const hex = gradientColors[Math.min(groupIndex, gradientColors.length - 1)] || colors[0];
                const styles = [
                    `color:${hex}`,
                    fmt.bold ? "font-weight:bold" : "",
                    fmt.italic ? "font-style:italic" : "",
                    fmt.underline || fmt.strikethrough
                        ? `text-decoration:${[fmt.underline ? "underline" : "", fmt.strikethrough ? "line-through" : ""].filter(Boolean).join(" ")}`
                        : "",
                ].filter(Boolean).join(";");
                const cls = '';
                html += `<span${cls} style="${styles}">${char.replace(/</g, "&lt;").replace(/>/g, "&gt;")}</span>`;
                colorIndex++;
            }
        }
        return html;
    }, [text, gradientColors, charsPerColor, charFormats]);

    // Save caret position as character offset
    const getCaretOffset = useCallback((): number => {
        const el = editorRef.current;
        if (!el) return 0;
        const sel = window.getSelection();
        if (!sel || sel.rangeCount === 0) return 0;
        const range = sel.getRangeAt(0).cloneRange();
        range.selectNodeContents(el);
        range.setEnd(sel.getRangeAt(0).endContainer, sel.getRangeAt(0).endOffset);
        return range.toString().length;
    }, []);

    // Restore caret position from character offset
    const setCaretOffset = useCallback((offset: number) => {
        const el = editorRef.current;
        if (!el) return;
        const sel = window.getSelection();
        if (!sel) return;

        let charCount = 0;
        const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
        let node: Node | null;
        while ((node = walker.nextNode())) {
            const len = node.textContent?.length || 0;
            if (charCount + len >= offset) {
                const range = document.createRange();
                range.setStart(node, offset - charCount);
                range.collapse(true);
                sel.removeAllRanges();
                sel.addRange(range);
                return;
            }
            charCount += len;
        }
        // If offset is beyond text, place at end
        const range = document.createRange();
        range.selectNodeContents(el);
        range.collapse(false);
        sel.removeAllRanges();
        sel.addRange(range);
    }, []);

    // Sync colored HTML into the editor, preserving caret
    const initialFocus = useRef(false);
    useEffect(() => {
        const el = editorRef.current;
        if (!el) return;
        const offset = getCaretOffset();
        el.innerHTML = coloredHtml || '<span class="text-gray-600">Type here...</span>';
        if (text.length > 0 && document.activeElement === el) {
            setCaretOffset(offset);
        }
        if (!initialFocus.current) {
            initialFocus.current = true;
            el.focus();
            setCaretOffset(text.length);
        }
    }, [coloredHtml, text]);

    const handleInput = () => {
        const el = editorRef.current;
        if (!el) return;
        const newText = el.textContent || "";
        if (newText.length > 64) {
            setText(newText.slice(0, 64));
            syncFormats(newText.slice(0, 64));
        } else {
            setText(newText);
            syncFormats(newText);
        }
    };

    const handleKeyDown = (e: React.KeyboardEvent) => {
        if (text.length >= 64 && !e.metaKey && !e.ctrlKey && e.key.length === 1) {
            const sel = window.getSelection();
            if (sel && sel.rangeCount > 0 && sel.getRangeAt(0).collapsed) {
                e.preventDefault();
            }
        }
    };

    const handlePaste = (e: React.ClipboardEvent) => {
        e.preventDefault();
        const plain = e.clipboardData.getData("text/plain");
        const currentLen = text.length;
        const sel = window.getSelection();
        const selectedLen = sel && sel.rangeCount > 0 ? sel.getRangeAt(0).toString().length : 0;
        const remaining = 64 - (currentLen - selectedLen);
        if (remaining <= 0) return;
        document.execCommand("insertText", false, plain.slice(0, remaining));
    };

    return (
        <div className="pt-24 pb-16">
            <div className="container mx-auto px-4 max-w-6xl">
                <div className="text-center mb-10 animate-in">
                    <h1 className="text-4xl lg:text-5xl font-bold text-orange-500 font-mc">RGB Nickname</h1>
                    <p className="mt-2 text-gray-600 dark:text-gray-400">Create gradient text for your Minecraft nickname</p>
                </div>

                {/* Editable preview */}
                <Squircle cornerRadius={32} className="px-6 py-4 bg-paper-2 shadow-sm mb-8 animate-in" style={{ animationDelay: "0.1s" }}>
                    <div className="flex items-center justify-between mb-2">
                        <p className="text-xs text-gray-500 uppercase tracking-wider">Click to edit</p>
                        <div className="flex gap-1 items-center">
                            {[
                                { label: "B", name: "Bold", key: "bold" as keyof CharFormat, style: "font-bold" },
                                { label: "I", name: "Italic", key: "italic" as keyof CharFormat, style: "italic" },
                                { label: "U", name: "Underline", key: "underline" as keyof CharFormat, style: "underline" },
                                { label: "S", name: "Strikethrough", key: "strikethrough" as keyof CharFormat, style: "line-through" },
                            ].map((btn) => (
                                <button
                                    key={btn.label}
                                    aria-label={btn.name}
                                    onMouseDown={(e) => { e.preventDefault(); toggleFormat(btn.key); }}
                                    className={`w-7 h-7 rounded flex items-center justify-center text-xs cursor-pointer transition-all ${btn.style} bg-gray-200 dark:bg-white/10 text-gray-600 dark:text-gray-400 hover:bg-gray-300 dark:hover:bg-white/20 hover:text-gray-900 dark:hover:text-white`}
                                >
                                    {btn.label}
                                </button>
                            ))}
                            <button
                                onClick={() => setShowResetConfirm(true)}
                                className="h-7 px-2 rounded flex items-center justify-center text-[10px] font-bold cursor-pointer transition-all bg-gray-200 dark:bg-white/10 text-gray-500 dark:text-gray-400 hover:bg-red-500/20 hover:text-red-400 ml-2"
                            >
                                RESET
                            </button>
                        </div>
                    </div>
                    <div
                        ref={editorRef}
                        contentEditable
                        suppressContentEditableWarning
                        spellCheck={false}
                        autoCorrect="off"
                        autoCapitalize="off"
                        role="textbox"
                        aria-label="Nickname text"
                        aria-multiline="false"
                        onInput={handleInput}
                        onKeyDown={handleKeyDown}
                        onPaste={handlePaste}
                        className="w-full font-mc text-3xl lg:text-4xl text-center focus:outline-none min-h-[2.5rem] cursor-text"
                        style={{ caretColor: "#f97316", lineHeight: "1", textShadow: "0 2px 8px rgba(0,0,0,0.15)" }}
                    />
                    {/* Gradient bar */}
                    <div
                        className="mt-1 h-1.5 rounded-full"
                        style={{ background: gradientCss }}
                    />
                </Squircle>

                <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
                    {/* Left: Colors */}
                    <div className="space-y-6">
                        {/* Colors */}
                        <Squircle cornerRadius={32} className="p-6 bg-paper-2 shadow-sm animate-in" style={{ animationDelay: "0.2s" }}>
                            <label className="block text-sm font-bold text-gray-700 dark:text-gray-300 mb-3">Gradient Colours</label>
                            <div ref={colorListRef} className="flex flex-col gap-2">
                                {colorStops.map((stop, index) => {
                                    const isDragging = dragIndex === index;

                                    // Rows the dragged item passes over slide by one row to open a gap.
                                    let shift = 0;
                                    if (dragIndex !== null && overIndex !== null && !isDragging) {
                                        if (index > dragIndex && index <= overIndex) shift = -rowHeight;
                                        else if (index < dragIndex && index >= overIndex) shift = rowHeight;
                                    }

                                    return (
                                        <div
                                            key={stop.id}
                                            style={{
                                                transform: `translateY(${isDragging ? dragY : shift}px)`,
                                                transition: isDragging || isDropping ? "none" : "transform 200ms ease",
                                                zIndex: isDragging ? 10 : undefined,
                                                position: "relative",
                                            }}
                                            className={`flex items-center gap-1.5 rounded-lg ${isDragging ? "shadow-lg" : ""}`}
                                        >
                                            <button
                                                type="button"
                                                aria-label="Drag to reorder colour"
                                                onPointerDown={(e) => { e.preventDefault(); startColorDrag(index, e.clientY); }}
                                                className="w-5 h-8 flex items-center justify-center flex-shrink-0 cursor-grab active:cursor-grabbing touch-none text-gray-400 dark:text-gray-500 hover:text-gray-600 dark:hover:text-gray-300 transition-colours"
                                            >
                                                <GripVertical className="w-4 h-4" />
                                            </button>
                                            <div className="flex-1">
                                                <SwatchColorPicker
                                                    color={stop.color}
                                                    onChange={(c) => updateColor(stop.id, c)}
                                                />
                                            </div>
                                            {colorStops.length > 1 && (
                                                <button
                                                    aria-label="Remove color"
                                                    onClick={() => removeColor(stop.id)}
                                                    className="w-8 h-8 flex-shrink-0 bg-red-500 hover:bg-red-600 text-white rounded-lg text-[10px] flex items-center justify-center cursor-pointer transition-colours"
                                                >
                                                    ✕
                                                </button>
                                            )}
                                        </div>
                                    );
                                })}
                            </div>
                            <button
                                onClick={addColor}
                                className="w-full py-2 rounded-xl border-2 border-dashed border-line hover:border-orange-400 text-gray-400 hover:text-orange-500 flex items-center justify-center text-sm transition-colours cursor-pointer mt-2"
                            >
                                + Add Colour
                            </button>
                        </Squircle>

                        {/* Presets button */}
                        <button
                            onClick={() => setShowPresets(true)}
                            className="rounded-3xl p-6 bg-paper-2 animate-in w-full text-left cursor-pointer hover:scale-[1.02] transition-transform"
                            style={{ animationDelay: "0.25s" }}
                        >
                            <label className="block text-sm font-bold text-gray-700 dark:text-gray-300 mb-2 pointer-events-none">Presets</label>
                            <div
                                className="h-3 rounded-full"
                                style={{ background: `linear-gradient(90deg, ${colors.join(", ")})` }}
                            />
                            <p className="text-xs text-gray-400 dark:text-gray-500 mt-2">Click to browse presets</p>
                        </button>
                    </div>

                    {/* Right: Settings + Output */}
                    <div className="lg:col-span-2 space-y-6">
                        {/* Chars per color */}
                        <Squircle cornerRadius={32} className="p-6 bg-paper-2 shadow-sm animate-in" style={{ animationDelay: "0.15s" }}>
                            <label htmlFor="chars-per-colour" className="block text-sm font-bold text-gray-700 dark:text-gray-300 mb-2">
                                Characters per colour: {charsPerColor}
                            </label>
                            <input
                                id="chars-per-colour"
                                type="range"
                                min={1}
                                max={maxCharsPerColor}
                                value={charsPerColor}
                                disabled={maxCharsPerColor <= 1}
                                aria-label="Characters per colour"
                                aria-valuetext={`${charsPerColor} character${charsPerColor === 1 ? "" : "s"} per colour`}
                                onChange={(e) => setCharsPerColor(Number(e.target.value))}
                                className="w-full cursor-pointer disabled:opacity-40 disabled:cursor-not-allowed"
                            />
                            {maxCharsPerColor > 1 && (
                                <div className="flex justify-between text-xs text-gray-400 mt-1.5">
                                    <span>Smooth</span>
                                    <span>Blocky</span>
                                </div>
                            )}
                        </Squircle>

                        {/* Output */}
                        <Squircle cornerRadius={32} className="p-6 bg-paper-2 shadow-sm animate-in" style={{ animationDelay: "0.2s" }}>
                            <div className="flex items-center justify-between mb-2">
                                <label htmlFor="rgb-output" className="text-sm font-bold text-gray-700 dark:text-gray-300">Output</label>
                                <span
                                    aria-hidden={copied !== "output"}
                                    className={`text-xs font-bold text-orange-500 transition-opacity duration-200 ${copied === "output" ? "opacity-100" : "opacity-0"}`}
                                >
                                    Copied!
                                </span>
                            </div>
                            <textarea
                                id="rgb-output"
                                readOnly
                                value={output}
                                rows={4}
                                aria-label="Generated nickname output, click to copy"
                                onClick={() => copyToClipboard(output, "output")}
                                className="w-full px-4 py-3 rounded-xl border border-line bg-paper text-sm font-mono resize-none focus:outline-none cursor-pointer"
                            />
                            <button
                                onClick={() => copyToClipboard(nickCommand, "nick")}
                                className="w-full mt-3 bg-orange-500 hover:bg-orange-600 text-white font-bold py-3 px-4 rounded-xl transition-colors cursor-pointer active:scale-95"
                            >
                                {copied === "nick" ? "Copied!" : "Copy /nick"}
                            </button>
                            {nickTooLong && (
                                <p className="text-xs text-red-500 mt-2 flex items-start gap-1">
                                    <span aria-hidden>⚠</span>
                                    <span>This /nick command is {nickCommand.length} characters. Minecraft chat has a 256-character limit, so it may be too long to run.</span>
                                </p>
                            )}
                        </Squircle>
                    </div>
                </div>

                {/* MC Chat Preview */}
                <Squircle
                    cornerRadius={32}
                    className="mt-8 animate-in overflow-hidden relative"
                    style={{ animationDelay: "0.3s" }}
                >
                    <div
                        className="w-full h-full min-h-[200px] bg-cover bg-center relative flex flex-col justify-end p-4"
                        style={{ backgroundImage: "url('/chat-background.webp')" }}
                    >
                        <div className="bg-black/50 px-3 py-3 space-y-1" style={{ width: "80%", height: "80%", position: "absolute", bottom: 0, left: 0 }}>
                            <div className="font-mc text-lg"><span className="text-gray-400">Steve</span><span className="text-white">: anyone online?</span></div>
                            <div className="font-mc text-lg"><span className="text-gray-400">Alex</span><span className="text-white">: yeah I just got on</span></div>
                            <div className="font-mc text-lg"><span className="text-gray-400">Herobrine</span><span className="text-white">: nice name btw!</span></div>
                            <div className="font-mc text-lg">
                                {previewChars.map((c, i) => (
                                    <span key={`chat-${i}`} style={{
                                        color: c.color,
                                        fontWeight: c.fmt.bold ? "bold" : undefined,
                                        fontStyle: c.fmt.italic ? "italic" : undefined,
                                        textDecoration: [c.fmt.underline ? "underline" : "", c.fmt.strikethrough ? "line-through" : ""].filter(Boolean).join(" ") || undefined,
                                    }}>
                                        {c.char === " " ? "\u00a0" : c.char}
                                    </span>
                                ))}
                                <span className="text-white">: Look at my awesome nickname</span>
                            </div>
                        </div>
                    </div>
                </Squircle>
            </div>

            {/* Presets Modal */}
            {showPresets && (
                <div
                    className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-[fadeIn_0.15s_ease-out]"
                    onClick={() => setShowPresets(false)}
                >
                    <div
                        className="bg-paper-2 rounded-2xl p-6 max-w-lg w-full shadow-2xl animate-[scaleIn_0.2s_ease-out]"
                        onClick={(e) => e.stopPropagation()}
                    >
                        <div className="flex items-center justify-between mb-4">
                            <h2 className="text-lg font-bold text-gray-800 dark:text-gray-100">Gradient Presets</h2>
                            <button
                                onClick={() => setShowPresets(false)}
                                className="w-8 h-8 rounded-lg bg-gray-100 hover:bg-gray-200 flex items-center justify-center text-gray-500 cursor-pointer transition-colors"
                            >
                                ✕
                            </button>
                        </div>
                        <div className="grid grid-cols-2 gap-3">
                            {PRESETS.map((preset) => (
                                <button
                                    key={preset.name}
                                    onClick={() => applyPreset(preset)}
                                    className="rounded-xl p-3 text-left hover:bg-paper transition-colors cursor-pointer border border-line hover:border-orange-300"
                                >
                                    <div
                                        className="h-6 rounded-lg mb-2"
                                        style={{ background: `linear-gradient(90deg, ${preset.colors.join(", ")})` }}
                                    />
                                    <p className="text-sm font-medium text-gray-700 dark:text-gray-300">{preset.name}</p>
                                </button>
                            ))}
                        </div>
                    </div>
                </div>
            )}

            {/* Reset Confirmation Modal */}
            {showResetConfirm && (
                <div
                    className="fixed inset-0 bg-black/50 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-[fadeIn_0.15s_ease-out]"
                    onClick={() => setShowResetConfirm(false)}
                >
                    <div
                        className="bg-paper-2 rounded-2xl p-6 max-w-sm w-full shadow-2xl animate-[scaleIn_0.2s_ease-out] text-center"
                        onClick={(e) => e.stopPropagation()}
                    >
                        <h2 className="text-lg font-bold text-gray-800 dark:text-gray-100 mb-2">Reset everything?</h2>
                        <p className="text-sm text-gray-500 mb-6">Are you sure you'd like to reset all settings back to default?</p>
                        <div className="flex gap-3">
                            <button
                                onClick={() => setShowResetConfirm(false)}
                                className="flex-1 py-2.5 rounded-xl bg-paper hover:bg-line text-gray-700 dark:text-gray-300 font-bold text-sm cursor-pointer transition-colors"
                            >
                                Cancel
                            </button>
                            <button
                                onClick={() => { resetAll(); setShowResetConfirm(false); }}
                                className="flex-1 py-2.5 rounded-xl bg-red-500 hover:bg-red-600 text-white font-bold text-sm cursor-pointer transition-colors"
                            >
                                Reset
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}
