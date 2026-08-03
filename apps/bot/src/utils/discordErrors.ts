import { RESTJSONErrorCodes } from "discord.js";

export function isUnknownChannelError(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    error.code === RESTJSONErrorCodes.UnknownChannel
  );
}

export function isUnknownMessageError(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "code" in error &&
    error.code === RESTJSONErrorCodes.UnknownMessage
  );
}
