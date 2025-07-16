package orgs.clintGUI;
import orgs.model.Message;
import orgs.model.User;
import orgs.model.Chat;
import orgs.protocol.Response;

import java.util.List;

/**
 * Interface for listeners to receive updates from the ChatClient.
 * This decouples the ChatClient's core logic from the UI (e.g., JavaFX).
 */
public interface ChatClientListener {
    /**
     * Called when a new message is received from the server (e.g., from another user).
     * @param message The new message object.
     */
    void onNewMessageReceived(Message message);

    /**
     * Called when a general command response is received from the server.
     * @param response The server's response.
     */
    void onCommandResponse(Response response);

    /**
     * Called to provide general status updates (e.g., connection status, file transfer progress).
     * @param status The status message.
     */
    void onStatusUpdate(String status);

    /**
     * Called when an error occurs in the client.
     * @param error The error message.
     */
    void onError(String error);

    /**
     * Called when the user successfully logs in.
     * @param user The authenticated user object.
     */
    void onLoginSuccess(User user);

    /**
     * Called when a list of messages is retrieved.
     * @param messages The list of messages.
     * @param chatId The ID of the chat these messages belong to.
     */
    void onMessagesRetrieved(List<Message> messages, int chatId);

    /**
     * Called when a list of all users is retrieved.
     * @param users The list of all users.
     */
    void onAllUsersRetrieved(List<User> users);

    /**
     * Called when a list of user's chats is retrieved.
     * @param chats The list of chats the user is part of.
     */
    void onUserChatsRetrieved(List<Chat> chats);

    /**
     * Called when a media file is successfully downloaded.
     * @param filePath The absolute path to the downloaded file.
     */
    void onFileDownloaded(String filePath);

    /**
     * Called when a list of contacts is retrieved.
     * @param contacts The list of user's contacts.
     */
    void onContactsRetrieved(List<User> contacts);

    /**
     * Called when a list of notifications is retrieved.
     * @param notifications The list of user's notifications.
     */
    void onNotificationsRetrieved(List<orgs.model.Notification> notifications);

    /**
     * Called when a list of chat participants is retrieved.
     * @param participants The list of chat participants.
     * @param chatId The ID of the chat.
     */
    void onChatParticipantsRetrieved(List<orgs.model.ChatParticipant> participants, int chatId);
}
