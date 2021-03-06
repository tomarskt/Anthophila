package me.ilyamirin.anthophila.server;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.collections.map.LinkedMap;
import org.apache.commons.collections.map.MultiKeyMap;

/**
 * @author ilyamirin
 */
@Slf4j
public class ServerStorage {

    public static final int CHUNK_LENGTH = 65536;
    public static final int KEY_LENGTH = 16; //md5 hash length (16 bytes)
    public static final int AUX_CHUNK_INFO_LENGTH = 1 + KEY_LENGTH + 4; //tombstone + hash + chunk length (int)
    public static final int IV_LENGTH = 8; //IV for Salsa cipher
    public static final int ENCRYPTION_CHUNK_INFO_LENGTH = 4 + IV_LENGTH; //cipher key has in int + IV
    public static final int WHOLE_CHUNK_WITH_META_LENGTH = AUX_CHUNK_INFO_LENGTH + ENCRYPTION_CHUNK_INFO_LENGTH + CHUNK_LENGTH; //total chunk with meta space

    private ServerParams params;

    private FileChannel fileChannel;

    private ServerEnigma enigma;

    private MultiKeyMap mainIndex;

    private List<ServerIndexEntry> condemnedIndex = new ArrayList<>();

    private ServerStorage(FileChannel fileChannel, ServerEnigma enigma, ServerParams params, MultiKeyMap mainIndex) {
        this.fileChannel = fileChannel;
        this.enigma = enigma;
        this.params = params;
        this.mainIndex = mainIndex;
    }

    public static ServerStorage newServerStorage(ServerParams params, ServerEnigma serverEnigma) throws IOException {
        RandomAccessFile randomAccessFile = new RandomAccessFile(params.getStorageFile(), "rw");
        FileChannel fileChannel = randomAccessFile.getChannel();
        MultiKeyMap mainIndex = MultiKeyMap.decorate(new LinkedMap(params.getInitialIndexSize()));
        ServerStorage serverStorage = new ServerStorage(fileChannel, serverEnigma, params, mainIndex);
        return serverStorage;
    }

    public boolean contains(ByteBuffer key) {
        return mainIndex.containsKey(key.getInt(0), key.getInt(4), key.getInt(8), key.getInt(12));
    }

    public synchronized void append(ByteBuffer key, ByteBuffer chunk) throws IOException {
        if (contains(key)) {
            return;
        }

        ByteBuffer byteBuffer = ByteBuffer.allocate(AUX_CHUNK_INFO_LENGTH + ENCRYPTION_CHUNK_INFO_LENGTH + CHUNK_LENGTH)
                .put(Byte.MAX_VALUE) //tombstone is off
                .put(key.array()) //chunk hash
                .putInt(chunk.array().length); //chunk length

        if (params.isEncrypt()) {
            ServerEnigma.EncryptedChunk encryptedChunk = enigma.encrypt(chunk);
            byteBuffer
                    .putInt(encryptedChunk.getKeyHash()) //key hash
                    .put(encryptedChunk.getIV()) //IV
                    .put(encryptedChunk.getChunk().array()); //encrypted chunk
        } else {
            byteBuffer
                    .putInt(0) //empty key hash
                    .put(new byte[IV_LENGTH]) //empty IV
                    .put(chunk.array()); //chunk itself
        }

        byteBuffer.rewind();

        if (condemnedIndex.isEmpty()) {
            long chunkFirstBytePosition = fileChannel.size() + AUX_CHUNK_INFO_LENGTH;
            while (byteBuffer.hasRemaining()) {
                fileChannel.write(byteBuffer, fileChannel.size());
            }
            ServerIndexEntry entry = new ServerIndexEntry(chunkFirstBytePosition, chunk.array().length);
            mainIndex.put(key.getInt(0), key.getInt(4), key.getInt(8), key.getInt(12), entry);

        } else {
            ServerIndexEntry entry = condemnedIndex.get(0);
            while (byteBuffer.hasRemaining()) {
                fileChannel.write(byteBuffer, entry.getChunkPosition() - AUX_CHUNK_INFO_LENGTH);
            }
            condemnedIndex.remove(0);
            entry.setChunkLength(chunk.array().length);
            mainIndex.put(key.getInt(0), key.getInt(4), key.getInt(8), key.getInt(12), entry);
        }
    }

