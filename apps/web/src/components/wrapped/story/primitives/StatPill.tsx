import Squircle from "@/components/Squircle";

interface Props {
  label: string;
  value: string;
  highlight?: boolean;
  className?: string;
}

export default function StatPill({ label, value, highlight, className }: Props) {
  return (
    <Squircle
      cornerRadius={16}
      className={`${
        highlight ? "bg-orange-500/20" : "dark:bg-white/10 bg-black/10"
      } p-4 backdrop-blur-sm ${className ?? ""}`}
    >
      <p className="font-mc text-xl font-bold tabular-nums dark:text-stone-100 text-stone-800 sm:text-2xl">
        {value}
      </p>
      <p className="mt-1 text-[10px] uppercase tracking-widest dark:text-white/60 text-stone-600">
        {label}
      </p>
    </Squircle>
  );
}
