interface Props {
  current: number;
  total: number;
}

export default function SlideCounter({ current, total }: Props) {
  return (
    <span className="font-mc text-xs tabular-nums tracking-wider text-white/70">
      {String(current + 1).padStart(2, "0")} / {String(total).padStart(2, "0")}
    </span>
  );
}
