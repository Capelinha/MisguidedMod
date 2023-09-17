package dev.sockmower.misguidedmod;

import java.io.IOException;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;

import com.mojang.logging.LogUtils;

import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;

@Mod(MisguidedMod.MODID)
public class MisguidedMod {
    public static final String MODID = "misguidedmod";
    public static final String NAME = "Just A Misguided Mod";
    public static final String VERSION = "1.0";
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Minecraft mc = Minecraft.getInstance();
    
    private static final Set<Pos2> loadedChunks = new HashSet<Pos2>();
    private static CachedWorld cachedWorld;
    private long lastExtraTime = 0;
    
    public MisguidedMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::init);

        MinecraftForge.EVENT_BUS.register(this);
    }

    public void init(FMLClientSetupEvent event) {
        LOGGER.info("Initializing MisguidedMod v{}", VERSION);
        MinecraftForge.EVENT_BUS.register(this);
    }

    public void loadChunk(CachedChunk chunk) {
        if (!mc.isSameThread()) {
            LOGGER.warn("Calling loadChunk from non-mc thread");
            return;
        }

        ClientPacketListener conn = mc.getConnection();
        if (conn == null) {
            LOGGER.warn("Connection is null!");
            return;
        }

        unloadChunk(chunk.pos);

        conn.handleLevelChunkWithLight(chunk.packet);
        loadedChunks.add(chunk.pos);
    }

    public void unloadChunk(Pos2 pos) {
        if (!mc.isSameThread()) {
            LOGGER.warn("Calling loadChunk from non-mc thread");
            return;
        }

        ClientPacketListener conn = mc.getConnection();
        if (conn == null) {
            LOGGER.warn("Connection is null!");
            return;
        }

        conn.handleForgetLevelChunk(new ClientboundForgetLevelChunkPacket(pos.x, pos.z));
        loadedChunks.remove(pos);
    }

    public Pos2 getPlayerChunkPos() {
        LocalPlayer player = mc.player;

        if (player == null) {
            return null;
        }

        if (player.position().x == 0 && player.position().y == 0 && player.position().z == 0) return null;
        if (player.position().x == 8.5 && player.position().y == 65 && player.position().z == 8.5) return null; // position not set from server yet
        return Pos2.chunkPosFromBlockPos(player.position().x, player.position().z);
    }

    public Set<Pos2> getNeededChunkPositions() {
        final int rdClient = mc.options.renderDistance().get() + 1;
        final Pos2 player = getPlayerChunkPos();

        final Set<Pos2> loadable = new HashSet<>();

        if (player == null) {
            return loadable;
        }

        for (int x = player.x - rdClient; x <= player.x + rdClient; x++) {
            for (int z = player.z - rdClient; z <= player.z + rdClient; z++) {
                Pos2 chunk = new Pos2(x, z);

                if (1 + player.chebyshevDistance(chunk) <= 7) {
                    // do not load extra chunks inside the server's (admittedly, guessed) render distance,
                    // we expect the server to send game chunks here eventually
                    continue;
                }

                if (!loadedChunks.contains(chunk)) {
                    loadable.add(chunk);
                }
            }
        }

        //logger.info("Want to request {} additional chunks", loadable.size());

        return loadable;
    }

    public void unloadOutOfRangeChunks() {
        final int rdClient = mc.options.renderDistance().get() + 1;
        final Pos2 player = getPlayerChunkPos();

        if (player == null) {
            return;
        }

        Set<Pos2> toUnload = new HashSet<>();

        for (Pos2 pos : loadedChunks) {
            if (pos.chebyshevDistance(player) > rdClient) {
                // logger.info("Unloading chunk at {} since it is outside of render distance", pos.toString());
                toUnload.add(pos);
            }
        }

        toUnload.forEach(pos -> mc.executeBlocking(() -> unloadChunk(pos)));
    }

    public void onReceiveGameChunk(CachedChunk chunk) throws IOException {
        mc.executeBlocking(() -> loadChunk(chunk));

        if ((System.currentTimeMillis() / 1000) - lastExtraTime > 1) {
            lastExtraTime = System.currentTimeMillis() / 1000;
            cachedWorld.addChunksToLoadQueue(getNeededChunkPositions());
            unloadOutOfRangeChunks();
        }

        cachedWorld.writeChunk(chunk);
    }

    @SubscribeEvent
    public void onGameConnected(ClientPlayerNetworkEvent.LoggingIn event) {
        ServerData currentServer = mc.getCurrentServer();
        ClientPacketListener conn = mc.getConnection();

        if (currentServer == null || (!currentServer.ip.equals("play.wynncraft.com") && !currentServer.ip.equals("play.wynncraft.net"))) {
            LOGGER.info("Current server is null or not play.wynncraft.com");
            return;
        }

        if (conn == null) {
            LOGGER.info("Connection is null");
            return;
        }

        LOGGER.info("Connecting to server {}", currentServer.ip);

        ChannelPipeline pipe = conn.getConnection().channel().pipeline();

        if (pipe.get(PacketHandler.NAME) != null) {
            LOGGER.warn("Game server connection pipeline already contains handler, removing and re-adding");
            pipe.remove(PacketHandler.NAME);
        }

        LOGGER.info("Adding packet handler to pipeline");

        PacketHandler packetHandler = new PacketHandler(this);
        pipe.addBefore("packet_handler", PacketHandler.NAME, packetHandler);

        Path path = Paths.get(mc.gameDirectory.getAbsolutePath() + "\\misguidedmod\\" + currentServer.ip);

        LOGGER.info("Connected to server {}, client render distance is {}", currentServer.ip, mc.options.renderDistance().get());
        LOGGER.info("Creating cached world at {}", path.toAbsolutePath());

        cachedWorld = new CachedWorld(path, LOGGER, mc, this);
    }

    @SubscribeEvent
    public void onGameDisconnected(ClientPlayerNetworkEvent.LoggingOut event) {
        loadedChunks.clear();
        LOGGER.info("loadedChunks cleared.");

        try {
            cachedWorld.releaseFiles();
            cachedWorld.cancelThreads();
        } catch (Exception e) {
            LOGGER.warn("Failed to release cached world files");
        }

        ClientPacketListener conn = mc.getConnection();

        if (conn == null) {
            LOGGER.info("Connection is null");
        } else {
            ChannelPipeline pipe = conn.getConnection().channel().pipeline();

            if (pipe != null && pipe.get(PacketHandler.NAME) != null) {
                pipe.remove(PacketHandler.NAME);
            }
        }
    }
}
