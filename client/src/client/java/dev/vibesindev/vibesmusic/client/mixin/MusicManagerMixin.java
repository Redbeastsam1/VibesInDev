package dev.vibesindev.vibesmusic.client.mixin;

import dev.vibesindev.vibesmusic.client.audio.MusicAudioPlayer;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MusicManager.class)
public class MusicManagerMixin {
  @Inject(method = "tick", at = @At("HEAD"))
  private void vibesindev$preventVanillaMusic(CallbackInfo ci) {
    if (MusicAudioPlayer.isPlaying()) {
      MusicManager musicManager = (MusicManager) (Object) this;

      musicManager.stopPlaying();
    }
  }
}
