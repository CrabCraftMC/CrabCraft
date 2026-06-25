import PixelIcon from "@/components/PixelIcon";

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
    <PixelIcon
      src={`/minecraft/${src}`}
      alt={alt}
      size={size}
      className={className}
    />
  );
}
