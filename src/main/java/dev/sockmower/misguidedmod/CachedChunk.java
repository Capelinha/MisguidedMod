package dev.sockmower.misguidedmod;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

public class CachedChunk {
    public final Pos2 pos;
    public final ClientboundLevelChunkWithLightPacket packet;
    public boolean poison;

    @Override
    public String toString() {
        return String.format("Chunk{%s}", pos);
    }

    public CachedChunk(Pos2 pos, ClientboundLevelChunkWithLightPacket packet) {
        this.pos = pos;
        this.packet = packet;
        this.poison = false;
    }

    public void poison() {
        poison = true;
    }
}
