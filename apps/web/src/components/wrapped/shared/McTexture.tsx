interface McTextureProps {
  src: string;
  size?: number;
  alt: string;
  className?: string;
}

export default function McTexture({
  src,
  size = 24,
  alt,
  className,
}: McTextureProps) {
  return (
    <img
      src={`/minecraft/${src}`}
      alt={alt}
      width={size}
      height={size}
      className={`pixelated ${className ?? ""}`}
      draggable={false}
    />
  );
}
