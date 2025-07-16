package orgs.clintGUI;

import orgs.model.ChatParticipant;

import java.util.List;

/**
 * Listener for retrieving a list of chat participants.
 */
public interface OnChatParticipantsRetrievedListener {
    void onChatParticipantsRetrieved(List<ChatParticipant> participants, int chatId);
}
