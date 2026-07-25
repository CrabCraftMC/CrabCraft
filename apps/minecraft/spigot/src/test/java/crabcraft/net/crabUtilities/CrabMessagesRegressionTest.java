package crabcraft.net.crabUtilities;

import crabcraft.net.crabUtilities.media.language.MediaMessages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;

final class CrabMessagesRegressionTest {

    public static void main(String[] args) {
        checkColour(CrabMessages.success("Success"), CrabMessages.SUCCESS, "success");
        checkColour(CrabMessages.error("Error"), CrabMessages.ERROR, "error");
        checkColour(CrabMessages.warning("Warning"), CrabMessages.HIGHLIGHT, "warning");
        checkColour(CrabMessages.text("Text"), CrabMessages.TEXT, "neutral");
        checkColour(CrabMessages.muted("Muted"), CrabMessages.MUTED, "muted");

        Component label = CrabMessages.label("State", "running");
        check(label.color().equals(CrabMessages.ACCENT), "labels must use the media accent");
        check(label.children().size() == 1, "label value component is missing");
        check(label.children().get(0).color().equals(CrabMessages.TEXT),
                "label values must use off-white");

        MediaMessages media = new MediaMessages();
        checkColour(
                media.component("command.create.messages.created"),
                CrabMessages.SUCCESS,
                "media success");
        checkColour(
                media.component("error.command.no-permission"),
                CrabMessages.ERROR,
                "media error");
        checkColour(
                media.component("command.help.messages.header"),
                CrabMessages.ACCENT,
                "media help heading");
    }

    private static void checkColour(Component component, Object expected, String role) {
        check(expected.equals(component.color()), role + " colour changed");
        check(component.decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE,
                role + " message inherited italics");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
