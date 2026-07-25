interface Props {
  current: number;
  total: number;
}

export default function SlideCounter({ current, total }: Props) {
  return (
    <span className="font-mc text-xs tabular-nums tracking-wider dark:text-white/70 text-stone-600">
      {String(current + 1).padStart(2, "0")} / {String(total).padStart(2, "0")}
    </span>
  );
}
