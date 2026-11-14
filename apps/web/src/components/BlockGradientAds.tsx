"use client";

import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";
import { usePrivacyConsent } from "@/components/PrivacyConsent";
import {
  getGoogleAdMode,
  type AdSenseConfig,
  type GoogleAdMode,
  type TcfConsentData,
} from "@/lib/adsense";
import styles from "./BlockGradientAds.module.css";

type AdQueue = { push: (entry: object) => unknown; pauseAdRequests?: number; requestNonPersonalizedAds?: number };
type GppEvent = {
  listenerId?: number;
  eventName?: string;
  data?: unknown;
  pingData?: { signalStatus?: string; gppString?: string };
};
type AdWindow = Window & {
  adsbygoogle?: AdQueue;
  __tcfapi?: (command: string, version: number, callback: (data: TcfConsentData | null, success: boolean) => void, listenerId?: number) => void;
  __gpp?: (command: string, callback: (data: GppEvent | null, success: boolean) => void, listenerId?: number) => void;
  googlefc?: {
    callbackQueue: { push: (callback: Record<string, () => void>) => unknown };
    showRevocationMessage?: () => void;
    usstatesoptout?: { getInitialUsStatesOptOutStatus?: () => number };
  };
};

const DESKTOP_QUERY = "(min-width: 1600px)";

function AdSlot({ clientId, slotId, active, onRequest }: {
  clientId: string | null;
  slotId: string | null;
  active: boolean;
  onRequest: () => void;
}) {
  const space = useRef<HTMLDivElement>(null);
  const advert = useRef<HTMLModElement>(null);
  const [visible, setVisible] = useState(false);
  const [fillStatus, setFillStatus] = useState<"pending" | "filled" | "unfilled">("pending");
  const requested = useRef(false);

  useEffect(() => {
    const slot = space.current;
    if (!active || !slot || visible || !clientId || !slotId) return;
    const observer = new IntersectionObserver(([entry]) => {
      if (!entry.isIntersecting ||
        !window.matchMedia(DESKTOP_QUERY).matches ||
        getComputedStyle(slot).visibility === "hidden") return;
      setVisible(true);
      observer.disconnect();
    });
    observer.observe(slot);
    return () => observer.disconnect();
  }, [active, clientId, slotId, visible]);

  useEffect(() => {
    const element = advert.current;
    if (!element) return;
    const update = () => {
      const status = element.getAttribute("data-ad-status");
      if (status === "filled") setFillStatus("filled");
      if (status === "unfilled" || status === "unfill-optimized") setFillStatus("unfilled");
    };
    const observer = new MutationObserver(update);
    observer.observe(element, { attributes: true, attributeFilter: ["data-ad-status"] });
    update();
    return () => observer.disconnect();
  }, [visible]);

  useEffect(() => {
    if (!active || !visible || requested.current) return;
    const queue = (window as AdWindow).adsbygoogle;
    if (!queue || queue.pauseAdRequests !== 0) return;
    requested.current = true;
    onRequest();
    try {
      queue.push({});
    } catch {
      // An unavailable or blocked advert must never interrupt the tool.
      setFillStatus("unfilled");
    }
  }, [active, visible, onRequest]);

  return (
    <div className={styles.slot} data-fill={fillStatus}>
      {fillStatus === "filled" && <p className={styles.label}>Advertisement</p>}
      <div className={styles.space} ref={space}>
        {visible && clientId && slotId && (
          <ins
            ref={advert}
            className="adsbygoogle"
            style={{ display: "block", width: 300, height: 250 }}
            data-ad-client={clientId}
            data-ad-slot={slotId}
            aria-label="Advertisement"
          />
        )}
      </div>
    </div>
  );
}

