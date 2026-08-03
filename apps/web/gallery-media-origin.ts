export function parseGalleryMediaOrigin(value: string | undefined) {
  const configuredValue = value?.trim();
  if (!configuredValue) {
    throw new Error("GALLERY_MEDIA_BASE_URL must be configured");
  }

  let url: URL;
  try {
    url = new URL(configuredValue);
  } catch {
    throw new Error(
      "GALLERY_MEDIA_BASE_URL must be a credential-free HTTPS origin",
    );
  }

  if (
    url.protocol !== "https:" ||
    url.username !== "" ||
    url.password !== "" ||
    url.pathname !== "/" ||
    url.search !== "" ||
    url.hash !== ""
  ) {
    throw new Error(
      "GALLERY_MEDIA_BASE_URL must be a credential-free HTTPS origin",
    );
  }

  return url;
}
