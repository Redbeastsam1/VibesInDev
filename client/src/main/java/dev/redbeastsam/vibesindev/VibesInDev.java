package dev.redbeastsam.vibesindev;

import dev.redbeastsam.vibesindev.network.MusicPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("null")
public class VibesInDev implements ModInitializer {
  public static final String MOD_ID = "vibesindev";

  // This logger is used to write text to the console and the log file.
  // It is considered best practice to use your mod id as the logger's name.
  // That way, it's clear which mod wrote info, warnings, and errors.
  public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

  @Override
  public void onInitialize() {
    // This code runs as soon as Minecraft is in a mod-load-ready state.
    // However, some things (like resources) may still be uninitialized.
    // Proceed with mild caution.

    Identifier networkMusicId = Identifier.fromNamespaceAndPath(MOD_ID, "network_music");

    Registry.register(
        BuiltInRegistries.SOUND_EVENT,
        networkMusicId,
        SoundEvent.createVariableRangeEvent(networkMusicId));

    PayloadTypeRegistry.serverboundPlay().register(MusicPayload.TYPE, MusicPayload.CODEC);

    PayloadTypeRegistry.clientboundPlay().register(MusicPayload.TYPE, MusicPayload.CODEC);

    LOGGER.info("Hello Fabric world!");
  }

  public static Identifier id(String path) {
    return Identifier.fromNamespaceAndPath(MOD_ID, path);
  }
}
