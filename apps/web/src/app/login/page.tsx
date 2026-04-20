"use client";

import { useEffect } from "react";
import { signIn } from "next-auth/react";

export default function LoginPage() {
  useEffect(() => {
    signIn("discord");
  }, []);

  return (
    <div className="flex-1 flex items-center justify-center">
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img src="/clock.gif" alt="Redirecting..." width={48} height={48} className="pixelated" />
    </div>
  );
}
