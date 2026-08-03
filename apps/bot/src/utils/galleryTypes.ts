export interface GalleryTagSyncInput {
  discordTagId: string;
  name: string;
  emojiId: string | null;
  emojiName: string | null;
  position: number;
  moderated: boolean;
}

export interface GalleryImageSyncInput {
  discordAttachmentId: string;
  position: number;
  storageKey: string;
  publicUrl: string;
  filename: string;
  alt: string | null;
  contentType: string | null;
  width: number;
  height: number;
  size: number;
}

export interface GalleryPostSyncInput {
  threadId: string;
  channelId: string;
  seasonId: string;
  title: string;
  content: string | null;
  authorDiscordId: string;
  authorDiscordUsername: string;
  authorDisplayName: string | null;
  authorWebhookId: string | null;
  sourceUrl: string;
  postedAt: number;
  editedAt: number | null;
  archived: boolean;
  locked: boolean;
  pinned: boolean;
  revision: number;
  contentHash: string;
  syncedAt: number;
  tags: GalleryTagSyncInput[];
  images: GalleryImageSyncInput[];
}

export interface GalleryStoredImage {
  storageKey: string;
  publicUrl: string;
  width: number;
  height: number;
}
