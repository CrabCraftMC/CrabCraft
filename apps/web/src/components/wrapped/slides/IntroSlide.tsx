"use client";

import { useState, useEffect } from "react";
import NextImage from "next/image";
import type { WrappedData } from "../WrappedContainer";

export default function IntroSlide({ data }: { data: WrappedData }) {
    const [awake, setAwake] = useState(false);
    const seasonLabel = `Season ${data.season}`;
    const sleepingUrl = `https://starlightskins.lunareclipse.studio/render/sleeping/${data.playerName}/full`;
    const defaultUrl = `https://starlightskins.lunareclipse.studio/render/default/${data.playerName}/full`;

    useEffect(() => {
        const img = new Image();
        img.src = defaultUrl;
    }, [defaultUrl]);

    return (
        <div className="flex flex-col lg:flex-row items-center lg:items-end gap-6 lg:gap-10">
            <NextImage
                src={awake ? defaultUrl : sleepingUrl}
                alt={data.playerName}
                width={120}
                height={240}
                className="h-[180px] lg:h-[240px] w-auto cursor-pointer transition-transform duration-300"
                style={{ transform: awake ? 'scale(1.05)' : 'scale(1)' }}
                onMouseEnter={() => setAwake(true)}
            />
            <div className="text-center lg:text-left">
                <p className="text-xs text-gray-500 dark:text-gray-400 uppercase tracking-widest mb-1">CrabCraft Wrapped</p>
                <h1 className="text-4xl lg:text-6xl font-bold text-orange-500 font-mc">{data.playerName}</h1>
                <p className="text-lg text-gray-600 dark:text-gray-400 mt-2">{seasonLabel}</p>
            </div>
        </div>
    );
}
