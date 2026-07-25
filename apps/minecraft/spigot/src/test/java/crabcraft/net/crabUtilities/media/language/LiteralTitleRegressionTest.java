package crabcraft.net.crabUtilities.media.language;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

public final class LiteralTitleRegressionTest {
  public static void main(String[] args) {
    Component builtIn = new MediaMessages().component("error.command.cant-perform");
    check(PlainTextComponentSerializer.plainText().serialize(builtIn)
        .equals("You can't run that command."),
      "media messages still depend on external configuration");

    String hostileTitle = "<click:run_command:'/op attacker'><red>Server maintenance";
    Component rendered = MediaMessages.literalReplacement(
      "<gold>Now playing: <yellow>{0}", Component.text(hostileTitle));
    String plain = PlainTextComponentSerializer.plainText().serialize(rendered);

    check(plain.equals("Now playing: " + hostileTitle),
      "remote title was interpreted instead of rendered literally");
    check(rendered.clickEvent() == null, "remote title installed an active click event");
    String renderedMiniMessage = MiniMessage.miniMessage().serialize(rendered);
    check(renderedMiniMessage.contains("<yellow>"),
      "the language template's title colour did not apply to the literal title: "
        + renderedMiniMessage);

    Component wrapped = MediaMessages.literalReplacement(
      "<gold>Now <bold>{0}</bold> tail</gold>", Component.text("playing"));
    check(PlainTextComponentSerializer.plainText().serialize(wrapped).equals("Now playing tail"),
      "tags spanning the literal title placeholder were rendered as text");
    check(MiniMessage.miniMessage().serialize(wrapped).contains("<bold>playing"),
      "tags spanning the literal title placeholder did not style the title");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
