package  orgs.utils ;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

/**
 * A simple Java client to perform a STUN (Session Traversal Utilities for NAT)
 * Binding Request and retrieve the public IP address and port.
 *
 * This version refactors the STUN logic into a reusable method.
 */
public class StunClient {

    // Well-known public STUN server (Google's STUN server)
    private static final String STUN_SERVER_HOST = "stun.l.google.com";
    private static final int STUN_SERVER_PORT = 19302;
    private static final int STUN_TIMEOUT_MS = 5000; // 5 seconds timeout for STUN response

    // STUN Message Type: Binding Request (0x0001)
    private static final short BINDING_REQUEST_TYPE = 0x0001;
    // STUN Message Type: Binding Response Success (0x0101)
    private static final short BINDING_SUCCESS_RESPONSE_TYPE = 0x0101;
    // STUN Magic Cookie (0x2112A442)
    private static final int MAGIC_COOKIE = 0x2112A442;

    // STUN Attribute Type: MAPPED-ADDRESS (0x0001) - Older RFC 3489
    private static final short MAPPED_ADDRESS_TYPE = 0x0001;
    // STUN Attribute Type: XOR-MAPPED-ADDRESS (0x0020) - RFC 5389 (more common for modern STUN)
    private static final short XOR_MAPPED_ADDRESS_TYPE = 0x0020;

