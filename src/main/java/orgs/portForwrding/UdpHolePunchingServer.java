package orgs.portForwrding;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * UdpHolePunchingServer.java
 * This server acts as a signaling server for UDP hole punching.
 * It listens for TCP connections from clients, collects their UDP endpoint information
 * (local and observed public IP/port), and then exchanges this information between two connected clients.
 * It does NOT handle UDP traffic itself; it only facilitates the initial handshake.
 *
 * To run:
 * 1. Compile: javac UdpHolePunchingServer.java
 * 2. Run: java UdpHolePunchingServer
 */
public class UdpHolePunchingServer {
    private static final int SERVER_PORT = 8888; // Port for TCP signaling
    private static final int MAX_CLIENTS = 2; // This example works with exactly two clients
    private static final ConcurrentHashMap<Integer, ClientInfo> connectedClients = new ConcurrentHashMap<>();
    private static final AtomicInteger clientIdCounter = new AtomicInteger(0); // Simple ID generator

    public static void main(String[] args) {
        System.out.println("UDP Hole Punching Signaling Server started on port " + SERVER_PORT);
        try (ServerSocket serverSocket = new ServerSocket(SERVER_PORT)) {
            while (true) {
                // Wait for two clients to connect
                if (connectedClients.size() < MAX_CLIENTS) {
                    Socket clientSocket = serverSocket.accept();
                    int clientId = clientIdCounter.incrementAndGet();
                    System.out.println("Client " + clientId + " connected from " + clientSocket.getInetAddress().getHostAddress() + ":" + clientSocket.getPort());
                    new ClientHandler(clientSocket, clientId).start();
                } else {
                    // For this simple example, we'll just wait for the current pair to finish
                    // In a real application, you'd manage multiple pairs or a lobby.
                    try {
                        Thread.sleep(1000); // Prevent busy-waiting
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Represents the UDP endpoint information for a client.
     */
    static class ClientInfo {
        public int clientId;
        public String localIp;
        public int localPort;
        public String publicIp; // IP as seen by the server (client's public IP)
        public int publicPort; // Port as seen by the server (client's public port)

        public ClientInfo(int clientId, String localIp, int localPort, String publicIp, int publicPort) {
            this.clientId = clientId;
            this.localIp = localIp;
            this.localPort = localPort;
            this.publicIp = publicIp;
            this.publicPort = publicPort;
        }

        @Override
        public String toString() {
            return "ClientInfo{" +
                    "clientId=" + clientId +
                    ", localIp='" + localIp + '\'' +
                    ", localPort=" + localPort +
                    ", publicIp='" + publicIp + '\'' +
                    ", publicPort=" + publicPort +
                    '}';
        }
    }

    /**
     * Handles a single client's TCP connection to exchange UDP endpoint information.
     */
    private static class ClientHandler extends Thread {
        private Socket clientSocket;
        private int clientId;
        private BufferedReader in;
        private PrintWriter out;

        public ClientHandler(Socket socket, int clientId) {
            this.clientSocket = socket;
            this.clientId = clientId;
            try {
                in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
                out = new PrintWriter(clientSocket.getOutputStream(), true);
            } catch (IOException e) {
                System.err.println("Error setting up streams for client " + clientId + ": " + e.getMessage());
            }
        }

        @Override
        public void run() {
            try {
                // 1. Client sends its local and public UDP info
                String clientUdpInfoJson = in.readLine();
                if (clientUdpInfoJson == null) {
                    System.out.println("Client " + clientId + " disconnected prematurely.");
                    return;
                }

                // Parse the client's UDP info
                // Expected format for IPv4: "localIp:localPort:publicIp:publicPort"
                String[] parts = clientUdpInfoJson.split(":");
                if (parts.length != 4) { // Ensure exactly 4 parts for IPv4
                    System.err.println("Invalid UDP info format from client " + clientId + ". Expected IPv4 format. Received: " + clientUdpInfoJson);
                    return;
                }

                String localIp = parts[0];
                int localPort = Integer.parseInt(parts[1]);
                String publicIp = parts[2];
                int publicPort = Integer.parseInt(parts[3]);

                ClientInfo currentClientInfo = new ClientInfo(clientId, localIp, localPort, publicIp, publicPort);
                connectedClients.put(clientId, currentClientInfo);
                System.out.println("Received info from Client " + clientId + ": " + currentClientInfo);

                // 2. Wait until two clients are connected
                while (connectedClients.size() < MAX_CLIENTS) {
                    try {
                        Thread.sleep(100); // Wait for the second client
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        System.out.println("ClientHandler " + clientId + " interrupted while waiting for second client.");
                        return;
                    }
                }

                // 3. Exchange info between the two clients
                // This simplified server assumes exactly two clients for a direct exchange.
                // In a real system, you'd manage multiple pairs or a lobby.
                ClientInfo otherClientInfo = null;
                for (ClientInfo info : connectedClients.values()) {
                    if (info.clientId != this.clientId) {
                        otherClientInfo = info;
                        break;
                    }
                }

                if (otherClientInfo != null) {
                    System.out.println("Exchanging info for Client " + clientId + " and Client " + otherClientInfo.clientId);
                    // Send the other client's info to the current client
                    String otherClientInfoString = otherClientInfo.localIp + ":" + otherClientInfo.localPort + ":" +
                            otherClientInfo.publicIp + ":" + otherClientInfo.publicPort;
                    out.println(otherClientInfoString);
                    System.out.println("Sent to Client " + clientId + ": " + otherClientInfoString);
                } else {
                    out.println("ERROR: No other client found.");
                    System.err.println("Error: No other client found for exchange with client " + clientId);
                }

            } catch (IOException e) {
                System.err.println("Client " + clientId + " handler error: " + e.getMessage());
            } finally {
                try {
                    // Clean up resources and remove client from map
                    if (in != null) in.close();
                    if (out != null) out.close();
                    if (clientSocket != null) clientSocket.close();
                    connectedClients.remove(clientId); // Remove after exchange
                    System.out.println("Client " + clientId + " disconnected and resources closed.");
                    // Reset counter if all clients are gone for next pair
                    if (connectedClients.isEmpty()) {
                        clientIdCounter.set(0);
                        System.out.println("All clients disconnected. Server ready for new pair.");
                    }
                } catch (IOException e) {
                    System.err.println("Error closing client socket for " + clientId + ": " + e.getMessage());
                }
            }
        }
    }
}
