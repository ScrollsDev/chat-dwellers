package com.chatdwellers;

import com.chatdwellers.config.Config;
import com.chatdwellers.twitch.TwitchBootstrap;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(ChatDwellers.MODID)
public class ChatDwellers {
    public static final String MODID = "chatdwellers";
    public static final Logger LOGGER = LogManager.getLogger();

    public ChatDwellers() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.SPEC, "chatdwellers-local.toml");
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::clientSetup);
    }

    private void clientSetup(FMLClientSetupEvent event) {
        ChatDwellersClient.init();
        TwitchBootstrap bootstrap = new TwitchBootstrap();
        ChatDwellersClient.tokenStore = bootstrap.store();
        ChatDwellersClient.helixClient = bootstrap.helix();
        ChatDwellersClient.bootstrap = bootstrap;
        bootstrap.start();

        // Register all FORGE-bus handlers explicitly rather than relying on @Mod.EventBusSubscriber
        // annotation scanning, so command + skinning registration is deterministic. Also wire the
        // bottom-right notification overlay.
        event.enqueueWork(() -> {
            MinecraftForge.EVENT_BUS.register(com.chatdwellers.command.ChatDwellersCommand.class);
            MinecraftForge.EVENT_BUS.register(com.chatdwellers.render.DwellerSpawnTracker.class);
            MinecraftForge.EVENT_BUS.register(com.chatdwellers.render.NametagHandler.class);
            MinecraftForge.EVENT_BUS.register(com.chatdwellers.render.VaultLifecycleHandler.class);
            MinecraftForge.EVENT_BUS.register(com.chatdwellers.client.PanelOpener.class);
            net.minecraftforge.client.gui.OverlayRegistry.registerOverlayTop(
                "chatdwellers_notifications", com.chatdwellers.client.Notify::renderOverlay);
            LOGGER.info("[ChatDwellers] registered command + render handlers + notification overlay");
        });
        LOGGER.info("[ChatDwellers] client setup complete");
    }
}
