package me.ilyamirin.anthophila.client;

import com.google.common.collect.Maps;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import me.ilyamirin.anthophila.common.Node;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import me.ilyamirin.anthophila.common.Topology;

/**
 * Created with IntelliJ IDEA. User: ilyamirin Date: 15.10.13 Time: 15:52 To
 * change this template use File | Settings | File Templates.
 */
@RequiredArgsConstructor
public class ClusterClient implements Client {

    @NonNull
    private Map<Node, OneNodeClient> clients;
    @NonNull
    private Topology topology;

    public static ClusterClient newClusterClient(Topology topology) throws IOException {
        Map<Node, OneNodeClient> clients = Maps.newHashMap();
        for (Node node : topology.getNodes().keySet()) {
            clients.put(node, OneNodeClient.newClient(node.getHost(), node.getPort(), Client.ConnectionType.OTHERS));
        }
        return new ClusterClient(clients, topology);
    }

    @Override
    public boolean push(ByteBuffer key, ByteBuffer chunk) throws IOException {
        List<Node> nodes = new ArrayList<>(topology.findNodes(key));
        Collections.shuffle(nodes);
        for (Node node : nodes) {
            if (clients.containsKey(node)) {
                return clients.get(node).push(key, chunk);
            }
        }
        throw new IOException("Can`t find proper node for key.");
    }

    @Override
    public ByteBuffer pull(ByteBuffer key) throws IOException {
        List<Node> nodes = new ArrayList<>(topology.findNodes(key));
        Collections.shuffle(nodes);
        for (Node node : nodes) {
            if (clients.containsKey(node)) {
                return clients.get(node).pull(key);
            }
        }
        throw new IOException("Can`t find proper node for key.");
    }

    @Override
    public boolean seek(ByteBuffer key) throws IOException {
        List<Node> nodes = new ArrayList<>(topology.findNodes(key));
        Collections.shuffle(nodes);
        for (Node node : nodes) {
            if (clients.containsKey(node)) {
                return clients.get(node).seek(key);
            }
        }
        throw new IOException("Can`t find proper node for key.");
    }

    @Override
    public boolean remove(ByteBuffer key) throws IOException {
        List<Node> nodes = new ArrayList<>(topology.findNodes(key));
        Collections.shuffle(nodes);
        for (Node node : nodes) {
            if (clients.containsKey(node)) {
                return clients.get(node).remove(key);
            }
        }
        throw new IOException("Can`t find proper node for key.");
    }

    @Override
    public void close() throws IOException {
        for (OneNodeClient client : clients.values()) {
            client.close();
        }
    }
}
