package orgs.clintGUI;

import orgs.model.Message;
import orgs.model.User;
import orgs.model.Chat;
import orgs.model.Media;
import orgs.model.Notification;
import orgs.model.ChatParticipant;
import orgs.protocol.Command;
import orgs.protocol.Request;
import orgs.protocol.Response;
import orgs.utils.LocalDateTimeAdapter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.net.Socket;
import java.net.SocketException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * ChatClient class implemented as a Singleton for use in a JavaFX application.
 * It handles network communication with the chat server. All user interaction
 * logic (input/output) has been removed. It uses specialized listener interfaces
 * for communicating updates and errors to the UI, and its public methods
 * now return the server's Response object for direct processing by the caller.
 */
public class ChatClient implements AutoCloseable {
    // Singleton instance
    private static ChatClient instance;

    private static final String SERVER_IP = "192.168.1.99"; // Localhost
    private static final int SERVER_PORT = 6373;
    private static final int FILE_TRANSFER_PORT = 6374;

    private String currentFilePathToSend; // Temporary storage for file path during send initiation
    private String pendingFileTransferId; // Temporary storage for transfer ID during send initiation

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
            .serializeNulls()
            .create();

    private User currentUser;

    // BlockingQueue to hold responses from the server for synchronous command processing
    private final BlockingQueue<Response> responseQueue = new LinkedBlockingQueue<>();

    // --- Specialized Listener Lists ---
    private final List<OnCommandResponseListener> commandResponseListeners = Collections.synchronizedList(new ArrayList<>());
    private final List<OnNewMessageListener> newMessageListeners = Collections.synchronizedList(new ArrayList<>());
    private final List<OnLoginSuccessListener> loginSuccessListeners = Collections.synchronizedList(new ArrayList<>());
    private final List<OnMessagesRetrievedListener> messagesRetrievedListeners = Collections.synchronizedList(new ArrayList<>());
    private final List<OnAllUsersRetrievedListener> allUsersRetrievedListeners = Collections.synchronizedList(new ArrayList<>());
    private final List<OnUserChatsRetrievedListener> userChatsRetrievedListeners = Collections.synchronizedList(new ArrayList<>());
    private final List<OnContactsRetrievedListener> contactsRetrievedListeners = Collections.synchronizedList(new ArrayList<>());
    private final List<OnNotificationsRetrievedListener> notificationsRetrievedListeners = Collections.synchronizedList(new ArrayList<>());
    private final List<OnChatParticipantsRetrievedListener> chatParticipantsRetrievedListeners = Collections.synchronizedList(new ArrayList<>());
    private final List<OnConnectionFailureListener> connectionFailureListeners = Collections.synchronizedList(new ArrayList<>());
    private final List<OnStatusUpdateListener> statusUpdateListeners = Collections.synchronizedList(new ArrayList<>());
    private final List<OnChatRetrievedListener> chatRetrievedListeners = Collections.synchronizedList(new ArrayList<>()); // New listener list
    private final List<OnUserRetrievedListener> userRetrievedListeners = Collections.synchronizedList(new ArrayList<>()); // New listener list


    // Store the OnFileTransferListener specifically for the current media transfer
    // This is a temporary listener for a specific operation, not a general one.
    private volatile OnFileTransferListener currentFileTransferListener;

