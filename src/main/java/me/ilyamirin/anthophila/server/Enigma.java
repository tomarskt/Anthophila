package me.ilyamirin.anthophila.server;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.FileReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.bouncycastle.crypto.CipherParameters;
import org.bouncycastle.crypto.StreamCipher;
import org.bouncycastle.crypto.engines.Salsa20Engine;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;

/**
 *
 * @author ilyamirin
 */
public class Enigma {

    @Data
    @AllArgsConstructor
    public static class EncryptedChunk {

        private Integer keyHash;
        private byte[] IV;
        private ByteBuffer chunk;
    }
    public static final TypeToken<Map<String, Set<String>>> KEYS_JSON_TOKEN = new TypeToken<Map<String, Set<String>>>() {private static final long serialVersionUID = 1L;
    };
    public static final String SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz1234567890";

    private static Gson gson = new GsonBuilder().create();
    private Random r = new Random();
    private Map<Integer, String> oldKeys;
    private Map<Integer, String> keys;
    private List<Integer> newKeysHashes;

    private Enigma(Map<Integer, String> oldKeys, Map<Integer, String> keys, List<Integer> newKeysHashes) {
        this.oldKeys = oldKeys;
        this.keys = keys;
        this.newKeysHashes = newKeysHashes;
    }

    public static Enigma newEnigma(Set<String> keySet, Set<String> oldKeySet) {
        Map<Integer, String> oldKeys = Collections.synchronizedMap(new HashMap<Integer, String>());
        Map<Integer, String> keys = Collections.synchronizedMap(new HashMap<Integer, String>());
        List<Integer> newKeysHashes = Collections.synchronizedList(new ArrayList<Integer>());

        Integer hashCode;
        for (String oldKey : oldKeySet) {
            oldKeys.put(oldKey.hashCode(), oldKey);
        }

        for (String key : keySet) {
            keys.put(key.hashCode(), key);
            newKeysHashes.add(key.hashCode());
        }

        return new Enigma(oldKeys, keys, newKeysHashes);
    }

    public static Enigma loadFromFile(String filename) throws IOException {
        FileReader fileReader = new FileReader(filename);
        Map<String, Set<String>> keys = gson.fromJson(fileReader, KEYS_JSON_TOKEN.getType());
        return newEnigma(keys.get("newKeys"), keys.get("oldKeys"));
    }

    public static Map<Integer, String> generateKeys(int number) {
        Random r = new Random(number);
        Map<Integer, String> keys = Maps.newHashMap();
        for (int i = 0; i < number; i++) {
            String key = "";
            for (int j = 0; j < 256; j++) {
                int symbolPos = r.nextInt(SYMBOLS.length());
                key += SYMBOLS.substring(symbolPos, symbolPos + 1);
            }
            keys.put(key.hashCode(), key);
        }//for
        return keys;
    }

    public EncryptedChunk encrypt(ByteBuffer chunk) {
        //select random key
        Integer keyHash = newKeysHashes.get(r.nextInt(newKeysHashes.size()));
        String key = keys.get(keyHash);

        //generate random IV
        byte[] IV = new byte[Storage.IV_LENGTH];
        r.nextBytes(IV);

        StreamCipher cipher = new Salsa20Engine();
        CipherParameters cipherParameters = new ParametersWithIV(new KeyParameter(key.getBytes()), IV);
        cipher.init(true, cipherParameters);

        byte[] result = new byte[chunk.capacity()];
        cipher.processBytes(chunk.array(), 0, chunk.capacity(), result, 0);

        return new EncryptedChunk(keyHash, IV, ByteBuffer.wrap(result));
    }

    public ByteBuffer decrypt(EncryptedChunk encryptedChunk) {
        Integer keyHash = encryptedChunk.getKeyHash();
        String key = keys.containsKey(keyHash) ? keys.get(keyHash) : oldKeys.get(keyHash);

        StreamCipher cipher = new Salsa20Engine();
        CipherParameters cipherParameters = new ParametersWithIV(new KeyParameter(key.getBytes()), encryptedChunk.getIV());
        cipher.init(true, cipherParameters);

        byte[] result = new byte[encryptedChunk.getChunk().capacity()];
        cipher.processBytes(encryptedChunk.getChunk().array(), 0, encryptedChunk.getChunk().capacity(), result, 0);

        return ByteBuffer.wrap(result);
    }
}
