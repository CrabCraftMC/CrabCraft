"use client";

import { useEffect, useRef, useState, forwardRef, useImperativeHandle } from "react";

interface ColorPickerProps {
    color: string;
    onChange: (color: string) => void;
}

export interface ColorPickerHandle {
    show: () => void;
}

const ColorPicker = forwardRef<ColorPickerHandle, ColorPickerProps>(
    function ColorPicker({ color, onChange }, ref) {
        const buttonRef = useRef<HTMLDivElement>(null);
        const pickrRef = useRef<any>(null);
        const onChangeRef = useRef(onChange);
        const [ready, setReady] = useState(false);

        useImperativeHandle(ref, () => ({
            show: () => pickrRef.current?.show(),
        }));

        // Keep onChange ref up to date
        useEffect(() => {
            onChangeRef.current = onChange;
        }, [onChange]);

        useEffect(() => {
            if (!buttonRef.current || pickrRef.current) return;

            let destroyed = false;

            (async () => {
                const [{ default: Pickr }] = await Promise.all([
                    import("@simonwep/pickr"),
                    import("@simonwep/pickr/dist/themes/classic.min.css"),
                ]);

                if (destroyed || !buttonRef.current || !buttonRef.current.isConnected) return;

                const pickr = Pickr.create({
                    el: buttonRef.current,
                    theme: "classic",
                    default: color,
                    components: {
                        preview: true,
                        opacity: false,
                        hue: true,
                        interaction: {
                            input: true,
                        },
                    },
                });

                pickr.on("change", (colorObj: any) => {
                    if (colorObj) {
                        onChangeRef.current(colorObj.toHEXA().toString().slice(0, 7));
                        pickr.applyColor(true);
                    }
                });

                pickrRef.current = pickr;
                setReady(true);
            })();

            return () => {
                destroyed = true;
                if (pickrRef.current) {
                    pickrRef.current.destroyAndRemove();
                    pickrRef.current = null;
                }
            };
        }, []);

        useEffect(() => {
            if (pickrRef.current && ready) {
                pickrRef.current.setColor(color, true);
            }
        }, [color, ready]);

        return <div ref={buttonRef} />;
    }
);

export default ColorPicker;