    /**
     * Private constructor to prevent direct instantiation.
     * Initializes network connections and starts a listener thread.
     */
    private ChatClient() {
        // Attempt initial connection. Errors are dispatched via listeners.
        try {
            socket = new Socket(SERVER_IP, SERVER_PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            notifyStatusUpdate("Connected to chat server on main port.");

            new Thread(this::listenForServerMessages, "ServerListener").start();

        } catch (IOException e) {
            notifyConnectionFailure("Error connecting to server: " + e.getMessage());
            // In a real JavaFX app, you might show an alert and handle graceful shutdown
            // System.exit(1); // Removed for better JavaFX integration
        }
    }

    /**
     * Provides the global access point to the ChatClient instance (Singleton pattern).
     * @return The single instance of ChatClient.
     */
    public static synchronized ChatClient getInstance() {
        if (instance == null) {
            instance = new ChatClient();
        }
        return instance;
    }

    // --- Listener Registration Methods ---

    public void addOnCommandResponseListener(OnCommandResponseListener listener) {
        commandResponseListeners.add(listener);
    }
    public void removeOnCommandResponseListener(OnCommandResponseListener listener) {
        commandResponseListeners.remove(listener);
    }

    public void addOnNewMessageListener(OnNewMessageListener listener) {
        newMessageListeners.add(listener);
    }
    public void removeOnNewMessageListener(OnNewMessageListener listener) {
        newMessageListeners.remove(listener);
    }

    public void addOnLoginSuccessListener(OnLoginSuccessListener listener) {
        loginSuccessListeners.add(listener);
    }
    public void removeOnLoginSuccessListener(OnLoginSuccessListener listener) {
        loginSuccessListeners.remove(listener);
    }

    public void addOnMessagesRetrievedListener(OnMessagesRetrievedListener listener) {
        messagesRetrievedListeners.add(listener);
    }
    public void removeOnMessagesRetrievedListener(OnMessagesRetrievedListener listener) {
        messagesRetrievedListeners.remove(listener);
    }

    public void addOnAllUsersRetrievedListener(OnAllUsersRetrievedListener listener) {
        allUsersRetrievedListeners.add(listener);
    }
    public void removeOnAllUsersRetrievedListener(OnAllUsersRetrievedListener listener) {
        allUsersRetrievedListeners.remove(listener);
    }

    public void addOnUserChatsRetrievedListener(OnUserChatsRetrievedListener listener) {
        userChatsRetrievedListeners.add(listener);
    }
    public void removeOnUserChatsRetrievedListener(OnUserChatsRetrievedListener listener) {
        userChatsRetrievedListeners.remove(listener);
    }

    public void addOnContactsRetrievedListener(OnContactsRetrievedListener listener) {
        contactsRetrievedListeners.add(listener);
    }
    public void removeOnContactsRetrievedListener(OnContactsRetrievedListener listener) {
        contactsRetrievedListeners.remove(listener);
    }

    public void addOnNotificationsRetrievedListener(OnNotificationsRetrievedListener listener) {
        notificationsRetrievedListeners.add(listener);
    }
    public void removeOnNotificationsRetrievedListener(OnNotificationsRetrievedListener listener) {
        notificationsRetrievedListeners.remove(listener);
    }

    public void addOnChatParticipantsRetrievedListener(OnChatParticipantsRetrievedListener listener) {
        chatParticipantsRetrievedListeners.add(listener);
    }
    public void removeOnChatParticipantsRetrievedListener(OnChatParticipantsRetrievedListener listener) {
        chatParticipantsRetrievedListeners.remove(listener);
    }

    public void addOnConnectionFailureListener(OnConnectionFailureListener listener) {
        connectionFailureListeners.add(listener);
    }
    public void removeOnConnectionFailureListener(OnConnectionFailureListener listener) {
        connectionFailureListeners.remove(listener);
    }

    public void addOnStatusUpdateListener(OnStatusUpdateListener listener) {
        statusUpdateListeners.add(listener);
    }
    public void removeOnStatusUpdateListener(OnStatusUpdateListener listener) {
        statusUpdateListeners.remove(listener);
    }

    public void addOnChatRetrievedListener(OnChatRetrievedListener listener) { // New listener registration
        chatRetrievedListeners.add(listener);
    }
    public void removeOnChatRetrievedListener(OnChatRetrievedListener listener) { // New listener removal
        chatRetrievedListeners.remove(listener);
    }

    public void addOnUserRetrievedListener(OnUserRetrievedListener listener) { // New listener registration
        userRetrievedListeners.add(listener);
    }
    public void removeOnUserRetrievedListener(OnUserRetrievedListener listener) { // New listener removal
        userRetrievedListeners.remove(listener);
    }


    // --- Internal Notification Helpers ---

    private void notifyCommandResponse(Response response) {
        commandResponseListeners.forEach(l -> l.onCommandResponse(response));
    }

    private void notifyNewMessageReceived(Message message) {
        newMessageListeners.forEach(l -> l.onNewMessageReceived(message));
    }

    private void notifyLoginSuccess(User user) {
        loginSuccessListeners.forEach(l -> l.onLoginSuccess(user));
    }

    private void notifyMessagesRetrieved(List<Message> messages, int chatId) {
        messagesRetrievedListeners.forEach(l -> l.onMessagesRetrieved(messages, chatId));
    }

    private void notifyAllUsersRetrieved(List<User> users) {
        allUsersRetrievedListeners.forEach(l -> l.onAllUsersRetrieved(users));
    }

    private void notifyUserChatsRetrieved(List<Chat> chats) {
        userChatsRetrievedListeners.forEach(l -> l.onUserChatsRetrieved(chats));
    }

    private void notifyContactsRetrieved(List<User> contacts) {
        contactsRetrievedListeners.forEach(l -> l.onContactsRetrieved(contacts));
    }

    private void notifyNotificationsRetrieved(List<Notification> notifications) {
        notificationsRetrievedListeners.forEach(l -> l.onNotificationsRetrieved(notifications));
    }

    private void notifyChatParticipantsRetrieved(List<ChatParticipant> participants, int chatId) {
        chatParticipantsRetrievedListeners.forEach(l -> l.onChatParticipantsRetrieved(participants, chatId));
    }

    private void notifyConnectionFailure(String errorMessage) {
        connectionFailureListeners.forEach(l -> l.onConnectionFailure(errorMessage));
        System.err.println("[Connection Failure]: " + errorMessage); // Fallback to console for critical errors
    }

    private void notifyStatusUpdate(String status) {
        statusUpdateListeners.forEach(l -> l.onStatusUpdate(status));
        System.out.println("[Status Update]: " + status); // Fallback to console for general status
    }

    private void notifyChatRetrieved(Chat chat) { // New notification helper
        chatRetrievedListeners.forEach(l -> l.onChatRetrieved(chat));
    }

    private void notifyUserRetrieved(User user) { // New notification helper
        userRetrievedListeners.forEach(l -> l.onUserRetrieved(user));
    }

    // --- Core Listener Thread ---

    /**
     * Listens for incoming messages from the server and processes them.
     * Dispatches unsolicited messages to listeners and puts command responses
     * into a queue for the calling thread.
     */
    private void listenForServerMessages() {
        try {
            String serverResponseJson;
            while ((serverResponseJson = in.readLine()) != null) {
                Response response = gson.fromJson(serverResponseJson, Response.class);
                // System.out.println("[DEBUG - Raw Server Response]: " + serverResponseJson); // Debugging can stay

                // Special handling for file transfer initiation (server tells client to send file)
                if ("READY_TO_RECEIVE_FILE".equals(response.getMessage())) {
                    notifyStatusUpdate("Server is ready for file transfer. Initiating file send...");
                    Type type = new TypeToken<Map<String, String>>() {}.getType();
                    Map<String, String> data = gson.fromJson(response.getData(), type);
                    pendingFileTransferId = data.get("transfer_id");

                    if (pendingFileTransferId != null) {
                        // This must be called with the specific listener provided by the sendMediaMessage caller
                        sendFileBytes(currentFilePathToSend, pendingFileTransferId, currentFileTransferListener);
                    } else {
                        if (currentFileTransferListener != null) {
                            currentFileTransferListener.onFail("Error: Server responded READY_TO_RECEIVE_FILE but no transfer_id found in data.");
                        }
                        notifyConnectionFailure("Server responded READY_TO_RECEIVE_FILE but no transfer_id found in data.");
                    }
                    continue; // Do not put this into the main response queue
                }

                // Handle unsolicited new messages (e.g., from other users)
                if (response.isSuccess() && "New message received".equals(response.getMessage())) {
                    Message newMessage = gson.fromJson(response.getData(), Message.class);
                    notifyNewMessageReceived(newMessage);
                }
                // All other responses are put into the queue for the specific command method that sent the request
                else {
                    responseQueue.put(response);
                }
            }
        } catch (SocketException e) {
            notifyConnectionFailure("Server connection lost: " + e.getMessage());
        } catch (IOException e) {
            notifyConnectionFailure("Error reading from server: " + e.getMessage());
        } catch (InterruptedException e) {
            notifyConnectionFailure("Listener thread interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            closeConnection();
        }
    }

    /**
     * Attempts to log in a user.
     * @param phoneNumber The user's phone number.
     * @param password The user's password.
     * @return The server's Response object.
     */
    public Response login(String phoneNumber, String password) {
        Map<String, Object> authData = new HashMap<>();
        authData.put("phone_number", phoneNumber);
        authData.put("password", password);

        Request loginRequest = new Request(Command.LOGIN, authData);
        Response loginResponse = sendRequestAndAwaitResponse(loginRequest);

        if (loginResponse != null && loginResponse.isSuccess()) {
            this.currentUser = gson.fromJson(loginResponse.getData(), User.class);
            notifyLoginSuccess(currentUser);
            notifyStatusUpdate("Logged in as: " + currentUser.getPhoneNumber() + " (" + currentUser.getFirstName() + " " + currentUser.getLastName() + ")");
        } else if (loginResponse != null) {
            notifyCommandResponse(loginResponse); // Notify other listeners about failed login
        }
        return loginResponse;
    }

    /**
     * Attempts to register a new user.
     * @param phoneNumber The new user's phone number.
     * @param password The new user's password.
     * @param firstName The new user's first name.
     * @param lastName The new user's last name.
     * @return The server's Response object.
     */
    public Response register(String phoneNumber, String password, String firstName, String lastName) {
        Map<String, Object> authData = new HashMap<>();
        authData.put("phone_number", phoneNumber);
        authData.put("password", password);
        authData.put("first_name", firstName);
        authData.put("last_name", lastName);

        Request registerRequest = new Request(Command.REGISTER, authData);
        Response registerResponse = sendRequestAndAwaitResponse(registerRequest);
        notifyCommandResponse(registerResponse); // Notify listeners about registration outcome
        return registerResponse;
    }

    /**
     * Sends a text message to a chat.
     * @param chatId The ID of the chat.
     * @param content The text content of the message.
     * @return The server's Response object.
     */
    public Response sendTextMessage(int chatId, String content) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to send messages.", null);
        }
        if (content == null || content.trim().isEmpty()) {
            return new Response(false, "Text message content cannot be empty.", null);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);
        data.put("content", content);

        Request request = new Request(Command.SEND_MESSAGE, data);
        Response response = sendRequestAndAwaitResponse(request);
        notifyCommandResponse(response); // Notify listeners about message send outcome
        return response;
    }

