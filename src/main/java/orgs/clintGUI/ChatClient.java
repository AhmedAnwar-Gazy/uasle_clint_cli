package orgs.clintGUI;

import orgs.model.Message;
import orgs.model.User;
import orgs.model.Chat;
import orgs.model.Media;
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
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * ChatClient class implemented as a Singleton for use in a JavaFX application.
 * It handles network communication with the chat server. All user interaction
 * logic (input/output) has been removed and replaced with a listener mechanism
 * to communicate with the JavaFX UI.
 */
public class ChatClient implements AutoCloseable {
    // Singleton instance
    private static ChatClient instance;

    private static final String SERVER_IP = "192.168.1.99"; // Localhost
    private static final int SERVER_PORT = 6373;
    private static final int FILE_TRANSFER_PORT = 6374;

    private String currentFilePathToSend;
    private String pendingFileTransferId;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    private Gson gson = new GsonBuilder()
            .registerTypeAdapter(LocalDateTime.class, new LocalDateTimeAdapter())
            .serializeNulls()
            .create();

    private User currentUser;
    private ChatClientListener listener; // Listener for UI updates

    private final BlockingQueue<Response> responseQueue = new LinkedBlockingQueue<>();

    /**
     * Private constructor to prevent direct instantiation.
     * Initializes network connections and starts a listener thread.
     * Note: Connection is established immediately upon instantiation.
     */
    private ChatClient() {
        // No scanner here, as input is handled by JavaFX UI
        try {
            socket = new Socket(SERVER_IP, SERVER_PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            if (listener != null) listener.onStatusUpdate("Connected to chat server on main port.");
            else System.out.println("Connected to chat server on main port."); // Fallback for no listener

            new Thread(this::listenForServerMessages, "ServerListener").start();

        } catch (IOException e) {
            String errorMsg = "Error connecting to server: " + e.getMessage();
            if (listener != null) listener.onError(errorMsg);
            else System.err.println(errorMsg); // Fallback for no listener
            // In a real JavaFX app, you might show an alert and exit gracefully
            // System.exit(1); // Removed for better JavaFX integration, let UI handle
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

    /**
     * Sets the listener for receiving updates from the ChatClient.
     * This method should be called by the JavaFX controller or main application class.
     * @param listener The implementation of ChatClientListener.
     */
    public void setListener(ChatClientListener listener) {
        this.listener = listener;
    }

    /**
     * Listens for incoming messages from the server and processes them.
     * Responses are added to a queue for the main thread to process or
     * dispatched directly to the listener for new messages.
     */
    private void listenForServerMessages() {
        try {
            String serverResponseJson;
            while ((serverResponseJson = in.readLine()) != null) {
                Response response = gson.fromJson(serverResponseJson, Response.class);
                // System.out.println("[DEBUG - Raw Server Response]: " + serverResponseJson); // Debugging can stay

                // Special handling for file transfer initiation
                if ("READY_TO_RECEIVE_FILE".equals(response.getMessage())) {
                    if (listener != null) listener.onStatusUpdate("Server is ready for file transfer. Initiating file send...");
                    Type type = new TypeToken<Map<String, String>>() {}.getType();
                    Map<String, String> data = gson.fromJson(response.getData(), type);

                    pendingFileTransferId = data.get("transfer_id");

                    if (pendingFileTransferId != null) {
                        sendFileBytes(currentFilePathToSend, pendingFileTransferId);
                    } else {
                        if (listener != null) listener.onError("Error: Server responded READY_TO_RECEIVE_FILE but no transfer_id found in data.");
                    }
                    continue; // Do not put this into the main response queue
                }

                // Handle unsolicited new messages (e.g., from other users)
                if (response.isSuccess() && "New message received".equals(response.getMessage())) {
                    Message newMessage = gson.fromJson(response.getData(), Message.class);
                    if (listener != null) {
                        listener.onNewMessageReceived(newMessage);
                    }
                }
                // Handle general success/failure messages for commands that don't need special parsing
                else {
                    responseQueue.put(response); // Put all other responses in the queue for the caller
                }
            }
        } catch (SocketException e) {
            if (listener != null) listener.onError("Server connection lost: " + e.getMessage());
            else System.err.println("Server connection lost: " + e.getMessage());
        } catch (IOException e) {
            if (listener != null) listener.onError("Error reading from server: " + e.getMessage());
            else System.err.println("Error reading from server: " + e.getMessage());
        } catch (InterruptedException e) {
            if (listener != null) listener.onError("Listener thread interrupted: " + e.getMessage());
            else System.err.println("Listener thread interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            closeConnection();
        }
    }

    /**
     * Initiates the client connection. In a JavaFX app, this might be called
     * on application startup or when a "Connect" button is pressed.
     */
    public void connect() {
        // The constructor already attempts connection. This method can be used
        // to re-establish if connection was lost, or as a conceptual "start".
        // For simplicity, we assume the constructor handles the initial connection.
        if (socket == null || socket.isClosed()) {
            // Re-attempt connection if needed, though current constructor behavior
            // means a new instance would be needed or specific reconnection logic.
            // For now, assume the initial connection in constructor is sufficient.
            if (listener != null) listener.onStatusUpdate("Client already connected or attempting reconnection...");
        } else {
            if (listener != null) listener.onStatusUpdate("Client is already connected.");
        }
    }

    /**
     * Attempts to log in a user.
     * @param phoneNumber The user's phone number.
     * @param password The user's password.
     */
    public void login(String phoneNumber, String password) {
        Map<String, Object> authData = new HashMap<>();
        authData.put("phone_number", phoneNumber);
        authData.put("password", password);

        Request loginRequest = new Request(Command.LOGIN, authData);
        Response loginResponse = sendRequestAndAwaitResponse(loginRequest);

        if (loginResponse != null && loginResponse.isSuccess()) {
            this.currentUser = gson.fromJson(loginResponse.getData(), User.class);
            if (listener != null) {
                listener.onLoginSuccess(currentUser);
                listener.onStatusUpdate("Logged in as: " + currentUser.getPhoneNumber() + " (" + currentUser.getFirstName() + " " + currentUser.getLastName() + ")");
            }
        } else if (loginResponse != null) {
            if (listener != null) listener.onCommandResponse(loginResponse);
        }
    }

    /**
     * Attempts to register a new user.
     * @param phoneNumber The new user's phone number.
     * @param password The new user's password.
     * @param firstName The new user's first name.
     * @param lastName The new user's last name.
     */
    public void register(String phoneNumber, String password, String firstName, String lastName) {
        Map<String, Object> authData = new HashMap<>();
        authData.put("phone_number", phoneNumber);
        authData.put("password", password);
        authData.put("first_name", firstName);
        authData.put("last_name", lastName);

        Request registerRequest = new Request(Command.REGISTER, authData);
        Response registerResponse = sendRequestAndAwaitResponse(registerRequest);

        if (listener != null) listener.onCommandResponse(registerResponse);
    }

    /**
     * Sends a message, either text or media.
     * @param chatId The ID of the chat to send the message to.
     * @param content The text content of the message (can be null for media-only).
     * @param filePath The local file path for media messages (null for text messages).
     * @param caption The caption for media messages (can be null).
     * @param mediaType The type of media (e.g., "image", "video", "voiceNote", "file").
     */
    public void sendMessage(int chatId, String content, String filePath, String caption, String mediaType) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to send messages.");
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);

        if (filePath != null && !filePath.isEmpty()) {
            File file = new File(filePath);
            if (!file.exists() || !file.isFile()) {
                if (listener != null) listener.onError("Error: File not found or is not a regular file at " + filePath);
                return;
            }
            currentFilePathToSend = filePath;
            long fileSize = file.length();
            String fileName = file.getName();

            Media media = new Media();
            media.setFileName(fileName);
            media.setFileSize(fileSize);
            media.setMediaType(mediaType);
            media.setUploadedByUserId(currentUser.getId());
            media.setUploadedAt(LocalDateTime.now());

            data.put("content", (caption != null && !caption.isEmpty()) ? caption : null);
            data.put("media", media);
            sendRequestAndProcessResponse(new Request(Command.SEND_MESSAGE, data));

        } else { // Text message
            if (content == null || content.trim().isEmpty()) {
                if (listener != null) listener.onError("Text message content cannot be empty.");
                return;
            }
            data.put("content", content);
            sendRequestAndProcessResponse(new Request(Command.SEND_MESSAGE, data));
        }
    }

    /**
     * Retrieves chat messages for a given chat ID.
     * @param chatId The ID of the chat.
     * @param limit The maximum number of messages to fetch.
     * @param offset The starting point (offset) for fetching messages.
     */
    public void getChatMessages(int chatId, int limit, int offset) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to get messages.");
            return;
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
            if (listener != null) listener.onMessagesRetrieved(messages, chatId);
        } else if (messagesResponse != null) {
            if (listener != null) listener.onCommandResponse(messagesResponse);
        }
    }

