package orgs.utils;

import javax.sound.sampled.*;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread for receiving audio via UDP and playing it back.
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
    private int reseved = 0; // Simple frame counter

    public AudioReceiverThread(DatagramSocket socket) {
        this.udpSocket = socket;
    }

    public void stopReceiving() {
        running.set(false);
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
            sourceDataLine = (SourceDataLine) AudioSystem.getLine(info);
            sourceDataLine.open(format);
            sourceDataLine.start(); // Start playing

            // Buffer size for receiving packets (should be large enough for a single audio packet)
            byte[] buffer = new byte[2048]; // Max UDP packet size is ~65507, but audio packets are usually smaller

            System.out.println("Audio playback started. Listening on port " + udpSocket.getLocalPort());

            while (running.get() && !udpSocket.isClosed()) {
                System.out.println("listening  %%%%%%%%");
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    udpSocket.receive(packet);
                    if (packet.getLength() > 0) {
                        sourceDataLine.write(packet.getData(), 0, packet.getLength());
                        // System.out.println("Received and played audio packet: " + packet.getLength() + " bytes"); // Too verbose
                        System.out.println("---------   Received and played audio packet "+ (++reseved)) ;
                    }
                } catch (IOException e) {
                    if (running.get() && !udpSocket.isClosed()) { // Only log if not intentionally stopped or socket closed
                        System.err.println("Error receiving audio packet: " + e.getMessage());
                    }
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
