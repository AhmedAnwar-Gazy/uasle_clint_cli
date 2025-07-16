package orgs.clintGUI;

import orgs.model.Message;

import java.util.List;

/**
 * Listener for retrieving lists of messages for a chat.
 */
public interface OnMessagesRetrievedListener {
    void onMessagesRetrieved(List<Message> messages, int chatId);
}
