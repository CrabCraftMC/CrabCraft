export default function Loading() {
  return (
    <div className="flex-1 flex items-center justify-center">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src="/clock.gif" alt="Loading..." width={48} height={48} className="pixelated" />
    </div>
  );
}