    public synchronized ByteBuffer read(ByteBuffer key) throws IOException {
        ServerIndexEntry indexEntry = (ServerIndexEntry) mainIndex.get(key.getInt(0), key.getInt(4), key.getInt(8), key.getInt(12));

        if (indexEntry == null) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.allocate(ENCRYPTION_CHUNK_INFO_LENGTH);
        while (buffer.hasRemaining()) {
            fileChannel.read(buffer, indexEntry.getChunkPosition());
        }

        Integer keyHash = buffer.getInt(0);

        byte[] IV = new byte[IV_LENGTH];
        buffer.position(4);
        buffer.get(IV);

        ByteBuffer chunk = ByteBuffer.allocate(indexEntry.getChunkLength());
        while (chunk.hasRemaining()) {
            fileChannel.read(chunk, indexEntry.getChunkPosition() + ENCRYPTION_CHUNK_INFO_LENGTH);
        }

        if (keyHash == 0) {
            return chunk;
        } else {
            ServerEnigma.EncryptedChunk encryptedChunk = new ServerEnigma.EncryptedChunk(keyHash, IV, chunk);
            return enigma.decrypt(encryptedChunk);
        }
    }

    public synchronized void delete(ByteBuffer key) throws IOException {
        ServerIndexEntry indexEntry = (ServerIndexEntry) mainIndex.remove(key.getInt(0), key.getInt(4), key.getInt(8), key.getInt(12));
        if (indexEntry != null) {
            long tombstonePosition = indexEntry.getChunkPosition() - AUX_CHUNK_INFO_LENGTH;
            fileChannel.write(ByteBuffer.allocate(1).put(Byte.MIN_VALUE), tombstonePosition);
            condemnedIndex.add(indexEntry);
        }
    }

    public BloomFilter loadExistedStorage() throws IOException {
        log.info("Start loading data from existed database file.");

        ByteBuffer buffer = ByteBuffer.allocate(AUX_CHUNK_INFO_LENGTH);
        long chunksSuccessfullyLoaded = 0;
        long position = 0;

        BloomFilter<byte[]> filter = BloomFilter.create(Funnels.byteArrayFunnel(), params.getMaxExpectedSize(), 0.01);
        
        while (fileChannel.read(buffer, position) > 0) {
            buffer.rewind();

            byte tombstone = buffer.get();

            byte[] keyArray = new byte[KEY_LENGTH];
            buffer.get(keyArray);
            ByteBuffer key = ByteBuffer.wrap(keyArray);

            int chunkLength = buffer.getInt();
            long chunkPosition = position + AUX_CHUNK_INFO_LENGTH;

            ServerIndexEntry indexEntry = new ServerIndexEntry(chunkPosition, chunkLength);

            if (tombstone == Byte.MAX_VALUE) {
                mainIndex.put(key.getInt(0), key.getInt(4), key.getInt(8), key.getInt(12), indexEntry);
                filter.put(keyArray);
            } else {
                condemnedIndex.add(indexEntry);
            }

            position = chunkPosition + ENCRYPTION_CHUNK_INFO_LENGTH + CHUNK_LENGTH;

            buffer.clear();

            if (++chunksSuccessfullyLoaded % 1000 == 0) {
                log.info("{} chunks were successfully loaded", chunksSuccessfullyLoaded);
            }
        }//while

        log.info("{} chunks were successfully loaded", chunksSuccessfullyLoaded);

        return filter;
    }//loadExistedStorage
}