    /**
     * Creates a new chat.
     * @param chatType The type of chat (private, group, channel).
     * @param chatName The name of the chat (optional for private).
     * @param chatDescription The description of the chat (optional).
     * @param publicLink The public link for public channels (optional).
     */
    public void createChat(String chatType, String chatName, String chatDescription, String publicLink) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to create chats.");
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("chat_type", chatType);
        data.put("chat_name", chatName != null && !chatName.isEmpty() ? chatName : null);
        data.put("chat_description", chatDescription != null && !chatDescription.isEmpty() ? chatDescription : null);
        data.put("public_link", publicLink != null && !publicLink.isEmpty() ? publicLink : null);

        sendRequestAndProcessResponse(new Request(Command.CREATE_CHAT, data));
    }

    /**
     * Retrieves all registered users.
     */
    public void getAllUsers() {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to get all users.");
            return;
        }
        Request request = new Request(Command.GET_ALL_USERS);
        Response allUsersResponse = sendRequestAndAwaitResponse(request);
        if (allUsersResponse != null && allUsersResponse.isSuccess() && "All users retrieved.".equals(allUsersResponse.getMessage())) {
            Type userListType = new TypeToken<List<User>>() {}.getType();
            List<User> users = gson.fromJson(allUsersResponse.getData(), userListType);
            if (listener != null) listener.onAllUsersRetrieved(users);
        } else if (allUsersResponse != null) {
            if (listener != null) listener.onCommandResponse(allUsersResponse);
        }
    }

    /**
     * Retrieves the current user's chats.
     */
    public void getUserChats() {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to get your chats.");
            return;
        }
        Request request = new Request(Command.GET_USER_CHATS);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess() && "All chats retrieved for user.".equals(response.getMessage())) {
            Type chatListType = new TypeToken<List<Chat>>() {}.getType();
            List<Chat> chats = gson.fromJson(response.getData(), chatListType);
            if (listener != null) listener.onUserChatsRetrieved(chats);
        } else if (response != null) {
            if (listener != null) listener.onCommandResponse(response);
        }
    }

    /**
     * Adds a participant to a chat.
     * @param chatId The ID of the chat.
     * @param userId The ID of the user to add.
     * @param role The role of the participant (e.g., member, admin).
     */
    public void addChatParticipant(int chatId, int userId, String role) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to manage chat participants.");
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);
        data.put("user_id", userId);
        data.put("role", role);

        Request request = new Request(Command.ADD_CHAT_PARTICIPANT, data);
        sendRequestAndProcessResponse(request);
    }

    /**
     * Retrieves participants of a specific chat.
     * @param chatId The ID of the chat.
     */
    public void getChatParticipants(int chatId) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to get chat participants.");
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("chat_id", chatId);
        Request request = new Request(Command.GET_CHAT_PARTICIPANTS, params);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess()) {
            Type participantListType = new TypeToken<List<orgs.model.ChatParticipant>>() {}.getType();
            List<orgs.model.ChatParticipant> participants = gson.fromJson(response.getData(), participantListType);
            if (listener != null) listener.onChatParticipantsRetrieved(participants, chatId);
        } else if (response != null) {
            if (listener != null) listener.onCommandResponse(response);
        }
    }

    /**
     * Updates a participant's role in a chat.
     * @param chatId The ID of the chat.
     * @param userId The ID of the participant.
     * @param newRole The new role.
     */
    public void updateChatParticipantRole(int chatId, int userId, String newRole) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to update participant roles.");
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);
        data.put("user_id", userId);
        data.put("new_role", newRole);

        Request request = new Request(Command.UPDATE_CHAT_PARTICIPANT_ROLE, data);
        sendRequestAndProcessResponse(request);
    }

    /**
     * Removes a participant from a chat.
     * @param chatId The ID of the chat.
     * @param userId The ID of the participant to remove.
     */
    public void removeChatParticipant(int chatId, int userId) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to remove participants.");
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);
        data.put("user_id", userId);

        Request request = new Request(Command.REMOVE_CHAT_PARTICIPANT, data);
        sendRequestAndProcessResponse(request);
    }

    /**
     * Adds a user to the current user's contacts.
     * @param contactUserId The ID of the user to add as a contact.
     */
    public void addContact(int contactUserId) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to add contacts.");
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("contact_user_id", contactUserId);
        Request request = new Request(Command.ADD_CONTACT, data);
        sendRequestAndProcessResponse(request);
    }

    /**
     * Retrieves the current user's contacts.
     */
    public void getContacts() {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to get contacts.");
            return;
        }
        Request request = new Request(Command.GET_CONTACTS);
        Response response = sendRequestAndAwaitResponse(request);
        if (response != null && response.isSuccess() && "User contacts retrieved.".equals(response.getMessage())) {
            Type contactListType = new TypeToken<List<User>>() {}.getType();
            List<User> contacts = gson.fromJson(response.getData(), contactListType);
            if (listener != null) listener.onContactsRetrieved(contacts);
        } else if (response != null) {
            if (listener != null) listener.onCommandResponse(response);
        }
    }

    /**
     * Removes a user from the current user's contacts.
     * @param contactUserId The ID of the contact to remove.
     */
    public void removeContact(int contactUserId) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to remove contacts.");
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("contact_user_id", contactUserId);
        Request request = new Request(Command.REMOVE_CONTACT, data);
        sendRequestAndProcessResponse(request);
    }

    /**
     * Blocks or unblocks a target user.
     * @param targetUserId The ID of the user to block/unblock.
     * @param action The action ("block" or "unblock").
     */
    public void blockUnblockUser(int targetUserId, String action) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to block/unblock users.");
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("target_user_id", targetUserId);
        data.put("action", action);

        Request request = new Request(Command.BLOCK_UNBLOCK_USER, data);
        sendRequestAndProcessResponse(request);
    }

    /**
     * Retrieves the current user's notifications.
     */
    public void getNotifications() {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to get notifications.");
            return;
        }
        Request request = new Request(Command.MY_NOTIFICATIONS);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess() && "User notifications retrieved.".equals(response.getMessage())) {
            Type notificationListType = new TypeToken<List<orgs.model.Notification>>() {}.getType();
            List<orgs.model.Notification> notifications = gson.fromJson(response.getData(), notificationListType);
            if (listener != null) listener.onNotificationsRetrieved(notifications);
        } else if (response != null) {
            if (listener != null) listener.onCommandResponse(response);
        }
    }

    /**
     * Marks a specific notification as read.
     * @param notificationId The ID of the notification to mark as read.
     */
    public void markNotificationAsRead(int notificationId) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to mark notifications as read.");
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("notification_id", notificationId);
        Request request = new Request(Command.MARK_NOTIFICATION_AS_READ, data);
        sendRequestAndProcessResponse(request);
    }

    /**
     * Deletes a specific notification.
     * @param notificationId The ID of the notification to delete.
     */
    public void deleteNotification(int notificationId) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to delete notifications.");
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("notification_id", notificationId);
        Request request = new Request(Command.DELETE_NOTIFICATION, data);
        sendRequestAndProcessResponse(request);
    }

    /**
     * Updates the content of a message.
     * @param messageId The ID of the message to update.
     * @param newContent The new content for the message.
     */
    public void updateMessage(int messageId, String newContent) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to update messages.");
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("message_id", messageId);
        data.put("new_content", newContent);
        Request request = new Request(Command.UPDATE_MESSAGE, data);
        sendRequestAndProcessResponse(request);
    }

    /**
     * Deletes a message.
     * @param messageId The ID of the message to delete.
     */
    public void deleteMessage(int messageId) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to delete messages.");
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("message_id", messageId);
        Request request = new Request(Command.DELETE_MESSAGE, data);
        sendRequestAndProcessResponse(request);
    }

    /**
     * Marks a message as read.
     * @param messageId The ID of the message to mark as read.
     */
    public void markMessageAsRead(int messageId) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to mark messages as read.");
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("message_id", messageId);
        data.put("user_id", currentUser.getId());
        Request request = new Request(Command.MARK_MESSAGE_AS_READ, data);
        sendRequestAndProcessResponse(request);
    }

    /**
     * Deletes a chat.
     * @param chatId The ID of the chat to delete.
     */
    public void deleteChat(int chatId) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to delete chats.");
            return;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("chatId", chatId);
        Request request = new Request(Command.DELETE_CHAT, params);
        sendRequestAndProcessResponse(request);
    }

    /**
     * Logs out the current user.
     */
    public void logout() {
        if (currentUser == null) {
            if (listener != null) listener.onStatusUpdate("Not currently logged in.");
            return;
        }
        Request request = new Request(Command.LOGOUT);
        Response logoutResponse = sendRequestAndAwaitResponse(request);
        if (logoutResponse != null && logoutResponse.isSuccess()) {
            currentUser = null;
            if (listener != null) listener.onStatusUpdate(logoutResponse.getMessage());
        } else if (logoutResponse != null) {
            if (listener != null) listener.onCommandResponse(logoutResponse);
        }
    }

    /**
     * Retrieves unread messages after a specific message ID in a chat.
     * @param chatId The ID of the chat.
     * @param lastMessageId The ID of the last message read.
     */
    public void getUnreadMessagesAfterId(int chatId, int lastMessageId) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to get unread messages.");
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);
        data.put("lastMessageId", lastMessageId);
        Request request = new Request(Command.GET_CHAT_UNREADMESSAGES, data);

        Response unreadMessagesResponse = sendRequestAndAwaitResponse(request);
        if (unreadMessagesResponse != null && unreadMessagesResponse.isSuccess() && "Messages retrieved.".equals(unreadMessagesResponse.getMessage())) {
            Type messageListType = new TypeToken<List<Message>>() {}.getType();
            List<Message> messages = gson.fromJson(unreadMessagesResponse.getData(), messageListType);
            if (listener != null) listener.onMessagesRetrieved(messages, chatId); // Reusing onMessagesRetrieved
        } else if (unreadMessagesResponse != null) {
            if (listener != null) listener.onCommandResponse(unreadMessagesResponse);
        }
    }


    /**
     * Sends a request to the server and processes the immediate response message.
     * This is a helper for commands that don't need special parsing of the response data
     * beyond checking success and displaying the message. It also handles clearing
     * file transfer details after a successful media message send.
     *
     * @param request The Request object to send.
     */
    private void sendRequestAndProcessResponse(Request request) {
        // Call the core method that sends the request and waits for a response
        Response response = sendRequestAndAwaitResponse(request);

        // Check if a response was received
        if (response != null) {
            // Report the server's response message to the registered listener (JavaFX UI)
            if (listener != null) {
                listener.onCommandResponse(response); // Provides the full response object
            }

            // Special handling for successful message sends, especially for media
            if (response.isSuccess() && "Message sent successfully!".equals(response.getMessage()) && currentFilePathToSend != null) {
                // If a file was just sent, clear the temporary file transfer details
                if (listener != null) {
                    listener.onStatusUpdate("File transfer details cleared after successful message send.");
                }
                currentFilePathToSend = null; // Reset the path of the file that was pending to send
                pendingFileTransferId = null; // Reset the transfer ID
            }
        } else {
            // This case should ideally be handled by sendRequestAndAwaitResponse reporting a timeout,
            // but as a fallback, ensure the listener is informed if somehow null is returned.
            if (listener != null) {
                listener.onError("No response received for command: " + request.getCommand() + ". Check server connection.");
            }
        }
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
            // Clear any stale responses from the queue before sending a new request
            responseQueue.clear();

            // Convert the request object to JSON and send it to the server
            out.println(gson.toJson(request));

            // Poll the response queue, waiting for up to 30 seconds for a response
            Response response = responseQueue.poll(30, TimeUnit.SECONDS);

            // If no response is received within the timeout period
            if (response == null) {
                String errorMsg = "No response from server within timeout for command: " + request.getCommand();
                // Report the timeout error to the listener
                if (listener != null) {
                    listener.onError(errorMsg);
                }
                // Return a failure response indicating a timeout
                return new Response(false, "Server response timed out.", null);
            }
            // Return the received response
            return response;
        } catch (InterruptedException e) {
            // Handle cases where the thread waiting for a response is interrupted
            String errorMsg = "Waiting for response interrupted: " + e.getMessage();
            if (listener != null) {
                listener.onError(errorMsg);
            }
            // Re-interrupt the current thread
            Thread.currentThread().interrupt();
            // Return a failure response indicating client interruption
            return new Response(false, "Client interrupted.", null);
        }
    }
    /**
     * Sends file bytes to the file transfer server.
     * @param filePath The path to the file to send.
     * @param transferId The transfer ID provided by the main server.
     */
    private void sendFileBytes(String filePath, String transferId) {
        if (filePath == null || filePath.isEmpty()) {
            if (listener != null) listener.onError("No file path provided for transfer.");
            return;
        }
        if (transferId == null || transferId.isEmpty()) {
            if (listener != null) listener.onError("No transfer ID provided by server for file transfer.");
            return;
        }

        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            if (listener != null) listener.onError("File not found or is not a regular file: " + filePath);
            return;
        }

        try (Socket fileSocket = new Socket(SERVER_IP, FILE_TRANSFER_PORT);
             OutputStream os = fileSocket.getOutputStream();
             BufferedReader serverResponseReader = new BufferedReader(new InputStreamReader(fileSocket.getInputStream()));
             FileInputStream fis = new FileInputStream(file)) {

            if (listener != null) listener.onStatusUpdate("Connecting to file transfer server on port " + FILE_TRANSFER_PORT + "...");

            PrintWriter socketWriter = new PrintWriter(os, true);
            socketWriter.println(transferId);
            if (listener != null) listener.onStatusUpdate("Sent transferId: " + transferId + " to file server.");

            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalBytesSent = 0;
            long fileSize = file.length();

            if (listener != null) listener.onStatusUpdate("Sending file: " + file.getName() + " (" + fileSize + " bytes)");

            while ((bytesRead = fis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                totalBytesSent += bytesRead;
                // Optional: Notify UI about progress
                // if (listener != null) listener.onStatusUpdate("Sent: " + totalBytesSent + " / " + fileSize + " bytes");
            }
            os.flush();

            if (listener != null) listener.onStatusUpdate("File '" + file.getName() + "' sent successfully!");

            String fileTransferStatus = serverResponseReader.readLine();
            if (fileTransferStatus != null) {
                if (listener != null) listener.onStatusUpdate("File server response: " + fileTransferStatus);
            }

        } catch (IOException e) {
            if (listener != null) listener.onError("Error during file transfer: " + e.getMessage());
            else System.err.println("Error during file transfer: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Requests and receives a media file from the server.
     * @param media The Media object containing details of the file to download.
     * @param saveDirectory The directory where the file should be saved.
     */
    public void getFileByMedia(Media media, String saveDirectory) {
        if (currentUser == null) {
            if (listener != null) listener.onError("Authentication required to download files.");
            return;
        }
        if (media == null || media.getId() == 0 || media.getFileName() == null || media.getFileName().isEmpty()) {
            if (listener != null) listener.onError("Error: Invalid media object. Missing mediaId or fileName.");
            return;
        }
        try {
            Map<String, Object> data = new HashMap<>();
            data.put("mediaId", media.getId());
            data.put("fileName", media.getFileName());

            Request request = new Request(Command.GET_FILE_BY_MEDIA, data);
            out.println(gson.toJson(request));

            Response response = responseQueue.poll(30, TimeUnit.SECONDS);

            if (response == null) {
                if (listener != null) listener.onError("Server response timed out for file download request.");
                return;
            }

            if (response.isSuccess() && "READY_TO_SEND_FILE".equals(response.getMessage())) {
                Type type = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> responseData = gson.fromJson(response.getData(), type);
                String transferId = (String) responseData.get("transfer_id");
                long fileSize = ((Double) responseData.get("fileSize")).longValue();

                if (listener != null) listener.onStatusUpdate("Server is ready to send the file. Initiating download...");
                receiveFileBytes(transferId, media.getFileName(), fileSize, saveDirectory);

            } else {
                if (listener != null) listener.onError("Server failed to initiate file download: " + response.getMessage());
            }

        } catch (Exception e) {
            if (listener != null) listener.onError("Error during file download process: " + e.getMessage());
            else System.err.println("Error during file download process: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Receives file bytes from the file transfer server.
     * @param transferId The transfer ID to identify the file on the server.
     * @param fileName The name of the file to save.
     * @param fileSize The expected size of the file.
     * @param saveDirectory The directory where the file should be saved.
     */
    private void receiveFileBytes(String transferId, String fileName, long fileSize, String saveDirectory) {
        try {
            File saveDir = new File(saveDirectory);
            if (!saveDir.exists()) {
                saveDir.mkdirs();
            }
            File outputFile = new File(saveDir, fileName);

            try (Socket fileSocket = new Socket(SERVER_IP, FILE_TRANSFER_PORT);
                 InputStream is = fileSocket.getInputStream();
                 OutputStream os = fileSocket.getOutputStream(); // For sending transferId
                 FileOutputStream fos = new FileOutputStream(outputFile)) {

                if (listener != null) listener.onStatusUpdate("Connecting to file transfer server for download...");
                PrintWriter socketWriter = new PrintWriter(os, true);

                // Send the transfer ID to the file server to identify the file
                socketWriter.println(transferId);
                if (listener != null) listener.onStatusUpdate("Sent transferId: " + transferId + " to file server for download.");

                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalBytesReceived = 0;

                if (listener != null) listener.onStatusUpdate("Receiving file: " + fileName + " (" + fileSize + " bytes)");

                while (totalBytesReceived < fileSize && (bytesRead = is.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalBytesReceived))) != -1) {
                    fos.write(buffer, 0, bytesRead);
                    totalBytesReceived += bytesRead;
                    // Optional: Notify UI about progress
                    // if (listener != null) listener.onStatusUpdate("Received: " + totalBytesReceived + " / " + fileSize + " bytes");
                }
                fos.flush();

                if (totalBytesReceived == fileSize) {
                    if (listener != null) listener.onFileDownloaded(outputFile.getAbsolutePath());
                    if (listener != null) listener.onStatusUpdate("File '" + fileName + "' received successfully and saved to " + outputFile.getAbsolutePath());
                } else {
                    if (listener != null) listener.onError("File transfer incomplete. Expected: " + fileSize + ", Received: " + totalBytesReceived);
                    outputFile.delete(); // Clean up incomplete file
                }
            }
        } catch (IOException e) {
            if (listener != null) listener.onError("Error during file download: " + e.getMessage());
            else System.err.println("Error during file download: " + e.getMessage());
            e.printStackTrace();
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
            // Scanner is removed, so no need to close it here
            if (listener != null) listener.onStatusUpdate("Client connection closed.");
            else System.out.println("Client connection closed.");
        } catch (IOException e) {
            if (listener != null) listener.onError("Error closing client resources: " + e.getMessage());
            else System.err.println("Error closing client resources: " + e.getMessage());
        }
    }

    /**
     * Main method for demonstration purposes.
     * In a real JavaFX application, you would typically initialize and set the listener
     * within your Application's start method or a controller.
     */
    public static void main(String[] args) {
        ChatClient client = ChatClient.getInstance();

        // Example of setting a simple listener (can be your JavaFX controller)
        client.setListener(new ChatClientListener() {
            @Override
            public void onNewMessageReceived(Message message) {
                System.out.println("[Listener] New Message: " + message.getContent() + " from " + message.getSenderId());
            }

            @Override
            public void onCommandResponse(Response response) {
                System.out.println("[Listener] Command Response: Success=" + response.isSuccess() + ", Message=" + response.getMessage());
            }

            @Override
            public void onStatusUpdate(String status) {
                System.out.println("[Listener] Status: " + status);
            }

            @Override
            public void onError(String error) {
                System.err.println("[Listener] ERROR: " + error);
            }

            @Override
            public void onLoginSuccess(User user) {
                System.out.println("[Listener] Login Successful for: " + user.getPhoneNumber());
            }

            @Override
            public void onMessagesRetrieved(List<Message> messages, int chatId) {
                System.out.println("[Listener] Messages Retrieved for Chat ID " + chatId + ": " + messages.size() + " messages.");
                messages.forEach(msg -> System.out.println("  - " + msg.getContent()));
            }

            @Override
            public void onAllUsersRetrieved(List<User> users) {
                System.out.println("[Listener] All Users Retrieved: " + users.size() + " users.");
            }

            @Override
            public void onUserChatsRetrieved(List<Chat> chats) {
                System.out.println("[Listener] User Chats Retrieved: " + chats.size() + " chats.");
            }

            @Override
            public void onFileDownloaded(String filePath) {
                System.out.println("[Listener] File Downloaded to: " + filePath);
            }

            @Override
            public void onContactsRetrieved(List<User> contacts) {
                System.out.println("[Listener] Contacts Retrieved: " + contacts.size() + " contacts.");
            }

            @Override
            public void onNotificationsRetrieved(List<orgs.model.Notification> notifications) {
                System.out.println("[Listener] Notifications Retrieved: " + notifications.size() + " notifications.");
            }

            @Override
            public void onChatParticipantsRetrieved(List<orgs.model.ChatParticipant> participants, int chatId) {
                System.out.println("[Listener] Participants Retrieved for Chat ID " + chatId + ": " + participants.size() + " participants.");
            }
        });

        // Example usage (would be triggered by JavaFX UI actions)
        // client.login("your_phone_number", "your_password");
        // client.sendMessage(1, "Hello from JavaFX!", null, null, null);
        // client.getChatMessages(1, 10, 0);

        // Keep the main thread alive for the listener thread to run,
        // in a JavaFX app, the JavaFX Application thread would keep it alive.
        try {
            // In a real JavaFX app, the application thread manages lifecycle.
            // For a standalone test, you might want to keep it running for a bit.
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
