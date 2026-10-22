package crabcraft.net.crabUtilities.tags;

import crabcraft.net.crabUtilities.CrabMessages;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** One acknowledged, immediately applied picker shared by all tag entry points. */
@SuppressWarnings("UnstableApiUsage")
public final class TagsDialog implements Listener {

    private static final int PAGE_SIZE = 8;
    private final PaperTagService service;
    private final Consumer<Player> settings;
    private final Map<UUID, Long> generations = new HashMap<>();
    private long nextGeneration;

    public TagsDialog(PaperTagService service, Consumer<Player> settings) {
        this.service = service;
        this.settings = settings;
    }

    public void open(Player player) {
        service.runOnMain(() -> {
            if (service.isSelecting(player.getUniqueId())) {
                player.sendMessage(CrabMessages.muted("Your tag is still saving, please wait a moment."));
                return;
            }
            long generation = begin(player);
            showDialog(player, generation, List.of(DialogBody.plainMessage(CrabMessages.muted("Loading your tags…"))),
                    List.of(back(player, generation)));
            service.query(player, response -> {
                if (!current(player, generation)) return;
                if (response.status() == TagBridgeProtocol.Status.OK && response.snapshot() != null) {
                    show(player, 0);
                } else {
                    dismiss(player);
                    player.closeDialog();
                    player.sendMessage(CrabMessages.error("Tags are temporarily unavailable. Please try /tags again."));
                }
            });
        });
    }

    /** Other local menus invalidate callbacks that could otherwise reopen this picker. */
    public void dismiss(Player player) {
        generations.remove(player.getUniqueId());
    }

    public void shutdown() {
        generations.clear();
    }

    private void show(Player player, int requestedPage) {
        TagCache.Entry entry = service.cache().get(player.getUniqueId());
        if (entry == null) {
            dismiss(player);
            player.closeDialog();
            player.sendMessage(CrabMessages.error("Your tags need refreshing. Please open /tags again."));
            return;
        }
        TagSnapshot snapshot = entry.snapshot();
        List<TagDefinition> tags = snapshot.ownedTags().stream().filter(TagDefinition::enabled)
                .sorted(Comparator.comparingInt(TagDefinition::sortOrder)
                        .thenComparing(TagDefinition::name).thenComparing(TagDefinition::id)).toList();
        int pages = Math.max(1, (tags.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        long generation = begin(player);
        List<ActionButton> actions = new ArrayList<>();
        actions.add(action(player, generation, selected(CrabMessages.text("No tag"), snapshot.selectedTagId() == null),
                CrabMessages.text("Hide your tag without losing any unlocks."),
                () -> select(player, null, snapshot.revision(), page)));
        int end = Math.min(tags.size(), (page + 1) * PAGE_SIZE);
        for (TagDefinition tag : tags.subList(page * PAGE_SIZE, end)) {
            Component icon = entry.icons().get(tag.id());
            Component label = icon == null ? Component.empty() : icon.append(Component.space());
            label = label.append(CrabMessages.text(tag.name()));
            Component tooltip = CrabMessages.text(tag.description());
            if (icon == null) tooltip = tooltip.append(Component.newline())
                    .append(CrabMessages.muted("The icon is unavailable on this server."));
            actions.add(action(player, generation, selected(label, Objects.equals(tag.id(), snapshot.selectedTagId())),
                    tooltip, () -> select(player, tag.id(), snapshot.revision(), page)));
        }
        if (page > 0) actions.add(action(player, generation, CrabMessages.muted("Previous page"), null,
                () -> show(player, page - 1)));
        if (page + 1 < pages) actions.add(action(player, generation, CrabMessages.muted("Next page"), null,
                () -> show(player, page + 1)));
        actions.add(back(player, generation));

        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(CrabMessages.text("Choose one unlocked tag, or hide it. Changes save immediately.")));
        if (tags.isEmpty()) body.add(DialogBody.plainMessage(CrabMessages.muted("You haven't unlocked any tags yet.")));
        TagDefinition saved = snapshot.selectedTag();
        if (saved != null && !saved.enabled()) body.add(DialogBody.plainMessage(CrabMessages.muted(
                "Your selected tag, " + saved.name() + ", is temporarily unavailable. It remains selected until you choose another option.")));
        if (pages > 1) body.add(DialogBody.plainMessage(CrabMessages.muted("Page " + (page + 1) + " of " + pages)));
        showDialog(player, generation, body, actions);
    }

    private void select(Player player, String tagId, long revision, int page) {
        if (service.isSelecting(player.getUniqueId())) return;
        long generation = begin(player);
        showDialog(player, generation, List.of(DialogBody.plainMessage(CrabMessages.muted("Saving your tag…"))),
                List.of(back(player, generation)));
        service.select(player, tagId, revision, response -> {
            if (!current(player, generation)) return;
            if (response.timedOut()) {
                dismiss(player);
                player.closeDialog();
                player.sendMessage(CrabMessages.warning("Your tag change is unconfirmed. Reopen /tags to check before trying again."));
                return;
            }
            switch (response.status()) {
                case OK -> {
                    player.sendMessage(CrabMessages.success(tagId == null ? "Tag hidden." : "Tag selected."));
                    show(player, page);
                }
                case STALE_STATE -> {
                    player.sendMessage(CrabMessages.warning("Your tags changed while this menu was open. Please choose again."));
                    show(player, page);
                }
                case NOT_OWNED, DISABLED, UNKNOWN_TAG -> {
                    player.sendMessage(CrabMessages.error("That tag is no longer available to select."));
                    show(player, page);
                }
                case UNAVAILABLE -> {
                    dismiss(player);
                    player.closeDialog();
                    player.sendMessage(CrabMessages.error("Your tag could not be saved. Please try /tags again."));
                }
            }
        });
    }

    private void showDialog(Player player, long generation, List<DialogBody> body, List<ActionButton> actions) {
        DialogBase base = DialogBase.builder(CrabMessages.highlight("Tags"))
                .body(body).pause(false).canCloseWithEscape(true)
                .afterAction(DialogBase.DialogAfterAction.NONE).build();
        player.showDialog(Dialog.create(builder -> builder.empty().base(base).type(DialogType.multiAction(actions,
                action(player, generation, CrabMessages.muted("Close"), null, () -> {
                    dismiss(player);
                    player.closeDialog();
                }), 1))));
    }

    private ActionButton back(Player player, long generation) {
        return action(player, generation, CrabMessages.highlight("Back to settings"), null, () -> {
            dismiss(player);
            player.closeDialog();
            settings.accept(player);
        });
    }

    private ActionButton action(Player player, long generation, Component label, Component tooltip, Runnable callback) {
        return ActionButton.create(label, tooltip, 250, DialogAction.customClick((view, audience) ->
                service.runOnMain(() -> {
                    if (audience == player && current(player, generation)) callback.run();
                }), ClickCallback.Options.builder().uses(1).build()));
    }

    private long begin(Player player) {
        long generation = ++nextGeneration;
        generations.put(player.getUniqueId(), generation);
        return generation;
    }

    private boolean current(Player player, long generation) {
        return player.isOnline() && Objects.equals(generations.get(player.getUniqueId()), generation);
    }

    private static Component selected(Component name, boolean selected) {
        return selected ? name.append(CrabMessages.success("  [Selected]")) : name;
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        // A later command may open another menu before the proxy reply arrives.
        dismiss(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        dismiss(event.getPlayer());
    }
}
