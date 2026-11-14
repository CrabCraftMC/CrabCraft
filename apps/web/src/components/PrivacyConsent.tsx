"use client";

import Link from "next/link";
import Script from "next/script";
import { usePathname } from "next/navigation";
import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import {
  effectivePrivacyPreferences, ESSENTIAL_ONLY, hasRevokedPermission,
  parsePrivacyConsent, PRIVACY_CONSENT_KEY, storePrivacyConsent, type PrivacyPreferences,
} from "@/lib/privacyConsent";

type PrivacyContextValue = {
  preferences: PrivacyPreferences;
  ready: boolean;
  openSettings: () => void;
  rejectOptional: () => void;
};

const PrivacyContext = createContext<PrivacyContextValue | null>(null);
const AD_PAGE = "/tools/block-gradient";
const choiceButton = "min-h-11 flex-1 cursor-pointer rounded-xl border border-gray-500 bg-paper px-4 py-2 text-sm font-semibold text-foreground transition-colors hover:border-orange-500 hover:bg-orange-500/10 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-500 disabled:cursor-not-allowed disabled:opacity-50";

export function usePrivacyConsent() {
  const context = useContext(PrivacyContext);
  if (!context) throw new Error("PrivacyConsentProvider is required");
  return context;
}

export default function PrivacyConsentProvider({ children }: {
  children: ReactNode;
}) {
  const [preferences, setPreferences] = useState<PrivacyPreferences>(ESSENTIAL_ONLY);
  const [ready, setReady] = useState(false);
  const [visible, setVisible] = useState(false);
  const [managing, setManaging] = useState(false);
  const [draft, setDraft] = useState<PrivacyPreferences>(ESSENTIAL_ONLY);
  const [storageUnavailable, setStorageUnavailable] = useState(false);
  const panel = useRef<HTMLElement>(null);
  const preferencesRef = useRef(preferences);
  const returningFocus = useRef<HTMLElement | null>(null);
  const denialInUrl = useRef(false);
  const pathname = usePathname();

  useEffect(() => {
    function restore() {
      const urlDenial = new URLSearchParams(window.location.search).get("privacy") === "essential";
      denialInUrl.current = urlDenial;
      let consent = null;
      try { consent = parsePrivacyConsent(localStorage.getItem(PRIVACY_CONSENT_KEY)); } catch { /* Storage may be disabled. */ }
      if (urlDenial && (!consent || consent.analytics || consent.advertising)) {
        try { storePrivacyConsent(localStorage, ESSENTIAL_ONLY); } catch { /* The URL preserves denial if storage is unavailable. */ }
      }
      const next = urlDenial ? ESSENTIAL_ONLY : effectivePrivacyPreferences(consent);
      const revoked = hasRevokedPermission(preferencesRef.current, next);
      preferencesRef.current = next;
      setPreferences(next);
      setDraft(next);
      setVisible(!urlDenial && (!consent || (consent.advertising && !next.advertising)));
      setReady(true);
      // Removing a script element does not stop its existing listeners or requests.
      if (revoked) window.location.reload();
    }
    restore();
    const onStorage = (event: StorageEvent) => {
      if (event.key === PRIVACY_CONSENT_KEY || event.key === null) restore();
    };
    window.addEventListener("storage", onStorage);
    window.addEventListener("focus", restore);
    window.addEventListener("pageshow", restore);
    return () => {
      window.removeEventListener("storage", onStorage);
      window.removeEventListener("focus", restore);
      window.removeEventListener("pageshow", restore);
    };
  }, []);

  useEffect(() => {
    // A full document navigation applies the page-specific CSP and terminates
    // Google scripts when leaving the only page that can display adverts.
    const documentPath = window.location.pathname;
    function navigate(event: MouseEvent) {
      if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const link = event.target instanceof Element ? event.target.closest("a[href]") : null;
      if (!(link instanceof HTMLAnchorElement) || link.download || (link.target && link.target !== "_self")) return;
      const url = new URL(link.href, window.location.href);
      if (url.origin !== window.location.origin) return;
      if (denialInUrl.current) url.searchParams.set("privacy", "essential");
      if (!denialInUrl.current && (window.location.pathname === AD_PAGE) === (url.pathname === AD_PAGE)) return;
      event.preventDefault();
      event.stopPropagation();
      window.location.assign(url.href);
    }
    const onPopState = () => {
      if ((documentPath === AD_PAGE) !== (window.location.pathname === AD_PAGE)) window.location.reload();
    };
    document.addEventListener("click", navigate, true);
    window.addEventListener("popstate", onPopState);
    return () => {
      document.removeEventListener("click", navigate, true);
      window.removeEventListener("popstate", onPopState);
    };
  }, []);

  const initialPathname = useRef(pathname);
  useEffect(() => {
    if (denialInUrl.current && new URLSearchParams(window.location.search).get("privacy") !== "essential") {
      const url = new URL(window.location.href);
      url.searchParams.set("privacy", "essential");
      window.location.replace(url.href);
      return;
    }
    if ((initialPathname.current === AD_PAGE) !== (pathname === AD_PAGE)) window.location.reload();
  }, [pathname]);

  const save = useCallback((requested: PrivacyPreferences) => {
    const next = requested;
    const revoked = hasRevokedPermission(preferencesRef.current, next);
    let persistence: "saved" | "cleared" | "unavailable" = "unavailable";
    try { persistence = storePrivacyConsent(localStorage, next); } catch { /* Access to storage itself may throw. */ }
    setStorageUnavailable(persistence !== "saved");
    if (persistence === "saved") {
      denialInUrl.current = false;
      const url = new URL(window.location.href);
      if (url.searchParams.get("privacy") === "essential") {
        url.searchParams.delete("privacy");
        window.history.replaceState(window.history.state, "", url.href);
      }
    }
    preferencesRef.current = next;
    setPreferences(next);
    setDraft(next);
    setVisible(false);
    returningFocus.current?.focus();
    if (revoked) {
      if (persistence !== "saved") {
        const url = new URL(window.location.href);
        url.searchParams.set("privacy", "essential");
        window.location.replace(url.href);
      } else window.location.reload();
    }
  }, []);

  const openSettings = useCallback(() => {
    returningFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setDraft(preferencesRef.current);
    setManaging(false);
    setVisible(true);
    requestAnimationFrame(() => panel.current?.focus());
  }, []);

  const rejectOptional = useCallback(() => save(ESSENTIAL_ONLY), [save]);

  return (
    <PrivacyContext.Provider value={{ preferences, ready, openSettings, rejectOptional }}>
      {children}
      {ready && preferences.analytics && process.env.NODE_ENV === "production" && (
        <Script
          id="crabcraft-analytics"
          src="https://web.maxmoon.sh/script.js"
          strategy="lazyOnload"
          data-website-id="b47dfe1d-3ed3-49d8-948a-96776107f338"
          data-domains="crabcraft.net,www.crabcraft.net"
          data-performance="true"
        />
      )}
      {ready && visible && (
        <section
          ref={panel}
          tabIndex={-1}
          aria-labelledby="privacy-heading"
          onKeyDown={(event) => {
            if (event.key === "Escape" && (managing || returningFocus.current)) {
              setVisible(false);
              returningFocus.current?.focus();
            }
          }}
          className="fixed bottom-4 left-4 z-[90] max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-[360px] overflow-y-auto rounded-2xl border border-line bg-paper-2 p-4 text-foreground shadow-xl focus:outline-none"
        >
          <div className="flex items-start justify-between gap-4">
            <h2 id="privacy-heading" className="text-sm font-bold">Cookies &amp; privacy</h2>
            {(managing || returningFocus.current) && <button type="button" aria-label="Close privacy settings" className="-mr-2 -mt-2 flex size-8 shrink-0 cursor-pointer items-center justify-center rounded-lg text-xl hover:bg-orange-500/10" onClick={() => {
              setVisible(false);
              returningFocus.current?.focus();
            }}>×</button>}
          </div>
          <p className="mt-2 text-xs leading-5 text-gray-700 dark:text-gray-300">
            Allow personalised Google ads?
          </p>
          {managing && (
            <fieldset className="mt-4 space-y-3 border-y border-line py-4">
              <legend className="sr-only">Privacy choices</legend>
              <label className="flex cursor-pointer items-start gap-3 text-xs leading-5">
                <input type="checkbox" checked={draft.analytics} onChange={(event) => setDraft({ ...draft, analytics: event.target.checked })} className="mt-1 size-4 shrink-0 accent-orange-600" />
                <span><strong className="block">Cookie-free analytics</strong>On by default to help us understand visits, tool usage and performance. Switch off to opt out.</span>
              </label>
              <label className="flex cursor-pointer items-start gap-3 text-xs leading-5">
                <input type="checkbox" checked={draft.advertising} onChange={(event) => setDraft({ ...draft, advertising: event.target.checked })} className="mt-1 size-4 shrink-0 accent-orange-600" />
                <span><strong className="block">Advertising</strong>Allow Google advertising services on the block gradient page when signed out. Google may ask for detailed choices before showing ads.</span>
              </label>
            </fieldset>
          )}
          <div className="mt-3 flex gap-2">
            <button type="button" className="min-h-9 flex-1 cursor-pointer rounded-lg border border-orange-500 bg-orange-500 px-3 py-1.5 text-xs font-bold text-white hover:bg-orange-400 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-500" onClick={() => save({ ...preferencesRef.current, advertising: true })}>Accept</button>
            <button type="button" className="min-h-9 flex-1 cursor-pointer rounded-lg bg-gray-800 px-3 py-1.5 text-xs font-bold text-white hover:bg-gray-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-orange-500" onClick={() => save({ ...preferencesRef.current, advertising: false })}>Deny</button>
          </div>
          {managing ? (
            <button type="button" className={`${choiceButton} mt-3 w-full`} onClick={() => save(draft)}>Save choices</button>
          ) : null}
          {managing && preferences.advertising && document.documentElement.dataset.crabcraftAdsLoaded === "true" && (
            <button type="button" className="mt-3 min-h-11 w-full cursor-pointer text-xs underline underline-offset-4" onClick={() => {
              setVisible(false);
              window.dispatchEvent(new Event("crabcraft:google-privacy-settings"));
            }}>Google advertising choices</button>
          )}
          <div className="mt-2 flex items-center gap-4 text-xs text-gray-600 dark:text-gray-400">
            <button type="button" className="min-h-7 cursor-pointer underline underline-offset-4 focus-visible:outline-2 focus-visible:outline-orange-500" onClick={() => setManaging(!managing)}>{managing ? "Fewer options" : "Settings"}</button>
            <Link href="/privacy" className="flex min-h-7 items-center underline underline-offset-4">Privacy policy</Link>
          </div>
        </section>
      )}
      {storageUnavailable && !visible && <p role="status" className="fixed bottom-4 left-4 z-[90] max-w-sm rounded-xl border border-line bg-paper-2 p-4 text-xs">Your browser could not save your choices. They apply to this page visit.</p>}
    </PrivacyContext.Provider>
  );
}

export function PrivacySettingsButton({ children = "Privacy and cookie settings", className, optOut = false }: {
  children?: ReactNode;
  className?: string;
  optOut?: boolean;
}) {
  const { openSettings, rejectOptional, ready } = usePrivacyConsent();
  const [confirmed, setConfirmed] = useState(false);
  return <>
    <button type="button" className={className} disabled={!ready} onClick={() => {
      if (optOut) { rejectOptional(); setConfirmed(true); } else openSettings();
    }}>{children}</button>
    {confirmed && <span role="status" className="mt-2 block text-xs">Optional analytics and advertising are off.</span>}
  </>;
}
