package crabcraft.net.crabUtilities.voicechat;

import de.maxhenkel.voicechat.api.Group;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;

import java.util.List;
import java.util.logging.Logger;

public class CrabVoicechatPlugin implements VoicechatPlugin {

    public static final String PLUGIN_ID = "crabutilities";

    private static final List<String> PERSISTENT_GROUP_NAMES = List.of(
            "Global #1",
            "Global #2",
            "Global #3"
    );

    private final Logger logger;

    public CrabVoicechatPlugin(Logger logger) {
        this.logger = logger;
    }

    @Override
    public String getPluginId() {
        return PLUGIN_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        // Nothing to initialize before the server is up — groups are
        // created in onServerStarted once we have a VoicechatServerApi.
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        VoicechatServerApi api = event.getVoicechat();
        for (String name : PERSISTENT_GROUP_NAMES) {
            Group group = api.groupBuilder()
                    .setName(name)
                    .setType(Group.Type.OPEN)
                    .setPersistent(true)
                    .build();
            logger.info("Created persistent voice chat group '" + name + "' (" + group.getId() + ")");
        }
    }
}
