interface Props {
  label: string;
  value: string;
  highlight?: boolean;
  className?: string;
}

export default function StatPill({ label, value, highlight, className }: Props) {
  return (
    <div
      className={`rounded-2xl border ${
        highlight
          ? "border-orange-400/40 bg-orange-500/10"
          : "border-white/10 bg-white/5"
      } p-4 backdrop-blur-sm ${className ?? ""}`}
    >
      <p className="font-mc text-xl font-bold tabular-nums text-white sm:text-2xl">
        {value}
      </p>
      <p className="mt-1 text-[10px] uppercase tracking-widest text-white/60">
        {label}
      </p>
    </div>
  );
}
