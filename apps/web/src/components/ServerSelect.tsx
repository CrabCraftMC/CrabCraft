"use client";

import { useRouter, usePathname } from "next/navigation";
import Squircle from "@/components/Squircle";

const AGGREGATE = "__aggregate__";

interface Props {
  servers: string[];
  current: string;
  basePath?: string;
}

export default function ServerSelect({ servers, current, basePath }: Props) {
  const router = useRouter();
  const pathname = usePathname();
  const target = basePath ?? pathname;

  const options: { id: string; label: string }[] = [
    { id: AGGREGATE, label: "All servers" },
    ...servers.map((s) => ({ id: s, label: s })),
  ];

  return (
    <div className="flex gap-2 flex-wrap">
      {options.map((opt) => (
        <Squircle
          key={opt.id}
          cornerRadius={12}
          onClick={() => {
            const url =
              opt.id === AGGREGATE
                ? target
                : `${target}?server=${encodeURIComponent(opt.id)}`;
            router.push(url);
          }}
          className={`px-3 py-1.5 text-xs font-bold whitespace-nowrap transition-colors cursor-pointer ${
            current === opt.id
              ? "bg-orange-500 text-white"
              : "bg-gray-200 dark:bg-[#2a221b] text-gray-600 dark:text-gray-400 hover:bg-gray-300 dark:hover:bg-[#3d3028]"
          }`}
        >
          {opt.label}
        </Squircle>
      ))}
    </div>
  );
}