    /**
     * Sends a media message to a chat.
     * This method initiates the media transfer process.
     * @param chatId The ID of the chat.
     * @param filePath The local file path for the media.
     * @param caption The caption for the media message (can be null).
     * @param mediaType The type of media (e.g., "image", "video", "voiceNote", "file").
     * @param fileTransferListener A specific listener for this file transfer's progress/completion.
     * @return The server's Response object for the initial message request.
     */
    public Response sendMediaMessage(int chatId, String filePath, String caption, String mediaType, OnFileTransferListener fileTransferListener) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to send media messages.", null);
        }
        if (filePath == null || filePath.isEmpty()) {
            return new Response(false, "File path cannot be empty for media message.", null);
        }

        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            String errorMsg = "Error: File not found or is not a regular file at " + filePath;
            if (fileTransferListener != null) fileTransferListener.onFail(errorMsg);
            return new Response(false, errorMsg, null);
        }

        // Store the file path and the specific listener for the upcoming file transfer
        this.currentFilePathToSend = filePath;
        this.currentFileTransferListener = fileTransferListener; // Set the specific listener for this transfer

        long fileSize = file.length();
        String fileName = file.getName();

        Media media = new Media();
        media.setFileName(fileName);
        media.setFileSize(fileSize);
        media.setMediaType(mediaType);
        media.setUploadedByUserId(currentUser.getId());
        media.setUploadedAt(LocalDateTime.now());

        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);
        data.put("content", (caption != null && !caption.isEmpty()) ? caption : null);
        data.put("media", media);

        Request request = new Request(Command.SEND_MESSAGE, data);
        Response response = sendRequestAndAwaitResponse(request);
        notifyCommandResponse(response); // Notify general listeners about message send outcome

        // Important: currentFileTransferListener will be used by listenForServerMessages
        // when it receives "READY_TO_RECEIVE_FILE" and calls sendFileBytes.
        // It's reset inside sendFileBytes's finally block or after successful transfer.
        return response;
    }

    /**
     * Retrieves chat messages for a given chat ID.
     * @param chatId The ID of the chat.
     * @param limit The maximum number of messages to fetch.
     * @param offset The starting point (offset) for fetching messages.
     * @return The server's Response object.
     */
    public Response getChatMessages(int chatId, int limit, int offset) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to get messages.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);
        data.put("limit", limit);
        data.put("offset", offset);

        Request request = new Request(Command.GET_CHAT_MESSAGES, data);
        Response messagesResponse = sendRequestAndAwaitResponse(request);

        if (messagesResponse != null && messagesResponse.isSuccess() && "Messages retrieved.".equals(messagesResponse.getMessage())) {
            Type messageListType = new TypeToken<List<Message>>() {}.getType();
            List<Message> messages = gson.fromJson(messagesResponse.getData(), messageListType);
            notifyMessagesRetrieved(messages, chatId); // Notify dedicated listener
        } else if (messagesResponse != null) {
            notifyCommandResponse(messagesResponse); // Notify general listeners about failure
        }
        return messagesResponse;
    }

    /**
     * Creates a new chat.
     * @param chatType The type of chat (private, group, channel).
     * @param chatName The name of the chat (optional for private).
     * @param chatDescription The description of the chat (optional).
     * @param publicLink The public link for public channels (optional).
     * @return The server's Response object.
     */
    public Response createChat(String chatType, String chatName, String chatDescription, String publicLink) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to create chats.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("chat_type", chatType);
        data.put("chat_name", chatName != null && !chatName.isEmpty() ? chatName : null);
        data.put("chat_description", chatDescription != null && !chatDescription.isEmpty() ? chatDescription : null);
        data.put("public_link", publicLink != null && !publicLink.isEmpty() ? publicLink : null);

        Request request = new Request(Command.CREATE_CHAT, data);
        Response response = sendRequestAndAwaitResponse(request);
        notifyCommandResponse(response);
        return response;
    }

    /**
     * Retrieves all registered users.
     * @return The server's Response object.
     */
    public Response getAllUsers() {
        if (currentUser == null) {
            return new Response(false, "Authentication required to get all users.", null);
        }
        Request request = new Request(Command.GET_ALL_USERS);
        Response allUsersResponse = sendRequestAndAwaitResponse(request);
        if (allUsersResponse != null && allUsersResponse.isSuccess() && "All users retrieved.".equals(allUsersResponse.getMessage())) {
            Type userListType = new TypeToken<List<User>>() {}.getType();
            List<User> users = gson.fromJson(allUsersResponse.getData(), userListType);
            notifyAllUsersRetrieved(users); // Notify dedicated listener
        } else if (allUsersResponse != null) {
            notifyCommandResponse(allUsersResponse);
        }
        return allUsersResponse;
    }

    /**
     * Retrieves the current user's chats.
     * @return The server's Response object.
     */
    public Response getUserChats() {
        if (currentUser == null) {
            return new Response(false, "Authentication required to get your chats.", null);
        }
        Request request = new Request(Command.GET_USER_CHATS);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess() && "All chats retrieved for user.".equals(response.getMessage())) {
            Type chatListType = new TypeToken<List<Chat>>() {}.getType();
            List<Chat> chats = gson.fromJson(response.getData(), chatListType);
            notifyUserChatsRetrieved(chats); // Notify dedicated listener
        } else if (response != null) {
            notifyCommandResponse(response);
        }
        return response;
    }

    /**
     * Adds a participant to a chat.
     * @param chatId The ID of the chat.
     * @param userId The ID of the user to add.
     * @param role The role of the participant (e.g., member, admin).
     * @return The server's Response object.
     */
    public Response addChatParticipant(int chatId, int userId, String role) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to manage chat participants.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);
        data.put("user_id", userId);
        data.put("role", role);

        Request request = new Request(Command.ADD_CHAT_PARTICIPANT, data);
        Response response = sendRequestAndAwaitResponse(request);
        notifyCommandResponse(response);
        return response;
    }

    /**
     * Retrieves participants of a specific chat.
     * @param chatId The ID of the chat.
     * @return The server's Response object.
     */
    public Response getChatParticipants(int chatId) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to get chat participants.", null);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("chat_id", chatId);
        Request request = new Request(Command.GET_CHAT_PARTICIPANTS, params);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess()) {
            Type participantListType = new TypeToken<List<ChatParticipant>>() {}.getType();
            List<ChatParticipant> participants = gson.fromJson(response.getData(), participantListType);
            notifyChatParticipantsRetrieved(participants, chatId); // Notify dedicated listener
        } else if (response != null) {
            notifyCommandResponse(response);
        }
        return response;
    }

    /**
     * Updates a participant's role in a chat.
     * @param chatId The ID of the chat.
     * @param userId The ID of the participant.
     * @param newRole The new role.
     * @return The server's Response object.
     */
    public Response updateChatParticipantRole(int chatId, int userId, String newRole) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to update participant roles.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);
        data.put("user_id", userId);
        data.put("new_role", newRole);

        Request request = new Request(Command.UPDATE_CHAT_PARTICIPANT_ROLE, data);
        Response response = sendRequestAndAwaitResponse(request);
        notifyCommandResponse(response);
        return response;
    }

    /**
     * Removes a participant from a chat.
     * @param chatId The ID of the chat.
     * @param userId The ID of the participant to remove.
     * @return The server's Response object.
     */
    public Response removeChatParticipant(int chatId, int userId) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to remove participants.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);
        data.put("user_id", userId);

        Request request = new Request(Command.REMOVE_CHAT_PARTICIPANT, data);
        Response response = sendRequestAndAwaitResponse(request);
        notifyCommandResponse(response);
        return response;
    }

    /**
     * Adds a user to the current user's contacts.
     * @param contactUserId The ID of the user to add as a contact.
     * @return The server's Response object.
     */
    public Response addContact(int contactUserId) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to add contacts.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("contact_user_id", contactUserId);
        Request request = new Request(Command.ADD_CONTACT, data);
        Response response = sendRequestAndAwaitResponse(request);
        notifyCommandResponse(response);
        return response;
    }

    /**
     * Retrieves the current user's contacts.
     * @return The server's Response object.
     */
    public Response getContacts() {
        if (currentUser == null) {
            return new Response(false, "Authentication required to get contacts.", null);
        }
        Request request = new Request(Command.GET_CONTACTS);
        Response response = sendRequestAndAwaitResponse(request);
        if (response != null && response.isSuccess() && "User contacts retrieved.".equals(response.getMessage())) {
            Type contactListType = new TypeToken<List<User>>() {}.getType();
            List<User> contacts = gson.fromJson(response.getData(), contactListType);
            notifyContactsRetrieved(contacts); // Notify dedicated listener
        } else if (response != null) {
            notifyCommandResponse(response);
        }
        return response;
    }

    /**
     * Removes a user from the current user's contacts.
     * @param contactUserId The ID of the contact to remove.
     * @return The server's Response object.
     */
    public Response removeContact(int contactUserId) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to remove contacts.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("contact_user_id", contactUserId);
        Request request = new Request(Command.REMOVE_CONTACT, data);
        Response response = sendRequestAndAwaitResponse(request);
        notifyCommandResponse(response);
        return response;
    }

    /**
     * Blocks or unblocks a target user.
     * @param targetUserId The ID of the user to block/unblock.
     * @param action The action ("block" or "unblock").
     * @return The server's Response object.
     */
    public Response blockUnblockUser(int targetUserId, String action) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to block/unblock users.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("target_user_id", targetUserId);
        data.put("action", action);

        Request request = new Request(Command.BLOCK_UNBLOCK_USER, data);
        Response response = sendRequestAndAwaitResponse(request);
        notifyCommandResponse(response);
        return response;
    }

    /**
     * Retrieves the current user's notifications.
     * @return The server's Response object.
     */
    public Response getNotifications() {
        if (currentUser == null) {
            return new Response(false, "Authentication required to get notifications.", null);
        }
        Request request = new Request(Command.MY_NOTIFICATIONS);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess() && "User notifications retrieved.".equals(response.getMessage())) {
            Type notificationListType = new TypeToken<List<Notification>>() {}.getType();
            List<Notification> notifications = gson.fromJson(response.getData(), notificationListType);
            notifyNotificationsRetrieved(notifications); // Notify dedicated listener
        } else if (response != null) {
            notifyCommandResponse(response);
        }
        return response;
    }

    /**
     * Marks a specific notification as read.
     * @param notificationId The ID of the notification to mark as read.
     * @return The server's Response object.
     */
    public Response markNotificationAsRead(int notificationId) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to mark notifications as read.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("notification_id", notificationId);
        Request request = new Request(Command.MARK_NOTIFICATION_AS_READ, data);
        Response response = sendRequestAndAwaitResponse(request);
        notifyCommandResponse(response);
        return response;
    }

    /**
     * Deletes a specific notification.
     * @param notificationId The ID of the notification to delete.
     * @return The server's Response object.
     */
    public Response deleteNotification(int notificationId) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to delete notifications.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("notification_id", notificationId);
        Request request = new Request(Command.DELETE_NOTIFICATION, data);
        Response response = sendRequestAndAwaitResponse(request);
        notifyCommandResponse(response);
        return response;
    }

    /**
     * Updates the content of a message.
     * @param messageId The ID of the message to update.
     * @param newContent The new content for the message.
     * @return The server's Response object.
     */
    public Response updateMessage(int messageId, String newContent) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to update messages.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("message_id", messageId);
        data.put("new_content", newContent);
        Request request = new Request(Command.UPDATE_MESSAGE, data);
        Response response = sendRequestAndAwaitResponse(request);
        notifyCommandResponse(response);
        return response;
    }

    /**
     * Deletes a message.
     * @param messageId The ID of the message to delete.
     * @return The server's Response object.
     */
    public Response deleteMessage(int messageId) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to delete messages.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("message_id", messageId);
        Request request = new Request(Command.DELETE_MESSAGE, data);
        Response response = sendRequestAndAwaitResponse(request);
        notifyCommandResponse(response);
        return response;
    }

    /**
     * Marks a message as read.
     * @param messageId The ID of the message to mark as read.
     * @return The server's Response object.
     */
    public Response markMessageAsRead(int messageId) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to mark messages as read.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("message_id", messageId);
        data.put("user_id", currentUser.getId());
        Request request = new Request(Command.MARK_MESSAGE_AS_READ, data);
        Response response = sendRequestAndAwaitResponse(request);
        notifyCommandResponse(response);
        return response;
    }

    /**
     * Deletes a chat.
     * @param chatId The ID of the chat to delete.
     * @return The server's Response object.
     */
    public Response deleteChat(int chatId) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to delete chats.", null);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("chatId", chatId);
        Request request = new Request(Command.DELETE_CHAT, params);
        Response response = sendRequestAndAwaitResponse(request);
        notifyCommandResponse(response);
        return response;
    }

    /**
     * Logs out the current user.
     * @return The server's Response object.
     */
    public Response logout() {
        if (currentUser == null) {
            return new Response(true, "Not currently logged in.", null);
        }
        Request request = new Request(Command.LOGOUT);
        Response logoutResponse = sendRequestAndAwaitResponse(request);
        if (logoutResponse != null && logoutResponse.isSuccess()) {
            currentUser = null;
            notifyStatusUpdate(logoutResponse.getMessage());
        } else if (logoutResponse != null) {
            notifyCommandResponse(logoutResponse);
        }
        return logoutResponse;
    }

    /**
     * Retrieves unread messages after a specific message ID in a chat.
     * @param chatId The ID of the chat.
     * @param lastMessageId The ID of the last message read.
     * @return The server's Response object.
     */
    public Response getUnreadMessagesAfterId(int chatId, int lastMessageId) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to get unread messages.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);
        data.put("lastMessageId", lastMessageId);
        Request request = new Request(Command.GET_CHAT_UNREADMESSAGES, data);

        Response unreadMessagesResponse = sendRequestAndAwaitResponse(request);
        if (unreadMessagesResponse != null && unreadMessagesResponse.isSuccess() && "Messages retrieved.".equals(unreadMessagesResponse.getMessage())) {
            Type messageListType = new TypeToken<List<Message>>() {}.getType();
            List<Message> messages = gson.fromJson(unreadMessagesResponse.getData(), messageListType);
            notifyMessagesRetrieved(messages, chatId); // Reuse messages retrieved listener
        } else if (unreadMessagesResponse != null) {
            notifyCommandResponse(unreadMessagesResponse);
        }
        return unreadMessagesResponse;
    }

    /**
     * Retrieves a chat by its ID.
     * @param chatId The ID of the chat to retrieve.
     * @return The server's Response object.
     */
    public Response getChatById(int chatId) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to get chat by ID.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);
        // Assuming Command.GET_CHAT_BY_ID exists in your Command enum
        Request request = new Request(Command.GET_CHAT_BY_ID, data);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess() && "Chat retrieved by id.".equals(response.getMessage())) {
            Type chatType = new TypeToken<Chat>() {}.getType();
            Chat chat = gson.fromJson(response.getData(), chatType);
            notifyChatRetrieved(chat); // Notify dedicated listener
        } else if (response != null) {
            notifyCommandResponse(response);
        }
        return response;
    }

    /**
     * Retrieves a user by their phone number.
     * @param phoneNumber The phone number of the user to retrieve.
     * @return The server's Response object.
     */
    public Response getUserByPhoneNumber(String phoneNumber) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to get user by phone number.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("phone_number", phoneNumber); // Corrected key to be consistent
        // Assuming Command.GET_USER_BY_PHONENUMBER exists in your Command enum
        Request request = new Request(Command.GET_USER_BY_PHONENUMBER, data);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess() && "User retrieved by phone number.".equals(response.getMessage())) {
            Type userType = new TypeToken<User>() {}.getType();
            User user = gson.fromJson(response.getData(), userType);
            notifyUserRetrieved(user); // Notify dedicated listener
        } else if (response != null) {
            notifyCommandResponse(response);
        }
        return response;
    }

    /**
     * Retrieves a user by their ID.
     * @param userId The ID of the user to retrieve.
     * @return The server's Response object.
     */
    public Response getUserById(int userId) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to get user by ID.", null);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("user_id", userId);
        // Assuming Command.GET_USER_BY_ID exists in your Command enum
        Request request = new Request(Command.GET_USER_BY_ID, data);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess() && "User retrieved by id.".equals(response.getMessage())) {
            Type userType = new TypeToken<User>() {}.getType();
            User user = gson.fromJson(response.getData(), userType);
            notifyUserRetrieved(user); // Notify dedicated listener
        } else if (response != null) {
            notifyCommandResponse(response);
        }
        return response;
    }


    /**
     * Sends a request to the server and waits for a response from the response queue.
     * This is a core private helper method used by all public command methods.
     *
     * @param request The Request object to send.
     * @return The Response object received from the server, or a timeout response.
     */
    private Response sendRequestAndAwaitResponse(Request request) {
        try {
            responseQueue.clear(); // Clear any stale responses
            out.println(gson.toJson(request));
            Response response = responseQueue.poll(30, TimeUnit.SECONDS); // 30-second timeout

            if (response == null) {
                String errorMsg = "No response from server within timeout for command: " + request.getCommand();
                notifyConnectionFailure(errorMsg); // Use specific connection failure listener
                return new Response(false, "Server response timed out.", null);
            }
            return response;
        } catch (InterruptedException e) {
            String errorMsg = "Waiting for response interrupted: " + e.getMessage();
            notifyConnectionFailure(errorMsg);
            Thread.currentThread().interrupt();
            return new Response(false, "Client interrupted.", null);
        }
    }

    /**
     * Sends file bytes to the file transfer server.
     * This method is called internally by the listener thread when the server is ready.
     * @param filePath The path to the file to send.
     * @param transferId The transfer ID provided by the main server.
     * @param fileTransferListener The specific listener for this transfer.
     */
    private void sendFileBytes(String filePath, String transferId, OnFileTransferListener fileTransferListener) {
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            String errorMsg = "File not found or is not a regular file: " + filePath;
            if (fileTransferListener != null) fileTransferListener.onFail(errorMsg);
            notifyConnectionFailure(errorMsg);
            return;
        }

        try (Socket fileSocket = new Socket(SERVER_IP, FILE_TRANSFER_PORT);
             OutputStream os = fileSocket.getOutputStream();
             BufferedReader serverResponseReader = new BufferedReader(new InputStreamReader(fileSocket.getInputStream()));
             FileInputStream fis = new FileInputStream(file)) {

            notifyStatusUpdate("Connecting to file transfer server on port " + FILE_TRANSFER_PORT + " for sending...");
            PrintWriter socketWriter = new PrintWriter(os, true);
            socketWriter.println(transferId);
            notifyStatusUpdate("Sent transferId: " + transferId + " to file server.");

            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalBytesSent = 0;
            long fileSize = file.length();

            notifyStatusUpdate("Sending file: " + file.getName() + " (" + fileSize + " bytes)");

            while ((bytesRead = fis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                totalBytesSent += bytesRead;
                if (fileTransferListener != null) {
                    fileTransferListener.onProgress(totalBytesSent, fileSize);
                }
            }
            os.flush();

            String fileTransferStatus = serverResponseReader.readLine();
            if (fileTransferStatus != null && fileTransferStatus.equals("FILE_RECEIVED_SUCCESS")) {
                if (fileTransferListener != null) fileTransferListener.onComplete(file);
                notifyStatusUpdate("File '" + file.getName() + "' sent successfully!");
            } else {
                String errorMsg = "File server reported failure or unexpected response: " + fileTransferStatus;
                if (fileTransferListener != null) fileTransferListener.onFail(errorMsg);
                notifyConnectionFailure(errorMsg);
            }

        } catch (IOException e) {
            String errorMsg = "Error during file send transfer: " + e.getMessage();
            if (fileTransferListener != null) fileTransferListener.onFail(errorMsg);
            notifyConnectionFailure(errorMsg);
            e.printStackTrace();
        } finally {
            // Reset the temporary listener and file path after the transfer attempt
            this.currentFileTransferListener = null;
            this.currentFilePathToSend = null;
            this.pendingFileTransferId = null;
        }
    }

    /**
     * Requests and receives a media file from the server.
     * @param media The Media object containing details of the file to download.
     * @param saveDirectory The directory where the file should be saved.
     * @param fileTransferListener A specific listener for this file transfer's progress/completion.
     * @return The server's Response object for the initial request to get the file.
     */
    public Response getFileByMedia(Media media, String saveDirectory, OnFileTransferListener fileTransferListener) {
        if (currentUser == null) {
            return new Response(false, "Authentication required to download files.", null);
        }
        if (media == null || media.getId() == 0 || media.getFileName() == null || media.getFileName().isEmpty()) {
            String errorMsg = "Error: Invalid media object. Missing mediaId or fileName.";
            if (fileTransferListener != null) fileTransferListener.onFail(errorMsg);
            return new Response(false, errorMsg, null);
        }

        // Set the specific listener for this download operation
        this.currentFileTransferListener = fileTransferListener;

        try {
            Map<String, Object> data = new HashMap<>();
            data.put("mediaId", media.getId());
            data.put("fileName", media.getFileName());

            Request request = new Request(Command.GET_FILE_BY_MEDIA, data);
            out.println(gson.toJson(request));

            Response response = responseQueue.poll(30, TimeUnit.SECONDS);

            if (response == null) {
                String errorMsg = "Server response timed out for file download request.";
                if (fileTransferListener != null) fileTransferListener.onFail(errorMsg);
                notifyConnectionFailure(errorMsg);
                return new Response(false, errorMsg, null);
            }

            if (response.isSuccess() && "READY_TO_SEND_FILE".equals(response.getMessage())) {
                Type type = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> responseData = gson.fromJson(response.getData(), type);
                String transferId = (String) responseData.get("transfer_id");
                long fileSize = ((Double) responseData.get("fileSize")).longValue();

                notifyStatusUpdate("Server is ready to send the file. Initiating download...");
                receiveFileBytes(transferId, media.getFileName(), fileSize, saveDirectory, fileTransferListener);

            } else {
                String errorMsg = "Server failed to initiate file download: " + response.getMessage();
                if (fileTransferListener != null) fileTransferListener.onFail(errorMsg);
                notifyCommandResponse(response); // Notify general listeners about the failure
            }
            return response;

        } catch (Exception e) {
            String errorMsg = "Error during file download process: " + e.getMessage();
            if (fileTransferListener != null) fileTransferListener.onFail(errorMsg);
            notifyConnectionFailure(errorMsg);
            e.printStackTrace();
            return new Response(false, errorMsg, null);
        } finally {
            // Reset the temporary listener after the download request is processed
            this.currentFileTransferListener = null;
        }
    }

    /**
     * Receives file bytes from the file transfer server.
     * @param transferId The transfer ID to identify the file on the server.
     * @param fileName The name of the file to save.
     * @param fileSize The expected size of the file.
     * @param saveDirectory The directory where the file should be saved.
     * @param fileTransferListener The specific listener for this transfer.
     */
    private void receiveFileBytes(String transferId, String fileName, long fileSize, String saveDirectory, OnFileTransferListener fileTransferListener) {
        File outputFile = new File(saveDirectory, fileName);
        try {
            File saveDir = new File(saveDirectory);
            if (!saveDir.exists()) {
                saveDir.mkdirs();
            }

            try (Socket fileSocket = new Socket(SERVER_IP, FILE_TRANSFER_PORT);
                 InputStream is = fileSocket.getInputStream();
                 OutputStream os = fileSocket.getOutputStream(); // For sending transferId
                 FileOutputStream fos = new FileOutputStream(outputFile)) {

                notifyStatusUpdate("Connecting to file transfer server for download...");
                PrintWriter socketWriter = new PrintWriter(os, true);

                // Send the transfer ID to the file server to identify the file
                socketWriter.println(transferId);
                notifyStatusUpdate("Sent transferId: " + transferId + " to file server for download.");

                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalBytesReceived = 0;

                notifyStatusUpdate("Receiving file: " + fileName + " (" + fileSize + " bytes)");

                while (totalBytesReceived < fileSize && (bytesRead = is.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalBytesReceived))) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesReceived += bytesRead;
                    if (fileTransferListener != null) {
                        fileTransferListener.onProgress(totalBytesReceived, fileSize);
                    }
                }
                fos.flush();

                if (totalBytesReceived == fileSize) {
                    if (fileTransferListener != null) fileTransferListener.onComplete(outputFile);
                    notifyStatusUpdate("File '" + fileName + "' received successfully and saved to " + outputFile.getAbsolutePath());
                } else {
                    String errorMsg = "File transfer incomplete. Expected: " + fileSize + ", Received: " + totalBytesReceived;
                    if (fileTransferListener != null) fileTransferListener.onFail(errorMsg);
                    notifyConnectionFailure(errorMsg);
                    outputFile.delete(); // Clean up incomplete file
                }
            }
        } catch (IOException e) {
            String errorMsg = "Error during file download: " + e.getMessage();
            if (fileTransferListener != null) fileTransferListener.onFail(errorMsg);
            notifyConnectionFailure(errorMsg);
            e.printStackTrace();
        } finally {
            // Reset the temporary listener after the transfer attempt
            this.currentFileTransferListener = null;
        }
    }

    /**
     * Closes all client resources.
     * @throws Exception If an error occurs during closing.
     */
    @Override
    public void close() throws Exception {
        closeConnection();
    }

    /**
     * Helper method to close network connections.
     */
    private void closeConnection() {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
            if (out != null) {
                out.close();
            }
            if (in != null) {
                in.close();
            }
            notifyStatusUpdate("Client connection closed.");
        } catch (IOException e) {
            notifyConnectionFailure("Error closing client resources: " + e.getMessage());
        }
    }

    /**
     * Main method for demonstration purposes.
     * In a real JavaFX application, you would typically initialize and register listeners
     * within your Application's start method or a controller.
     */
    public static void main(String[] args) {
        ChatClient client = ChatClient.getInstance();

        // Example of adding listeners (these would be your JavaFX controller methods)
        client.addOnCommandResponseListener(response ->
                System.out.println("[CMD_RESP] Success=" + response.isSuccess() + ", Msg=" + response.getMessage()));
        client.addOnNewMessageListener(message ->
                System.out.println("[NEW_MSG] From " + message.getSenderId() + ": " + message.getContent()));
        client.addOnLoginSuccessListener(user ->
                System.out.println("[LOGIN_SUCCESS] Logged in as: " + user.getPhoneNumber()));
        client.addOnConnectionFailureListener(error ->
                System.err.println("[CONN_FAIL] " + error));
        client.addOnStatusUpdateListener(status ->
                System.out.println("[STATUS] " + status));
        client.addOnMessagesRetrievedListener((messages, chatId) ->
                System.out.println("[MSGS_RETRIEVED] Chat " + chatId + ": " + messages.size() + " messages."));
        client.addOnChatRetrievedListener(chat ->
                System.out.println("[CHAT_RETRIEVED] Chat Name: " + chat.getChatName() + ", ID: " + chat.getId()));
        client.addOnUserRetrievedListener(user ->
                System.out.println("[USER_RETRIEVED] User Name: " + user.getFirstName() + " " + user.getLastName() + ", Phone: " + user.getPhoneNumber()));

        // Add other listeners as needed for testing

        // Example usage (these calls would be triggered by JavaFX UI actions on background threads)
        // new Thread(() -> {
        //     Response loginResponse = client.login("your_phone_number", "your_password");
        //     if (loginResponse.isSuccess()) {
        //         // Now retrieve a chat by ID
        //         client.getChatById(123); // Replace with a valid chat ID
        //
        //         // Now retrieve a user by phone number
        //         client.getUserByPhoneNumber("1234567890"); // Replace with a valid phone number
        //
        //         // Now retrieve a user by ID
        //         client.getUserById(456); // Replace with a valid user ID
        //     }
        // }).start();


        // Keep the main thread alive for the listener thread to run.
        // In a JavaFX app, the JavaFX Application thread would keep it alive.
        try {
            Thread.sleep(60000); // Keep alive for 60 seconds for demonstration
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                client.close();
            } catch (Exception e) {
                System.err.println("Error closing client: " + e.getMessage());
            }
        }
    }
}
