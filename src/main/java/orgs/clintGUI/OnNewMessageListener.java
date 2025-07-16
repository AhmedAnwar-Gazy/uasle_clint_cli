package orgs.clintGUI;

import orgs.model.Message;

/**
 * Listener for new messages received from the server (unsolicited).
 */
public interface OnNewMessageListener {
    void onNewMessageReceived(Message message);
}