export default function BlockGradientAds({ config, children }: {
  config: AdSenseConfig;
  children: ReactNode;
}) {
  const { ready, preferences } = usePrivacyConsent();
  const [wide, setWide] = useState(false);
  const [desktopVisited, setDesktopVisited] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [adsAllowed, setAdsAllowed] = useState(false);
  const layout = useRef<HTMLDivElement>(null);
  const hasRequested = useRef(false);
  const lastMode = useRef<GoogleAdMode | null>(null);
  const onRequest = useCallback(() => { hasRequested.current = true; }, []);

  useEffect(() => {
    const query = window.matchMedia(DESKTOP_QUERY);
    const update = () => {
      setWide(query.matches);
      if (query.matches) setDesktopVisited(true);
    };
    update();
    query.addEventListener("change", update);
    return () => query.removeEventListener("change", update);
  }, []);

  useEffect(() => {
    const container = layout.current;
    if (!container) return;
    const update = () => setExpanded(Boolean(container.querySelector('button[aria-label="Restore preview size"]')));
    const observer = new MutationObserver(update);
    observer.observe(container, { subtree: true, attributes: true, attributeFilter: ["aria-label"] });
    return () => observer.disconnect();
  }, []);

  useLayoutEffect(() => {
    const queue = (window as AdWindow).adsbygoogle;
    if (queue) queue.pauseAdRequests = wide && !expanded && adsAllowed ? 0 : 1;
  }, [wide, expanded, adsAllowed]);

  useEffect(() => {
    setAdsAllowed(false);
    if (!config.enabled || !config.clientId || !desktopVisited || !ready ||
      !preferences.advertising) return;

    const adWindow = window as AdWindow;
    const queue: AdQueue = adWindow.adsbygoogle ?? [] as object[];
    adWindow.adsbygoogle = queue;
    queue.pauseAdRequests = 1;
    // Start with the stricter mode while the certified CMP resolves choices.
    queue.requestNonPersonalizedAds = 1;
    const googlefc: NonNullable<AdWindow["googlefc"]> = adWindow.googlefc ?? {
      callbackQueue: [] as Record<string, () => void>[],
    };
    adWindow.googlefc = googlefc;
    googlefc.callbackQueue ??= [] as Record<string, () => void>[];
    let disposed = false;
    let loaded = false;
    let consent: TcfConsentData | null = null;
    let usOptOutStatus: number | undefined;
    let tcfListenerId: number | undefined;
    let gppListenerId: number | undefined;
    let previousGppString: string | undefined;
    let gppPending = false;

    const pause = () => {
      (adWindow.adsbygoogle ?? queue).pauseAdRequests = 1;
      setAdsAllowed(false);
    };
    const reload = () => {
      pause();
      window.location.reload();
    };
    const update = () => {
      if (disposed) return;
      const mode = getGoogleAdMode(consent, usOptOutStatus);
      // Opening the CMP is not a revocation. Pause until a completed choice.
      const pending = consent?.eventStatus === "cmpuishown" ||
        (consent?.gdprApplies === false && gppPending);
      if (!pending && hasRequested.current && mode !== lastMode.current) {
        reload();
        return;
      }
      if (!loaded || pending || !mode) {
        pause();
        return;
      }
      lastMode.current = mode;
      const currentQueue = adWindow.adsbygoogle ?? queue;
      currentQueue.requestNonPersonalizedAds = mode === "non-personalised" ? 1 : 0;
      currentQueue.pauseAdRequests = window.matchMedia(DESKTOP_QUERY).matches &&
        !layout.current?.querySelector('button[aria-label="Restore preview size"]') ? 0 : 1;
      setAdsAllowed(true);
    };

    googlefc.callbackQueue.push({
      CONSENT_API_READY: () => {
        if (disposed) return;
        adWindow.__tcfapi?.("addEventListener", 2, (data, success) => {
          if (disposed) return;
          if (data?.listenerId !== undefined) tcfListenerId = data.listenerId;
          consent = success ? data : null;
          update();
        });
        adWindow.__gpp?.("addEventListener", (data, success) => {
          if (disposed) return;
          if (data?.listenerId !== undefined) gppListenerId = data.listenerId;
          if (!success || data?.eventName === "error") {
            usOptOutStatus = undefined;
            update();
            return;
          }
          gppPending = data?.pingData?.signalStatus === "not ready";
          if (data?.pingData?.signalStatus === "ready" &&
            typeof data.pingData.gppString === "string") {
            const nextString = data.pingData.gppString;
            if (previousGppString !== undefined && previousGppString !== nextString) {
              // Initial-status APIs are snapshots. Reload after a saved GPP
              // change so updated opt-outs are read before any more ads.
              reload();
              return;
            }
            previousGppString = nextString;
          }
          update();
        });
      },
      INITIAL_US_STATES_OPT_OUT_DATA_READY: () => {
        if (disposed) return;
        usOptOutStatus = adWindow.googlefc?.usstatesoptout?.getInitialUsStatesOptOutStatus?.();
        update();
      },
    });

    const showGooglePrivacy = () => adWindow.googlefc?.callbackQueue.push({
      CONSENT_API_READY: () => {
        if (!disposed) adWindow.googlefc?.showRevocationMessage?.();
      },
    });
    window.addEventListener("crabcraft:google-privacy-settings", showGooglePrivacy);
    const script = document.createElement("script");
    script.async = true;
    script.crossOrigin = "anonymous";
    script.src = `https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js?client=${config.clientId}`;
    script.onload = () => {
      if (disposed) return;
      loaded = true;
      update();
    };
    script.onerror = pause;
    // Set before append: revocation must also stop an in-flight script load.
    document.documentElement.dataset.crabcraftAdsLoaded = "true";
    document.head.appendChild(script);

    return () => {
      disposed = true;
      (adWindow.adsbygoogle ?? queue).pauseAdRequests = 1;
      window.removeEventListener("crabcraft:google-privacy-settings", showGooglePrivacy);
      if (tcfListenerId !== undefined) adWindow.__tcfapi?.("removeEventListener", 2, () => {}, tcfListenerId);
      if (gppListenerId !== undefined) adWindow.__gpp?.("removeEventListener", () => {}, gppListenerId);
      script.onload = null;
      script.onerror = null;
      script.remove();
    };
  }, [config.enabled, config.clientId, desktopVisited, ready, preferences.advertising]);

  const permitted = adsAllowed && ready && preferences.advertising;
  const active = permitted && wide && !expanded;
  const rail = (side: "left" | "right") => (
    <aside className={styles.rail} aria-label={`Advertisements on the ${side}`}>
      {Array.from({ length: 4 }, (_, index) => (
        <AdSlot
          key={`${side}-${index}`}
          clientId={config.enabled ? config.clientId : null}
          slotId={config.slotId}
          active={active}
          onRequest={onRequest}
        />
      ))}
    </aside>
  );

  if (!config.enabled) return children;

  return (
    <div className={styles.layout} ref={layout} data-advertising-permitted={permitted}>
      {rail("left")}
      <div className={styles.tool}>{children}</div>
      {rail("right")}
    </div>
  );
}
