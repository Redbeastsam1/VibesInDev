package dev.vibesindev.vibesmusic.client;

import dev.vibesindev.vibesmusic.client.audio.MusicAudioPlayer;
import dev.vibesindev.vibesmusic.client.network.MusicNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public final class VibesInDevClient implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    // Tick the audio player recovery loop
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          MusicAudioPlayer.tick();
        });

    // Register network payload handler
    MusicNetwork.register();

    // Send HELLO handshake when joining a server
    ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> MusicNetwork.sendHello());

    System.out.println("[VibesInDev] Fabric client initialized.");
  }
}
