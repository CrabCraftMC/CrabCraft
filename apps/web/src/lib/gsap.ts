"use client";

import { gsap } from "gsap";
import { CustomEase } from "gsap/CustomEase";
import { SplitText } from "gsap/SplitText";

let registered = false;

function ensureRegistered() {
  if (registered || typeof window === "undefined") return;
  gsap.registerPlugin(CustomEase, SplitText);
  CustomEase.create(
    "crab-overshoot",
    "M0,0 C0.2,0 0.1,1.4 0.55,1 1,0.6 0.95,1 1,1"
  );
  CustomEase.create("crab-smash", "M0,0 C0.85,0 0.1,1.1 1,1");
  gsap.defaults({ overwrite: "auto" });
  registered = true;
}

ensureRegistered();

export {
  gsap,
  CustomEase,
  SplitText,
};
