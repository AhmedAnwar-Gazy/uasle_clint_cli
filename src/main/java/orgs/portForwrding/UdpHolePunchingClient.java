package orgs.portForwrding;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.*;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UdpHolePunchingClient.java
 * This client demonstrates UDP hole punching.
 * It connects to a signaling server (UdpHolePunchingServer), exchanges UDP endpoint information,
 * and then attempts to establish a direct UDP connection with another peer.
 *
 * To run:
 * 1. Compile: javac UdpHolePunchingClient.java
 * 2. Run two instances: java UdpHolePunchingClient
 * (Ensure the UdpHolePunchingServer is running first)
 */
public class UdpHolePunchingClient {
    private static final String SERVER_IP = "192.168.1.99"; // IP of the signaling server
    private static final int SERVER_PORT = 8888; // Port of the signaling server (TCP)

    private DatagramSocket udpSocket;
    private InetAddress localUdpIp; // This client's local IPv4 address
    private int localUdpPort;
    private String publicIp; // This client's public IP
    private int publicPort; // This client's public port (as seen by the server)

    private InetAddress peerLocalIp;
    private int peerLocalPort;
    private InetAddress peerPublicIp;
    private int peerPublicPort;

    private AtomicBoolean connectedToPeer = new AtomicBoolean(false);
    private JTextArea logArea;
    private JFrame frame;

