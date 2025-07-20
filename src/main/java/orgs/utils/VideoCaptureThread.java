package orgs.utils;

import org.opencv.core.Core;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.videoio.VideoCapture;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoCaptureThread extends Thread {
    private VideoCapture capture;
    private DatagramSocket udpSocket;
    private InetAddress remoteIp;
    private int remoteUdpPort;
    private AtomicBoolean running = new AtomicBoolean(true);
    private int frameId = 0; // Simple frame counter

    public VideoCaptureThread(DatagramSocket socket, InetAddress remoteIp, int remoteUdpPort) {
        //System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
        this.udpSocket = socket;
        this.remoteIp = remoteIp;
        this.remoteUdpPort = remoteUdpPort;
        this.capture = new VideoCapture(0); // 0 for default webcam
        if (!capture.isOpened()) {
            System.err.println("Error: Could not open webcam.");
            running.set(false);
        }
    }

    public void stopCapture() {
        running.set(false);
        if (capture != null) {
            capture.release(); // Release webcam resources
        }
    }

    @Override
    public void run() {
        System.out.println("the capter thread is on \n\n");
        Mat frame = new Mat();
        MatOfByte mob = new MatOfByte(); // Used for JPEG encoding

        while (running.get() && capture.read(frame)) {
            if (!frame.empty()) {
                // Encode frame to JPEG (simple for demo, but not very efficient for video)
                Imgcodecs.imencode(".jpg", frame, mob);
                byte[] frameBytes = mob.toArray();

                // For fragmented packets, each packet might look like:
                // [4 bytes: frameId] [4 bytes: fragmentIndex] [4 bytes: totalFragments] [data...]
                int maxPacketSize = 1400; // Typical payload size to avoid IP fragmentation
                int totalFragments = (int) Math.ceil((double) frameBytes.length / maxPacketSize);

                for (int i = 0; i < totalFragments; i++) {
                    int offset = i * maxPacketSize;
                    int length = Math.min(maxPacketSize, frameBytes.length - offset);

                    // Create a buffer with space for header + data
                    ByteBuffer packetBuffer = ByteBuffer.allocate(4 + 4 + 4 + length); // frameId + fragIndex + totalFrags + data
                    packetBuffer.putInt(frameId);
                    packetBuffer.putInt(i);
                    packetBuffer.putInt(totalFragments);
                    packetBuffer.put(frameBytes, offset, length);

                    DatagramPacket packet = new DatagramPacket(packetBuffer.array(), packetBuffer.position(), remoteIp, remoteUdpPort);
                    try {
                        udpSocket.send(packet);
                    } catch (IOException e) {
                        System.err.println("Error sending video packet: " + e.getMessage());
                        if (e instanceof SocketException && e.getMessage().contains("socket closed")) {
                            running.set(false); // Stop if socket is closed externally
                        }
                    }
                }
                frameId++;
                System.out.println("send :"+frameId);
            }

            try {
                Thread.sleep(30); // ~33ms for ~30 FPS
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running.set(false);
            }
        }
        System.out.println("Video capture thread stopped.");
        frame.release(); // Release frame resources
        mob.release(); // Release MatOfByte resources
        if (capture.isOpened()) {
            capture.release();
        }
    }

//    public static void main(String[] args) {
//        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
//        VideoCaptureThread videoCaptureThread =new VideoCaptureThread(null, null,1234);
//
//    }
}