import { beforeEach, describe, expect, mock, spyOn, test } from "bun:test";
import type { TagDefinitionInput } from "@crabcraft/db/queries/tag-admin";

const queries = await import("@crabcraft/db/queries/tag-admin");
const { TagAdminError, validateTagDefinition } = queries;
let session: { user: { role: string; discordId: string } } | null;
const auth = mock(async () => session);
const revalidatePath = mock((_path: string) => {});
const createTag = mock(async (input: TagDefinitionInput) => validateTagDefinition(input));
const updateTag = mock(async (input: TagDefinitionInput) => validateTagDefinition(input));
const grantPlayerTag = mock(async (_input: { minecraftUuid: string; tagId: string; actor: string; reason: string }) => true);
const revokePlayerTag = mock(async (_input: { minecraftUuid: string; tagId: string; actor: string; reason: string }) => true);

mock.module("../src/lib/auth", () => ({ auth }));
mock.module("next/cache", () => ({ revalidatePath }));
mock.module("@crabcraft/db/queries/tag-admin", () => ({
  ...queries, TagAdminError, createTag, updateTag, grantPlayerTag, revokePlayerTag,
}));

const { saveTagAction, grantTagAction, revokeTagAction } = await import("../src/app/admin/tag-actions");
const initial = { status: "idle" as const, message: "" };
const uuid = "12345678-1234-1234-1234-123456789abc";

function definitionForm(operation = "create") {
  const form = new FormData();
  for (const [key, value] of Object.entries({ operation, id: "test-tag", name: "Test tag", description: "", glyph_id: "pumpkin", sort_order: "3", enabled: "on" })) {
    form.set(key, value);
  }
  return form;
}

function awardForm() {
  const form = new FormData();
  form.set("minecraft_uuid", uuid);
  form.set("tag_id", "test-tag");
  form.set("reason", "Won the event");
  form.set("actor", "discord:999");
  return form;
}

beforeEach(() => {
  session = { user: { role: "admin", discordId: "123" } };
  for (const fn of [auth, revalidatePath, createTag, updateTag, grantPlayerTag, revokePlayerTag]) fn.mockClear();
  createTag.mockImplementation(async (input) => validateTagDefinition(input));
  updateTag.mockImplementation(async (input) => validateTagDefinition(input));
  grantPlayerTag.mockImplementation(async () => true);
  revokePlayerTag.mockImplementation(async () => true);
});

describe("administrator tag actions", () => {
  test("every mutation denies anonymous users and all non-admin roles before accessing storage", async () => {
    for (const role of [null, "unverified", "verified", "moderator"]) {
      session = role ? { user: { role, discordId: "123" } } : null;
      for (const [action, form] of [[saveTagAction, definitionForm()], [grantTagAction, awardForm()], [revokeTagAction, awardForm()]] as const) {
        expect(await action(initial, form)).toMatchObject({ status: "error", message: "Administrator access is required." });
      }
    }
    expect(auth).toHaveBeenCalledTimes(12);
    for (const query of [createTag, updateTag, grantPlayerTag, revokePlayerTag, revalidatePath]) expect(query).not.toHaveBeenCalled();
  });

  test("a role change is checked again on the next action", async () => {
    expect((await grantTagAction(initial, awardForm())).status).toBe("success");
    session!.user.role = "moderator";
    expect((await revokeTagAction(initial, awardForm())).status).toBe("error");
    expect(auth).toHaveBeenCalledTimes(2);
    expect(revokePlayerTag).not.toHaveBeenCalled();
  });

  test("catalogue create and update use validated fields and refresh the admin page", async () => {
    const create = await saveTagAction(initial, definitionForm());
    const form = definitionForm("update");
    form.delete("enabled");
    const update = await saveTagAction(initial, form);
    expect(create.status).toBe("success");
    expect(update.status).toBe("success");
    expect(createTag).toHaveBeenCalledWith({ id: "test-tag", name: "Test tag", description: "", glyph_id: "pumpkin", sort_order: 3, enabled: true });
    expect(updateTag).toHaveBeenCalledWith(expect.objectContaining({ id: "test-tag", enabled: false }));
    expect(revalidatePath).toHaveBeenCalledTimes(2);
    expect(revalidatePath).toHaveBeenCalledWith("/admin");
  });

  test("blank, fractional and invalid display orders produce inline validation errors", async () => {
    for (const sortOrder of ["", "1.5", "abc"]) {
      const form = definitionForm();
      form.set("sort_order", sortOrder);
      expect(await saveTagAction(initial, form)).toMatchObject({ status: "error", message: "Display order must be a whole number." });
    }
    expect(createTag).not.toHaveBeenCalled();
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  test("a forged operation cannot create or update a definition", async () => {
    expect((await saveTagAction(initial, definitionForm("delete"))).status).toBe("error");
    expect(createTag).not.toHaveBeenCalled();
    expect(updateTag).not.toHaveBeenCalled();
  });

  test("grant and revoke record the authenticated identity, ignoring an actor supplied by the browser", async () => {
    for (const action of [grantTagAction, revokeTagAction]) expect((await action(initial, awardForm())).status).toBe("success");
    const expected = { minecraftUuid: uuid, tagId: "test-tag", actor: "discord:123", reason: "Won the event" };
    expect(grantPlayerTag).toHaveBeenCalledWith(expected);
    expect(revokePlayerTag).toHaveBeenCalledWith(expected);
  });

  test("duplicate grants and already-revoked ownership are clear no-ops", async () => {
    grantPlayerTag.mockImplementation(async () => false);
    revokePlayerTag.mockImplementation(async () => false);
    expect((await grantTagAction(initial, awardForm())).message).toContain("already owns");
    expect((await revokeTagAction(initial, awardForm())).message).toContain("no longer owns");
  });

  test("expected validation and duplicate-ID errors are returned to the form", async () => {
    createTag.mockImplementation(async () => { throw new TagAdminError("TAG_EXISTS", "This tag already exists."); });
    expect(await saveTagAction(initial, definitionForm())).toEqual({ status: "error", message: "This tag already exists." });
    grantPlayerTag.mockImplementation(async () => { throw new TagAdminError("INVALID_INPUT", "Provide a reason."); });
    expect(await grantTagAction(initial, awardForm())).toEqual({ status: "error", message: "Provide a reason." });
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  test("missing or file-valued fields are rejected without coercion", async () => {
    const form = definitionForm();
    form.set("name", new File(["bad"], "tag.txt"));
    expect((await saveTagAction(initial, form)).status).toBe("error");
    const award = awardForm();
    award.delete("reason");
    expect((await grantTagAction(initial, award)).status).toBe("error");
    expect(createTag).not.toHaveBeenCalled();
    expect(grantPlayerTag).not.toHaveBeenCalled();
  });

  test("unexpected storage failures do not disclose SQL details or claim success", async () => {
    const log = spyOn(console, "error").mockImplementation(() => {});
    try {
      revokePlayerTag.mockImplementation(async () => { throw new Error("private SQL details"); });
      const result = await revokeTagAction(initial, awardForm());
      expect(result.status).toBe("error");
      expect(result.message).toContain("Refresh");
      expect(result.message).not.toContain("private SQL");
      expect(revalidatePath).not.toHaveBeenCalled();
    } finally {
      log.mockRestore();
    }
  });
});
