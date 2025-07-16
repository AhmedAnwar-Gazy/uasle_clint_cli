package orgs.clintGUI;

import orgs.model.Chat;

import java.util.List;

/**
 * Listener for retrieving the current user's chats.
 */
public interface OnUserChatsRetrievedListener {
    void onUserChatsRetrieved(List<Chat> chats);
}

