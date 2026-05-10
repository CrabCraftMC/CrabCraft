package crabcraft.net.crabUtilities.neoforge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(CrabUtilitiesNeoForge.MOD_ID)
public final class CrabUtilitiesNeoForge {

    public static final String MOD_ID = "crabutilities";
    public static final Logger LOGGER = LogUtils.getLogger();

    private StatsPushTask statsPushTask;

    public CrabUtilitiesNeoForge() {
        ModLoadingContext.get().registerConfig(
                ModConfig.Type.COMMON, CrabConfig.SPEC, "crabutilities.toml");
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        // Defer to ServerStarted (not Starting) so the world directory
        // is fully resolved by the time we read the stats folder.
        this.statsPushTask = new StatsPushTask(event.getServer());
        this.statsPushTask.start();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (statsPushTask != null) {
            statsPushTask.shutdown();
            statsPushTask = null;
        }
    }
}
