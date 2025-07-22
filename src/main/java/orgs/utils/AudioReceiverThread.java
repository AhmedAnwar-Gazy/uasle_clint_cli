//package orgs.utils;
//
//import javax.sound.sampled.*;
//import java.io.IOException;
//import java.net.DatagramPacket;
//import java.net.DatagramSocket;
//import java.util.concurrent.atomic.AtomicBoolean;
//
///**
// * Thread for receiving audio via UDP and playing it back.
// */
//public class AudioReceiverThread extends Thread {
//
//    private DatagramSocket udpSocket;
//    private AtomicBoolean running = new AtomicBoolean(true);
//    private SourceDataLine sourceDataLine; // Line for playing audio
//
//    // Audio format (must match capture format)
//    private static final float SAMPLE_RATE = 16000; // 16 kHz
//    private static final int SAMPLE_SIZE_IN_BITS = 16;
//    private static final int CHANNELS = 1; // Mono
//    private static final boolean SIGNED = true;
//    private static final boolean BIG_ENDIAN = false; // Little-endian is common for PCM
//
//    private int reseved = 0; // Simple frame counter
//
//    public AudioReceiverThread(DatagramSocket socket) {
//        this.udpSocket = socket;
//    }
//
//    public void stopReceiving() {
//        running.set(false);
//        if (sourceDataLine != null) {
//            sourceDataLine.stop();
//            sourceDataLine.close();
//            System.out.println("Audio playback line closed.");
//        }
//    }
//
//    @Override
//    public void run() {
//        System.out.println("Audio receiver thread started.");
//
//        AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, SIGNED, BIG_ENDIAN);
//        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
//
//        if (!AudioSystem.isLineSupported(info)) {
//            System.err.println("Audio playback line not supported: " + info);
//            running.set(false);
//            return;
//        }
//
//        try {
//            sourceDataLine = (SourceDataLine) AudioSystem.getLine(info);
//            sourceDataLine.open(format);
//            sourceDataLine.start(); // Start playing
//
//            // Calculate frame size based on the format
//            int frameSize = format.getFrameSize();
//            if (frameSize <= 0) { // Should not happen with valid format, but good to check
//                System.err.println("Invalid frame size: " + frameSize + ". Cannot proceed with audio playback.");
//                running.set(false);
//                return;
//            }
//
//            // Buffer size for receiving packets (should be large enough for a single audio packet)
//            byte[] buffer = new byte[2048]; // Max UDP packet size is ~65507, but audio packets are usually smaller
//
//            System.out.println("Audio playback started. Listening on port " + udpSocket.getLocalPort() + ". Frame size: " + frameSize + " bytes.");
//
//            while (running.get() && !udpSocket.isClosed()) {
//                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
//                try {
//                    udpSocket.receive(packet);
//                    int bytesReceived = packet.getLength();
//
//                    if (bytesReceived > 0) {
//                        // Ensure the number of bytes to write is a multiple of the frame size
//                        int bytesToWrite = bytesReceived - (bytesReceived % frameSize);
//
//                        if (bytesToWrite > 0) {
//                            sourceDataLine.write(packet.getData(), 0, bytesToWrite);
//                            System.out.println("--------- Received and played audio packet " + (++reseved) + " (" + bytesToWrite + " bytes)");
//                        } else {
//                            System.err.println("Received audio packet with non-integral number of frames. Discarding " + bytesReceived + " bytes.");
//                        }
//                    }
//                } catch (IOException e) {
//                    if (running.get() && !udpSocket.isClosed()) {
//                        System.err.println("Error receiving audio packet: " + e.getMessage());
//                    }
//                }
//            }
//        } catch (LineUnavailableException e) {
//            System.err.println("Speaker line unavailable: " + e.getMessage());
//            e.printStackTrace();
//        } catch (SecurityException e) {
//            System.err.println("Security exception: Permission to access speakers denied. " + e.getMessage());
//            e.printStackTrace();
//        } catch (Exception e) {
//            System.err.println("An unexpected error occurred in audio reception: " + e.getMessage());
//            e.printStackTrace();
//        } finally {
//            if (sourceDataLine != null && sourceDataLine.isOpen()) {
//                sourceDataLine.stop();
//                sourceDataLine.close();
//            }
//            System.out.println("Audio receiver thread stopped.");
//        }
//    }
//}
package orgs.utils;

import javax.sound.sampled.*;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread for receiving audio via UDP and playing it back.
 * Implements a simple jitter buffer for smoother playback.
 */
public class AudioReceiverThread extends Thread {

    private DatagramSocket udpSocket;
    private AtomicBoolean running = new AtomicBoolean(true);
    private SourceDataLine sourceDataLine; // Line for playing audio

    // Audio format (must match capture format)
    private static final float SAMPLE_RATE = 16000; // 16 kHz
    private static final int SAMPLE_SIZE_IN_BITS = 16;
    private static final int CHANNELS = 1; // Mono
    private static final boolean SIGNED = true;
    private static final boolean BIG_ENDIAN = false; // Little-endian is common for PCM

