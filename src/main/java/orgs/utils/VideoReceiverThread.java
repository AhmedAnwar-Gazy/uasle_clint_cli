package orgs.utils;

import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoReceiverThread extends Thread {
    private DatagramSocket udpSocket;
    private AtomicBoolean running = new AtomicBoolean(true);
    private JLabel videoDisplayLabel; // UI component to display video

    // Jitter buffer and frame reassembly
    // Map: <FrameId, Map<FragmentIndex, FragmentData>>
    private ConcurrentMap<Integer, ConcurrentMap<Integer, byte[]>> frameBuffer = new ConcurrentHashMap<>();
    // Map: <FrameId, TotalFragmentsExpected>
    private ConcurrentMap<Integer, Integer> frameMetadata = new ConcurrentHashMap<>();
    private int lastDisplayedFrameId = -1; // To ensure frames are displayed in order

    public VideoReceiverThread(DatagramSocket socket) {
        this.udpSocket = socket;
    }

    public void setVideoDisplayLabel(JLabel label) {
        this.videoDisplayLabel = label;
    }

    public void stopReceiving() {
        running.set(false);
    }

    @Override
    public void run() {
        System.out.println("the rsever thread is on "+ "\nrunning : "+running.get() +"\nis close : "+udpSocket.isClosed());
        System.out.println("my ip : " + udpSocket.getLocalAddress().getHostAddress()+ " \nmy port" + udpSocket.getPort());
        byte[] buffer = new byte[65507]; // Max UDP packet size
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        int reseved = 0 ;
        while (running.get() && !udpSocket.isClosed()) {
            System.out.println("******   resved "+ (++reseved)) ;

            try {
                System.out.println("good 1");
                udpSocket.receive(packet);
                System.out.println("good 1.1");
                ByteBuffer bBuffer = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
                System.out.println("good 1.2");
                if (bBuffer.remaining() >= 12) { // Ensure header exists (frameId, fragIndex, totalFrags)
                    System.out.println("good 2");
                    int currentFrameId = bBuffer.getInt();
                    int fragmentIndex = bBuffer.getInt();
                    int totalFragments = bBuffer.getInt();

                    byte[] fragmentData = new byte[bBuffer.remaining()];
                    bBuffer.get(fragmentData);

                    frameMetadata.putIfAbsent(currentFrameId, totalFragments);
                    frameBuffer.computeIfAbsent(currentFrameId, k -> new ConcurrentHashMap<>())
                            .put(fragmentIndex, fragmentData);

                    // Check if all fragments for the current frame are received
                    if (frameBuffer.containsKey(currentFrameId) &&
                            frameBuffer.get(currentFrameId).size() == frameMetadata.get(currentFrameId)) {
                        System.out.println("good 2");
                        // Only process if it's the next expected frame or a more recent one
                        if (currentFrameId > lastDisplayedFrameId) {
                            // Reassemble the full frame
                            byte[] fullFrameBytes = reassembleFrame(currentFrameId);
                            if (fullFrameBytes != null) {
                                displayFrame(fullFrameBytes);
                                lastDisplayedFrameId = currentFrameId;
                            }
                        }
                        // Clean up processed frame data to prevent memory leak
                        frameBuffer.remove(currentFrameId);
                        frameMetadata.remove(currentFrameId);
                    }

                    // Simple cleanup for old frames (basic jitter buffer management)
                    frameBuffer.keySet().removeIf(id -> id < lastDisplayedFrameId - 5); // Remove very old frames
                    frameMetadata.keySet().removeIf(id -> id < lastDisplayedFrameId - 5);

                }
            } catch (IOException e) {
                if (!udpSocket.isClosed()) {
                    System.err.println("Error receiving video packet: " + e.getMessage());
                }
            }
        }
        System.out.println("Video receiver thread stopped.");
    }

    private byte[] reassembleFrame(int frameId) {
        ConcurrentMap<Integer, byte[]> fragments = frameBuffer.get(frameId);
        int totalFragments = frameMetadata.get(frameId);

        // Simple check: ensure all fragments are here
        if (fragments == null || fragments.size() != totalFragments) {
            return null; // Not all fragments received yet
        }

        // Calculate total size
        int totalSize = 0;
        byte[][] orderedFragments = new byte[totalFragments][];
        for (int i = 0; i < totalFragments; i++) {
            byte[] frag = fragments.get(i);
            if (frag == null) return null; // Missing fragment
            orderedFragments[i] = frag;
            totalSize += frag.length;
        }

        // Reassemble
        byte[] fullFrame = new byte[totalSize];
        int currentOffset = 0;
        for (byte[] frag : orderedFragments) {
            System.arraycopy(frag, 0, fullFrame, currentOffset, frag.length);
            currentOffset += frag.length;
        }
        return fullFrame;
    }

    private void displayFrame(byte[] frameBytes) {
        if (videoDisplayLabel == null) return;
        try {
            // Decode JPEG bytes to Mat
            Mat encodedMat = new MatOfByte(frameBytes);
            Mat frame = Imgcodecs.imdecode(encodedMat, Imgcodecs.IMREAD_COLOR);

            // Convert OpenCV Mat to AWT BufferedImage
            if (!frame.empty()) {
                int type = BufferedImage.TYPE_BYTE_GRAY;
                if (frame.channels() > 1) {
                    type = BufferedImage.TYPE_3BYTE_BGR;
                }
                int bufferSize = frame.channels() * frame.cols() * frame.rows();
                byte[] b = new byte[bufferSize];
                frame.get(0, 0, b); // get all the pixels
                BufferedImage image = new BufferedImage(frame.cols(), frame.rows(), type);
                final byte[] targetPixels = ((java.awt.image.DataBufferByte) image.getRaster().getDataBuffer()).getData();
                System.arraycopy(b, 0, targetPixels, 0, b.length);

                SwingUtilities.invokeLater(() -> videoDisplayLabel.setIcon(new ImageIcon(image)));
            }
            encodedMat.release(); // Release resources
            frame.release(); // Release resources
        } catch (Exception e) {
            System.err.println("Error displaying video frame: " + e.getMessage());
            // e.printStackTrace(); // Uncomment for detailed debugging
        }
    }
}