    public static void main(String[] args) {
        System.out.println("Starting STUN client to discover public IP and port...");
        System.out.println("STUN Server: " + STUN_SERVER_HOST + ":" + STUN_SERVER_PORT);

        try (DatagramSocket socket = new DatagramSocket()) {
            // You can optionally bind the socket to a specific local port if needed,
            // otherwise, the OS will assign a random ephemeral port.
            // socket.bind(new InetSocketAddress(0)); // Binds to any available local port

            System.out.println("Local Socket Bound to: " + socket.getLocalSocketAddress());

            InetSocketAddress publicAddress = getPublicAddress(socket);

            if (publicAddress != null) {
                System.out.println("\n--- STUN Discovery Successful ---");
                System.out.println("Your Public IP Address: " + publicAddress.getAddress().getHostAddress());
                System.out.println("Your Public Port: " + publicAddress.getPort());
                System.out.println("---------------------------------");
            } else {
                System.out.println("STUN discovery failed: Could not determine public address.");
            }

        } catch (SocketTimeoutException e) {
            System.err.println("Error: STUN server did not respond within the timeout period. " +
                    "This might be due to a strict firewall, network issues, or the STUN server being unreachable.");
        } catch (SocketException e) {
            System.err.println("Error creating or using UDP socket: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Network I/O error: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("An unexpected error occurred: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("STUN client finished.");
    }

    /**
     * Connects to a STUN server using the provided DatagramSocket to discover
     * the client's public IP address and port.
     *
     * @param socket The DatagramSocket to use for STUN communication.
     * It should be open and ready to send/receive.
     * @return An InetSocketAddress containing the discovered public IP and port,
     * or null if the public address could not be determined.
     * @throws IOException if a network I/O error occurs during communication.
     * @throws Exception if the STUN response is malformed or invalid.
     */
    public static InetSocketAddress getPublicAddress(DatagramSocket socket) throws IOException, Exception {
        socket.setSoTimeout(STUN_TIMEOUT_MS); // Set timeout for receiving responses

        byte[] transactionId = generateTransactionId();
        byte[] requestBytes = createStunBindingRequest(transactionId);

        InetAddress serverAddress = InetAddress.getByName(STUN_SERVER_HOST);
        DatagramPacket requestPacket = new DatagramPacket(requestBytes, requestBytes.length, serverAddress, STUN_SERVER_PORT);

        System.out.println("Sending STUN Binding Request...");
        socket.send(requestPacket);

        byte[] responseBuffer = new byte[1024];
        DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);

        System.out.println("Waiting for STUN Binding Response...");
        socket.receive(responsePacket);

        System.out.println("Received STUN Binding Response.");
        // Print raw response bytes for debugging, limiting to first 64 bytes for brevity
        System.out.println("Raw response bytes (first " + Math.min(responsePacket.getLength(), 64) + " bytes): " + bytesToHex(Arrays.copyOfRange(responsePacket.getData(), 0, Math.min(responsePacket.getLength(), 64))));

        return parseStunBindingResponse(responsePacket.getData(), responsePacket.getLength(), transactionId);
    }

    /**
     * Generates a 12-byte (96-bit) random transaction ID for the STUN request.
     * The transaction ID helps match responses to requests.
     * @return A byte array representing the transaction ID.
     */
    private static byte[] generateTransactionId() {
        byte[] transactionId = new byte[12];
        new Random().nextBytes(transactionId);
        return transactionId;
    }

    /**
     * Creates a STUN Binding Request packet.
     * The structure is:
     * - Message Type (2 bytes)
     * - Message Length (2 bytes) - length of attributes, 0 for basic request
     * - Magic Cookie (4 bytes)
     * - Transaction ID (12 bytes)
     * @param transactionId The 12-byte transaction ID for this request.
     * @return A byte array representing the STUN Binding Request.
     */
    private static byte[] createStunBindingRequest(byte[] transactionId) {
        ByteBuffer buffer = ByteBuffer.allocate(20); // STUN header is 20 bytes

        // Message Type: Binding Request (0x0001)
        buffer.putShort(BINDING_REQUEST_TYPE);

        // Message Length: 0 (no attributes for a basic binding request)
        buffer.putShort((short) 0);

        // Magic Cookie (0x2112A442)
        buffer.putInt(MAGIC_COOKIE);

        // Transaction ID (12 bytes)
        buffer.put(transactionId);

        return buffer.array();
    }

    /**
     * Parses a STUN Binding Response packet to extract the MAPPED-ADDRESS or XOR-MAPPED-ADDRESS attribute.
     * @param responseBytes The raw bytes of the STUN response.
     * @param length The actual length of the received response.
     * @param expectedTransactionId The transaction ID from the original request, used for validation.
     * @return An InetSocketAddress containing the public IP and port, or null if not found.
     * @throws Exception if the response is malformed or invalid.
     */
    private static InetSocketAddress parseStunBindingResponse(byte[] responseBytes, int length, byte[] expectedTransactionId) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(responseBytes, 0, length);

        // Read STUN Header
        short messageType = buffer.getShort();
        short messageLength = buffer.getShort();
        int magicCookie = buffer.getInt();
        byte[] transactionId = new byte[12];
        buffer.get(transactionId);

        System.out.println("STUN Response Header:");
        System.out.println("  Message Type: " + String.format("0x%04X", messageType));
        System.out.println("  Message Length: " + messageLength + " bytes (attributes only)");
        System.out.println("  Magic Cookie: " + String.format("0x%08X", magicCookie));
        System.out.println("  Transaction ID: " + bytesToHex(transactionId));

        // Validate STUN header
        if (messageType != BINDING_SUCCESS_RESPONSE_TYPE) {
            throw new Exception("STUN Response: Unexpected message type. Expected 0x0101 (Binding Success), Got: " + String.format("0x%04X", messageType));
        }
        if (magicCookie != MAGIC_COOKIE) {
            throw new Exception("STUN Response: Invalid Magic Cookie. Expected: " + String.format("0x%08X", MAGIC_COOKIE) +
                    ", Got: " + String.format("0x%08X", magicCookie));
        }
        if (!Arrays.equals(transactionId, expectedTransactionId)) {
            throw new Exception("STUN Response: Transaction ID mismatch. Expected: " + bytesToHex(expectedTransactionId) +
                    ", Got: " + bytesToHex(transactionId));
        }

        InetAddress publicIp = null;
        int publicPort = -1;

        // Parse Attributes
        int bytesParsed = 0; // Bytes parsed within the attribute section
        while (bytesParsed < messageLength) {
            short attributeType = buffer.getShort();
            short attributeLength = buffer.getShort();

            System.out.println("  Parsing Attribute: Type=0x" + String.format("%04X", attributeType) + ", Length=" + attributeLength + " bytes");

            if (attributeType == MAPPED_ADDRESS_TYPE) {
                // MAPPED-ADDRESS attribute format:
                // 1 byte: Family (0x01 for IPv4)
                // 1 byte: Padding (0x00)
                // 2 bytes: Port
                // 4 bytes: IP Address
                buffer.get(); // Family (skip, assume IPv4 for this example)
                buffer.get(); // Padding (skip)
                publicPort = Short.toUnsignedInt(buffer.getShort()); // Convert signed short to unsigned int for port
                byte[] ipBytes = new byte[4];
                buffer.get(ipBytes);
                publicIp = InetAddress.getByAddress(ipBytes);
                // Found it, but continue parsing in case there are other attributes,
                // though for this specific task, we can break.
                // For simplicity, we'll return the first one found that's valid.
                break;
            } else if (attributeType == XOR_MAPPED_ADDRESS_TYPE) {
                // XOR-MAPPED-ADDRESS attribute format:
                // 1 byte: Family (0x01 for IPv4)
                // 1 byte: Padding (0x00)
                // 2 bytes: XORed Port (Port XOR (Magic Cookie >> 16))
                // 4 bytes: XORed IP Address (IP Address XOR Magic Cookie)
                buffer.get(); // Family (skip, assume IPv4 for this example)
                buffer.get(); // Padding (skip)
                int xoredPort = Short.toUnsignedInt(buffer.getShort());
                byte[] xoredIpBytes = new byte[4];
                buffer.get(xoredIpBytes);

                // De-XOR the port
                publicPort = xoredPort ^ (MAGIC_COOKIE >>> 16);

                // De-XOR the IP address
                ByteBuffer ipBuffer = ByteBuffer.wrap(xoredIpBytes);
                int xoredIpInt = ipBuffer.getInt();
                int ipInt = xoredIpInt ^ MAGIC_COOKIE;
                byte[] ipBytes = ByteBuffer.allocate(4).putInt(ipInt).array();
                publicIp = InetAddress.getByAddress(ipBytes);
                // Found it, break.
                break;
            } else {
                // Skip unknown attributes
                buffer.position(buffer.position() + attributeLength);
            }

            // Attributes must be padded to a 4-byte boundary
            int padding = (4 - (attributeLength % 4)) % 4;
            buffer.position(buffer.position() + padding);
            bytesParsed += (4 + attributeLength + padding); // 4 bytes for type+length, then attribute data, then padding
        }

        if (publicIp != null && publicPort != -1) {
            return new InetSocketAddress(publicIp, publicPort);
        } else {
            System.out.println("No MAPPED-ADDRESS or XOR-MAPPED-ADDRESS attribute found in STUN response.");
            return null;
        }
    }

    /**
     * Helper method to convert byte array to hex string for logging.
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
