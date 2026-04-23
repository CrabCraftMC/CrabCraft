"use client";

import { useEffect } from "react";
import { signIn } from "next-auth/react";
import { useSearchParams } from "next/navigation";

export default function LoginPage() {
  const searchParams = useSearchParams();
  const callbackUrl = searchParams.get("callbackUrl") || "/";

  useEffect(() => {
    signIn("discord", { callbackUrl });
  }, [callbackUrl]);

  return (
    <div className="flex-1 flex items-center justify-center">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src="/clock.gif" alt="Redirecting..." width={48} height={48} className="pixelated" />
    </div>
  );
}
