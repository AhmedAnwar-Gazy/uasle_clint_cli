package orgs.clintGUI;

import java.io.File;

/**
 * Listener for media file transfer progress and completion.
 */
public interface OnFileTransferListener {
    /**
     * Called when a file transfer operation fails.
     *
     * @param msg The error message.
     */
    void onFail(String msg);

    /**
     * Called to report progress during file sending or receiving.
     *
     * @param transferredBytes The number of bytes transferred so far.
     * @param totalSize        The total size of the file in bytes.
     */
    void onProgress(long transferredBytes, long totalSize);

    /**
     * Called when a file has been successfully sent or received.
     *
     * @param file The File object representing the transferred file.
     */
    void onComplete(File file);
}
