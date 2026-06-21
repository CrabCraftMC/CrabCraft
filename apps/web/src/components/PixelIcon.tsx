import type { CSSProperties, ReactEventHandler } from "react";

interface PixelIconProps {
  src: string;
  alt?: string;
  size?: number;
  className?: string;
  imgClassName?: string;
  style?: CSSProperties;
  onError?: ReactEventHandler<HTMLImageElement>;
}

const DISPLAY_CLASS_RE = /\b(hidden|block|inline-block|flex|inline-flex|grid|inline-grid)\b/;

export default function PixelIcon({
  src,
  alt = "",
  size = 32,
  className = "",
  imgClassName = "",
  style,
  onError,
}: PixelIconProps) {
  const displayClass = DISPLAY_CLASS_RE.test(className) ? "" : "inline-flex";

  return (
    <span
      className={`${displayClass} shrink-0 items-center justify-center ${className}`.trim()}
      style={{ width: size, height: size, minWidth: size, minHeight: size, ...style }}
    >
      <img
        src={src}
        alt={alt}
        width={16}
        height={16}
        className={`pixelated block ${imgClassName}`.trim()}
        style={{ width: size, height: size }}
        draggable={false}
        onError={onError}
      />
    </span>
  );
}