    public UdpHolePunchingClient() {
        // Initialize UI
        frame = new JFrame("UDP Hole Punching Client");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 400);
        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);
        frame.add(scrollPane);
        frame.setVisible(true);

        log("Client started.");
    }

    public void startClient() {
        try {
            // 1. Initialize local UDP socket and get a suitable local IPv4 address
            localUdpIp = getIPv4Address();
            if (localUdpIp == null) {
                log("Failed to find a suitable local IPv4 address. Exiting.");
                return;
            }
            udpSocket = new DatagramSocket(0, localUdpIp); // Binds to any available port on the specified IP
            localUdpPort = udpSocket.getLocalPort();
            log("Local UDP endpoint: " + localUdpIp.getHostAddress() + ":" + localUdpPort);

            // 2. Discover public IP address
            publicIp = getPublicIpAddress();
            if (publicIp == null) {
                log("Failed to determine public IP. Exiting.");
                return;
            }
            log("Discovered public IP: " + publicIp);

            // 3. Connect to signaling server (TCP)
            log("Connecting to signaling server at " + SERVER_IP + ":" + SERVER_PORT);
            try (Socket tcpSocket = new Socket(SERVER_IP, SERVER_PORT);
                 BufferedReader in = new BufferedReader(new InputStreamReader(tcpSocket.getInputStream()));
                 PrintWriter out = new PrintWriter(tcpSocket.getOutputStream(), true)) {

                // The server sees our public port as the source port of this TCP connection.
                // We assume for simplicity that the public UDP port will be the same or close to this.
                // In a real STUN client, you'd send a UDP packet to the STUN server to get the mapped UDP port.
                // For this demo, we'll just use the local UDP port and assume NAT will map it consistently.
                // This is a simplification and might not work with all NAT types.
                publicPort = localUdpPort; // Simplification for demo

                // Send our local and public UDP info to the server
                String myUdpInfo = localUdpIp.getHostAddress() + ":" + localUdpPort + ":" + publicIp + ":" + publicPort;
                out.println(myUdpInfo);
                log("Sent my UDP info to server: " + myUdpInfo);

                // Receive peer's UDP info from the server
                String peerUdpInfo = in.readLine();
                if (peerUdpInfo == null || peerUdpInfo.startsWith("ERROR")) {
                    log("Failed to get peer info from server: " + peerUdpInfo);
                    return;
                }

                // Parse peer's UDP info
                // Expected format: "localIp:localPort:publicIp:publicPort"
                String[] parts = peerUdpInfo.split(":");
                if (parts.length != 4) {
                    log("Invalid peer UDP info format. Expected IPv4 format. Received: " + peerUdpInfo);
                    return;
                }

                peerLocalIp = InetAddress.getByName(parts[0]);
                peerLocalPort = Integer.parseInt(parts[1]);
                peerPublicIp = InetAddress.getByName(parts[2]);
                peerPublicPort = Integer.parseInt(parts[3]);

                log("Received peer UDP info: Local=" + peerLocalIp.getHostAddress() + ":" + peerLocalPort +
                        ", Public=" + peerPublicIp.getHostAddress() + ":" + peerPublicPort);

            } // TCP socket closes here

            // 4. Start UDP listener thread
            new Thread(this::udpListener, "UDP-Listener").start();

            // 5. Start UDP hole punching (sending packets)
            // Send packets to both the peer's public and local addresses.
            // This is the "punching" part.
            log("Starting UDP hole punching...");
            String message = "Hole Punching Packet from " + publicIp + ":" + publicPort;
            byte[] data = message.getBytes();
            DatagramPacket packetToPublic = new DatagramPacket(data, data.length, peerPublicIp, peerPublicPort);
            DatagramPacket packetToLocal = new DatagramPacket(data, data.length, peerLocalIp, peerLocalPort); // Often same as public if on same LAN

            // Send packets repeatedly for a short period to ensure holes are punched
            for (int i = 0; i < 20 && !connectedToPeer.get(); i++) { // Try 20 times or until connected
                try {
                    udpSocket.send(packetToPublic);
                    log("Sent packet to peer's public: " + peerPublicIp.getHostAddress() + ":" + peerPublicPort);
                    // Also send to local, in case both clients are on the same LAN
                    if (!peerPublicIp.equals(peerLocalIp) || peerPublicPort != peerLocalPort) {
                        udpSocket.send(packetToLocal);
                        log("Sent packet to peer's local: " + peerLocalIp.getHostAddress() + ":" + peerLocalPort);
                    }
                    Thread.sleep(500); // Wait a bit before next send
                } catch (IOException | InterruptedException e) {
                    log("Error during punching: " + e.getMessage());
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (connectedToPeer.get()) {
                log("UDP hole punched! Direct communication established.");
                // Now you can send and receive messages directly
                startChatInput();
            } else {
                log("UDP hole punching failed. Could not establish direct connection.");
            }

        } catch (IOException e) {
            log("Client startup error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
                log("UDP socket closed.");
            }
        }
    }

    /**
     * Listener thread for incoming UDP packets.
     */
    private void udpListener() {
        byte[] buffer = new byte[1024];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        log("UDP Listener started on port " + localUdpPort);

        try {
            while (!udpSocket.isClosed()) {
                udpSocket.receive(packet);
                String receivedMessage = new String(packet.getData(), 0, packet.getLength());
                log("Received UDP from " + packet.getAddress().getHostAddress() + ":" + packet.getPort() + ": " + receivedMessage);

                if (!connectedToPeer.get()) {
                    connectedToPeer.set(true); // Mark as connected once a packet is received
                    log("Successfully received first packet. Direct connection established!");
                }
            }
        } catch (SocketException e) {
            log("UDP Listener socket closed.");
        } catch (IOException e) {
            log("Error in UDP Listener: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Prompts the user for messages to send to the peer via UDP.
     */
    private void startChatInput() {
        SwingUtilities.invokeLater(() -> {
            JTextField inputField = new JTextField(40);
            JButton sendButton = new JButton("Send");

            sendButton.addActionListener(e -> {
                String message = inputField.getText();
                if (!message.isEmpty()) {
                    sendMessageToPeer(message);
                    inputField.setText("");
                }
            });

            JPanel inputPanel = new JPanel();
            inputPanel.add(inputField);
            inputPanel.add(sendButton);

            frame.add(inputPanel, "South");
            frame.revalidate();
            frame.repaint();

            log("You can now send messages directly to the peer.");
        });
    }

    /**
     * Sends a UDP message to the peer.
     * @param message The message to send.
     */
    private void sendMessageToPeer(String message) {
        try {
            String fullMessage = "Client Message: " + message;
            byte[] data = fullMessage.getBytes();
            // Once connected, send only to the public IP/port that worked
            // (or both, for maximum robustness, though usually one path becomes preferred)
            DatagramPacket packet = new DatagramPacket(data, data.length, peerPublicIp, peerPublicPort);
            udpSocket.send(packet);
            log("Sent: " + message);
        } catch (IOException e) {
            log("Error sending message: " + e.getMessage());
        }
    }

    /**
     * Fetches the public IP address of the client by making an HTTP request to an external service.
     * @return The public IP address as a String, or null if it cannot be determined.
     */
    private String getPublicIpAddress() {
        try {
            URL url = new URL("http://checkip.amazonaws.com");
            try (BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()))) {
                String publicIp = in.readLine();
                return publicIp != null ? publicIp.trim() : null;
            }
        } catch (IOException e) {
            log("Error fetching public IP address: " + e.getMessage() + ". Falling back to local IP.");
            return getIPv4Address().getHostAddress(); // Fallback to the determined local IPv4
        }
    }

    /**
     * Attempts to find a non-loopback IPv4 address for the local machine.
     * @return An InetAddress representing a local IPv4 address, or null if none is found.
     */
    private InetAddress getIPv4Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr;
                    }
                }
            }
        } catch (SocketException e) {
            log("Error getting network interfaces: " + e.getMessage());
        }
        try {
            // Fallback to localhost if no suitable non-loopback address is found
            return InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            log("Error getting local host address: " + e.getMessage());
            return null;
        }
    }

    /**
     * Logs messages to the console and the Swing JTextArea.
     * @param message The message to log.
     */
    private void log(String message) {
        String fullMessage = "[" + System.currentTimeMillis() % 100000 + "] " + message;
        System.out.println(fullMessage);
        SwingUtilities.invokeLater(() -> logArea.append(fullMessage + "\n"));
    }

    public static void main(String[] args) {
        UdpHolePunchingClient client = new UdpHolePunchingClient();
        client.startClient();
    }
}
