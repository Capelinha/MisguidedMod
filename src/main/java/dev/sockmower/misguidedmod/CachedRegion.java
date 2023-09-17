package dev.sockmower.misguidedmod;

import static java.nio.file.StandardOpenOption.*;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.lwjgl.BufferUtils;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

import org.slf4j.Logger;

public class CachedRegion implements Closeable {
    public static final int CHUNKS_PER_REGION = 32 * 32;

    private Path regionFile;
    private SeekableByteChannel seekableStream;
    private Int2ObjectMap<ChunkHeader> lookupTable = new Int2ObjectOpenHashMap<>();
    private Logger logger;
    public long lastAccessed;
    public boolean poison;

    CachedRegion(boolean poison) {
        this.poison = poison;
    }

    CachedRegion(String directory, int x, int z, Logger logger) throws IOException {
        this.logger = logger;

        regionFile = Paths.get(String.format("%s\\r.%d.%d.mmr", directory, x, z));
        boolean fileExisted = Files.exists(regionFile);

        if (!fileExisted) {
            Files.createFile(regionFile);
        }

        seekableStream = Files.newByteChannel(regionFile, WRITE, READ);

        if (!fileExisted) {
            byte[] write = new byte[CHUNKS_PER_REGION * 8];

            seekableStream.write(ByteBuffer.wrap(write));
        }

        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            ByteBuffer header = BufferUtils.createByteBuffer(8);
            seekableStream.position(i * 8);
            seekableStream.read(header);

            lookupTable.put(i, new ChunkHeader(header.getInt(0), header.getInt(4), i * 8));
        }
    }

    ChunkHeader getChunkHeader(Pos2 pos) throws IOException {
        return lookupTable.get((pos.x & 31) | ((pos.z & 31) << 5));
    }

    CachedChunk getChunk(Pos2 pos) throws IOException {
        ChunkHeader header = getChunkHeader(pos);
        if (!header.chunkExists()) {
            return null;
        }

        seekableStream.position(header.offset);
        ByteBuffer chunkData = BufferUtils.createByteBuffer(header.size);
        seekableStream.read(chunkData);
        chunkData.flip();

        ByteBuf buffer = Unpooled.buffer();
        buffer.writeBytes(chunkData);
        buffer.readerIndex(0);

        // logger.info("Loading chunk ({} bytes) at offset {}", buffer.readableBytes(), header.offset);

        try {
            FriendlyByteBuf newBuffer = new FriendlyByteBuf(buffer);
            ClientboundLevelChunkWithLightPacket chunkPacket = new ClientboundLevelChunkWithLightPacket(newBuffer);

            return new CachedChunk(pos, chunkPacket);
        } catch (Exception e) {
            logger.warn("Malformed chunk at {}", pos.toString());
            return null;
        }
    }

    void writeHeader(ChunkHeader head) throws IOException {
        //lookupTable.put(head.headerOffset/8, head);

        seekableStream.position(head.headerOffset);

        ByteBuffer header = BufferUtils.createByteBuffer(8);
        header.putInt(head.offset);
        header.putInt(head.size);

        seekableStream.write((ByteBuffer)header.flip());
    }

    void writeChunk(CachedChunk chunk) throws IOException {
        ChunkHeader header = getChunkHeader(chunk.pos);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        try {
            chunk.packet.write(buffer);
        } catch (Exception e) {
            logger.warn("Packet error chunk at {}: {}", chunk.pos.toString(), e.toString());
            return;
        }
        
        int size = buffer.readableBytes();

        if (header.chunkExists()) {
            // TODO: this doesn't actually work
            if (size > header.size) {
                //logger.info("Skipping region {} because {} < {}", chunk.toString(), header.size, chunk.packet.getExtractedSize());
            } else {
                //seekableStream.position(header.offset);

                //ByteBuffer chunkData = ByteBuffer.allocate(size);
                //PacketBuffer pbuffer = new PacketBuffer(Unpooled.wrappedBuffer(chunkData));

                //seekableStream.position(header.offset);
                //chunk.packet.writePacketData(pbuffer);

                //seekableStream.write((ByteBuffer)chunkData.flip());
                //writeHeader(header);
            }
        } else {
            ChunkHeader max = new ChunkHeader(0, CHUNKS_PER_REGION * 8, -1);
            for (int i = 0; i < CHUNKS_PER_REGION; i++) {
                ChunkHeader head = lookupTable.get(i);
                if (head.offset > max.offset && head.chunkExists()) {
                    max = head;
                }
            }

            seekableStream.position(max.offset + max.size);

            header.size = size;
            header.offset = max.offset + max.size;

            // logger.info("Writing chunk ({} bytes) at offset {}", size, header.offset);
            seekableStream.write(buffer.nioBuffer());

            writeHeader(header);
        }
    }

    @Override
    public void close() throws IOException {
        seekableStream.close();
    }
}
