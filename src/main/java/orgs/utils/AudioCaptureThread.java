//package orgs.utils;
//
//import javax.sound.sampled.*;
//import java.io.IOException;
//import java.net.DatagramPacket;
//import java.net.DatagramSocket;
//import java.net.InetAddress;
//import java.util.concurrent.atomic.AtomicBoolean;
//
///**
// * Thread for capturing audio from the microphone and sending it via UDP.
// */
//public class AudioCaptureThread extends Thread {
//
//    private DatagramSocket udpSocket;
//    private InetAddress remoteIp;
//    private int remoteUdpPort;
//    private AtomicBoolean running = new AtomicBoolean(true);
//    private TargetDataLine targetDataLine; // Line for capturing audio
//
//    // Audio format for capture and playback
//    private static final float SAMPLE_RATE = 16000; // 16 kHz
//    private static final int SAMPLE_SIZE_IN_BITS = 16;
//    private static final int CHANNELS = 1; // Mono
//    private static final boolean SIGNED = true;
//    private static final boolean BIG_ENDIAN = false; // Little-endian is common for PCM
//    private int frameId = 0; // Simple frame counter
//
//    public AudioCaptureThread(DatagramSocket socket, InetAddress remoteIp, int remoteUdpPort) {
//        this.udpSocket = socket;
//        this.remoteIp = remoteIp;
//        this.remoteUdpPort = remoteUdpPort;
//    }
//
//    public void stopCapture() {
//        running.set(false);
//        if (targetDataLine != null) {
//            targetDataLine.stop();
//            targetDataLine.close();
//            System.out.println("Audio capture line closed.");
//        }
//    }
//
//    @Override
//    public void run() {
//        System.out.println("Audio capture thread started.");
//
//        AudioFormat format = new AudioFormat(SAMPLE_RATE, SAMPLE_SIZE_IN_BITS, CHANNELS, SIGNED, BIG_ENDIAN);
//        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
//
//        if (!AudioSystem.isLineSupported(info)) {
//            System.err.println("Audio capture line not supported: " + info);
//            running.set(false);
//            return;
//        }
//
//        try {
//            targetDataLine = (TargetDataLine) AudioSystem.getLine(info);
//            targetDataLine.open(format);
//            targetDataLine.start(); // Start capturing
//
//            // Buffer size: 1/10th of a second of audio data
//            int bufferSize = (int) (format.getSampleRate() * format.getFrameSize() / 10);
//            byte[] buffer = new byte[bufferSize];
//
//            System.out.println("Audio capture started. Sending to " + remoteIp + ":" + remoteUdpPort);
//
//            while (running.get()) {
//                int bytesRead = targetDataLine.read(buffer, 0, buffer.length);
//                if (bytesRead > 0) {
//                    DatagramPacket packet = new DatagramPacket(buffer, 0, bytesRead, remoteIp, remoteUdpPort);
//                    try {
//                        udpSocket.send(packet);
//                        // System.out.println("Sent audio packet: " + bytesRead + " bytes"); // Too verbose for continuous log
//                    } catch (IOException e) {
//                        if (running.get()) { // Only log if not intentionally stopped
//                            System.err.println("Error sending audio packet: " + e.getMessage());
//                        }
//                    }
//                    frameId++;
//                    System.out.println("------ send audio to the ip : "+remoteIp+ " on port : "+remoteUdpPort);
//                }
//            }
//        } catch (LineUnavailableException e) {
//            System.err.println("Microphone line unavailable: " + e.getMessage());
//            e.printStackTrace();
//        } catch (SecurityException e) {
//            System.err.println("Security exception: Permission to access microphone denied. " + e.getMessage());
//            e.printStackTrace();
//        } catch (Exception e) {
//            System.err.println("An unexpected error occurred in audio capture: " + e.getMessage());
//            e.printStackTrace();
//        } finally {
//            if (targetDataLine != null && targetDataLine.isOpen()) {
//                targetDataLine.stop();
//                targetDataLine.close();
//            }
//            System.out.println("Audio capture thread stopped.");
//        }
//    }
//}
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

    // Packetization: Aim for small, frequent packets (e.g., 20ms of audio per packet)
    // Frame size = (SAMPLE_SIZE_IN_BITS / 8) * CHANNELS
    // Bytes per millisecond = (SAMPLE_RATE * FrameSize) / 1000
    // Buffer size = Bytes per millisecond * desired_packet_duration_ms
    private static final int FRAME_SIZE = (SAMPLE_SIZE_IN_BITS / 8) * CHANNELS; // 2 bytes per frame
    private static final int PACKET_DURATION_MS = 20; // 20 milliseconds of audio per packet
    private static final int BUFFER_SIZE = (int) (SAMPLE_RATE * FRAME_SIZE * PACKET_DURATION_MS / 1000);


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
            // Open the TargetDataLine with a buffer size that's a multiple of the frame size
            // and large enough to prevent underruns/overruns, but not too large to add latency.
            // A good starting point is 2-4 times the packet size.
            targetDataLine = (TargetDataLine) AudioSystem.getLine(info);
            targetDataLine.open(format, BUFFER_SIZE * 4); // Open with a larger internal buffer
            targetDataLine.start(); // Start capturing

            byte[] buffer = new byte[BUFFER_SIZE]; // Buffer for each UDP packet

            System.out.println("Audio capture started. Sending to " + remoteIp + ":" + remoteUdpPort +
                    " with packet size " + BUFFER_SIZE + " bytes (" + PACKET_DURATION_MS + "ms audio).");

            while (running.get()) {
                // Read exactly BUFFER_SIZE bytes. This call is blocking.
                int bytesRead = targetDataLine.read(buffer, 0, buffer.length);

                if (bytesRead > 0) {
                    // Ensure bytesRead is a multiple of frameSize before sending
                    int bytesToSend = bytesRead - (bytesRead % FRAME_SIZE);
                    if (bytesToSend > 0) {
                        DatagramPacket packet = new DatagramPacket(buffer, 0, bytesToSend, remoteIp, remoteUdpPort);
                        try {
                            udpSocket.send(packet);
                            // System.out.println("Sent audio packet: " + bytesToSend + " bytes");
                        } catch (IOException e) {
                            if (running.get()) {
                                System.err.println("Error sending audio packet: " + e.getMessage());
                            }
                        }
                    } else {
                        System.err.println("Captured non-integral number of frames. Discarding " + bytesRead + " bytes.");
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
