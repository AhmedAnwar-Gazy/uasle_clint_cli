package orgs.utils;

import javax.sound.sampled.*;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread for capturing audio from the microphone and sending it via UDP.
 */
public class AudioCaptureThread extends Thread {

    private DatagramSocket udpSocket;
    private InetAddress remoteIp;
    private int remoteUdpPort;
    private AtomicBoolean running = new AtomicBoolean(true);
    private TargetDataLine targetDataLine; // Line for capturing audio

    // Audio format for capture and playback
    private static final float SAMPLE_RATE = 16000; // 16 kHz
    private static final int SAMPLE_SIZE_IN_BITS = 16;
    private static final int CHANNELS = 1; // Mono
    private static final boolean SIGNED = true;
    private static final boolean BIG_ENDIAN = false; // Little-endian is common for PCM

    public AudioCaptureThread(DatagramSocket socket, InetAddress remoteIp, int remoteUdpPort) {
        this.udpSocket = socket;
        this.remoteIp = remoteIp;
        this.remoteUdpPort = remoteUdpPort;
    }

    public void stopCapture() {
        running.set(false);
        if (targetDataLine != null) {
            targetDataLine.stop();
            targetDataLine.close();
            System.out.println("Audio capture line closed.");
        }
    }

    @Override
    public void run() {
        System.out.println("Audio capture thread started.");

        AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, SIGNED, BIG_ENDIAN);
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            System.err.println("Audio capture line not supported: " + info);
            running.set(false);
            return;
        }

        try {
            targetDataLine = (TargetDataLine) AudioSystem.getLine(info);
            targetDataLine.open(format);
            targetDataLine.start(); // Start capturing

            // Buffer size: 1/10th of a second of audio data
            int bufferSize = (int) (format.getSampleRate() * format.getFrameSize() / 10);
            byte[] buffer = new byte[bufferSize];

            System.out.println("Audio capture started. Sending to " + remoteIp + ":" + remoteUdpPort);

            while (running.get()) {
                int bytesRead = targetDataLine.read(buffer, 0, buffer.length);
                if (bytesRead > 0) {
                    DatagramPacket packet = new DatagramPacket(buffer, 0, bytesRead, remoteIp, remoteUdpPort);
                    try {
                        udpSocket.send(packet);
                        // System.out.println("Sent audio packet: " + bytesRead + " bytes"); // Too verbose for continuous log
                    } catch (IOException e) {
                        if (running.get()) { // Only log if not intentionally stopped
                            System.err.println("Error sending audio packet: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (LineUnavailableException e) {
            System.err.println("Microphone line unavailable: " + e.getMessage());
            e.printStackTrace();
        } catch (SecurityException e) {
            System.err.println("Security exception: Permission to access microphone denied. " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("An unexpected error occurred in audio capture: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (targetDataLine != null && targetDataLine.isOpen()) {
                targetDataLine.stop();
                targetDataLine.close();
            }
            System.out.println("Audio capture thread stopped.");
        }
    }
}
