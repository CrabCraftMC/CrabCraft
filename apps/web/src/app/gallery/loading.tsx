export default function GalleryLoading() {
  return (
    <div
      role="status"
      aria-label="Loading Gallery"
      className="min-h-screen pb-16 pt-24"
    >
      <div className="container mx-auto max-w-7xl px-4">
        <div className="mx-auto mb-10 h-24 max-w-2xl animate-pulse rounded-3xl bg-paper-2 motion-reduce:animate-none" />
        <div className="mb-8 h-48 animate-pulse rounded-[28px] bg-paper-2 motion-reduce:animate-none" />
        <div className="grid grid-cols-1 gap-6 md:grid-cols-2 xl:grid-cols-3">
          {Array.from({ length: 6 }, (_, index) => (
            <div
              key={index}
              className="h-96 animate-pulse rounded-[2rem] bg-paper-2 motion-reduce:animate-none"
            />
          ))}
        </div>
      </div>
    </div>
  );
}
