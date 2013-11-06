package me.ilyamirin.anthophila.store;

import java.nio.ByteBuffer;

/**
 *
 * @author ilyamirin
 */
public interface ChunkStore {

    boolean containsKey(ByteBuffer key);
    
    boolean put(ByteBuffer key, ByteBuffer chunk);

    ByteBuffer get(ByteBuffer key);

    boolean remove(ByteBuffer key);
}