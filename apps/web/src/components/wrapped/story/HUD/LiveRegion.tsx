"use client";

import { useEffect, useState } from "react";

interface Props {
  message: string;
  /** Delay before announcing, in ms. Lets the scene mount first. */
  delay?: number;
}

export default function LiveRegion({ message, delay = 200 }: Props) {
  const [text, setText] = useState("");

  useEffect(() => {
    setText("");
    const handle = window.setTimeout(() => setText(message), delay);
    return () => window.clearTimeout(handle);
  }, [message, delay]);

  return (
    <div aria-live="polite" aria-atomic="true" className="sr-only">
      {text}
    </div>
  );
}