    // Packetization parameters (must match sender)
    private static final int FRAME_SIZE = (SAMPLE_SIZE_IN_BITS / 8) * CHANNELS; // 2 bytes per frame
    private static final int PACKET_DURATION_MS = 20; // 20 milliseconds of audio per packet
    private static final int EXPECTED_PACKET_SIZE = (int) (SAMPLE_RATE * FRAME_SIZE * PACKET_DURATION_MS / 1000);

    // Jitter Buffer: Stores incoming audio packets
    private BlockingQueue<byte[]> jitterBuffer = new LinkedBlockingQueue<>(50); // Max 50 packets in buffer (e.g., 1 second of audio)
    private static final int JITTER_BUFFER_PLAYBACK_THRESHOLD = 5; // Start playing when buffer has at least 5 packets

    public AudioReceiverThread(DatagramSocket socket) {
        this.udpSocket = socket;
    }

    public void stopReceiving() {
        running.set(false);
        // Clear the buffer to prevent threads from getting stuck on `take()`
        jitterBuffer.clear();
        if (sourceDataLine != null) {
            sourceDataLine.stop();
            sourceDataLine.close();
            System.out.println("Audio playback line closed.");
        }
    }

    @Override
    public void run() {
        System.out.println("Audio receiver thread started.");

        AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, SIGNED, BIG_ENDIAN);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            System.err.println("Audio playback line not supported: " + info);
            running.set(false);
            return;
        }

        try {
            // Open SourceDataLine with a larger internal buffer to prevent underruns
            sourceDataLine = (SourceDataLine) AudioSystem.getLine(info);
            sourceDataLine.open(format, EXPECTED_PACKET_SIZE * 4); // Internal buffer 4 times expected packet size
            sourceDataLine.start(); // Start playing

            byte[] receiveBuffer = new byte[EXPECTED_PACKET_SIZE + 100]; // Slightly larger than expected packet size for safety

            System.out.println("Audio playback started. Listening on port " + udpSocket.getLocalPort() +
                    ". Expected packet size: " + EXPECTED_PACKET_SIZE + " bytes. Frame size: " + FRAME_SIZE + " bytes.");

            // Thread for receiving packets and adding to jitter buffer
            Thread receiverThread = new Thread(() -> {
                while (running.get() && !udpSocket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    try {
                        udpSocket.receive(packet);
                        int bytesReceived = packet.getLength();

                        if (bytesReceived > 0) {
                            // Ensure the received data is a multiple of the frame size
                            int bytesToProcess = bytesReceived - (bytesReceived % FRAME_SIZE);

                            if (bytesToProcess > 0) {
                                byte[] audioData = Arrays.copyOf(packet.getData(), bytesToProcess);
                                if (!jitterBuffer.offer(audioData, 1, TimeUnit.SECONDS)) { // Offer with timeout
                                    System.err.println("Jitter buffer full, dropping audio packet.");
                                }
                            } else {
                                System.err.println("Received audio packet with non-integral number of frames. Discarding " + bytesReceived + " bytes.");
                            }
                        }
                    } catch (IOException e) {
                        if (running.get() && !udpSocket.isClosed()) {
                            System.err.println("Error receiving audio packet: " + e.getMessage());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        running.set(false);
                        System.out.println("Audio receiver thread interrupted during buffer offer.");
                    }
                }
                System.out.println("UDP packet receiver sub-thread stopped.");
            }, "AudioPacketReceiver");
            receiverThread.start();


            // Main playback loop: pulls from jitter buffer and plays
            while (running.get()) {
                try {
                    // Wait until jitter buffer has enough packets to start smooth playback
                    if (jitterBuffer.size() < JITTER_BUFFER_PLAYBACK_THRESHOLD) {
                        // System.out.println("Jitter buffer low (" + jitterBuffer.size() + " packets), waiting...");
                        Thread.sleep(PACKET_DURATION_MS); // Wait a bit before checking again
                        continue;
                    }

                    byte[] audioData = jitterBuffer.take(); // Blocks until a packet is available
                    sourceDataLine.write(audioData, 0, audioData.length);
                    // System.out.println("Played audio packet: " + audioData.length + " bytes. Jitter buffer size: " + jitterBuffer.size());

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    running.set(false);
                    System.out.println("Audio playback thread interrupted.");
                } catch (Exception e) {
                    System.err.println("An unexpected error occurred during audio playback: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (LineUnavailableException e) {
            System.err.println("Speaker line unavailable: " + e.getMessage());
            e.printStackTrace();
        } catch (SecurityException e) {
            System.err.println("Security exception: Permission to access speakers denied. " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("An unexpected error occurred in audio reception: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (sourceDataLine != null && sourceDataLine.isOpen()) {
                sourceDataLine.stop();
                sourceDataLine.close();
            }
            System.out.println("Audio receiver thread stopped.");
        }
    }
}
