package orgs.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.opencv.core.Core;
import orgs.model.Chat;
import orgs.model.Media;
import orgs.model.Message;
import orgs.model.User;
import orgs.protocol.Command;
import orgs.protocol.Request;
import orgs.protocol.Response;
import orgs.utils.*;

import javax.swing.*;
import java.io.*;
import java.lang.reflect.Type;
import java.net.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static orgs.utils.StunClient.getPublicAddress;


public class ChatClient5 implements AutoCloseable {
    private static final String SERVER_IP = "192.168.1.99";
    //private static final String SERVER_IP ="3.83.141.156" ;
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

    private Scanner scanner;
    private User currentUser;

    private final BlockingQueue<Response> responseQueue = new LinkedBlockingQueue<>();

    // Separate UDP Sockets for Video and Audio
    private DatagramSocket udpVideoSocket; // UDP socket for video stream
    private DatagramSocket udpAudioSocket; // UDP socket for audio stream

    private int localVideoUdpPort;
    private int localAudioUdpPort;

    private InetAddress remoteVideoIp;
    private int remoteVideoUdpPort;
    private InetAddress remoteAudioIp; // NEW: Remote IP for audio
    private int remoteAudioUdpPort;    // NEW: Remote UDP port for audio

    private VideoCaptureThread videoCaptureThread;
    private VideoReceiverThread videoReceiverThread;
    private AudioCaptureThread audioCaptureThread;
    private AudioReceiverThread audioReceiverThread;

    // UI for video call display
    private JFrame videoFrame;
    private JLabel videoLabel;


    public ChatClient5() {
        this.scanner = new Scanner(System.in);
        try {
            socket = new Socket(SERVER_IP, SERVER_PORT);
            out = new PrintWriter(socket.getOutputStream(), true);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            System.out.println("Connected to chat server on main port.");

            new Thread(this::listenForServerMessages, "ServerListener").start();

        } catch (IOException e) {
            System.err.println("Error connecting to server: " + e.getMessage());
            System.exit(1);
        }
    }

    private void listenForServerMessages() {
        try {
            String serverResponseJson;
            while ((serverResponseJson = in.readLine()) != null) {

                Response response = gson.fromJson(serverResponseJson, Response.class);
                System.out.println("[DEBUG - Raw Server Response]: " + serverResponseJson);

                if ("READY_TO_RECEIVE_FILE".equals(response.getMessage())) {
                    System.out.println("Server is ready for file transfer. Initiating file send...");
                    Type type = new TypeToken<Map<String, String>>() {}.getType();
                    Map<String, String> data = gson.fromJson(response.getData(), type);

                    System.out.println(data.get("transfer_id"));
                    pendingFileTransferId = data.get("transfer_id");

                    if (pendingFileTransferId != null) {
                        sendFileBytes(currentFilePathToSend, pendingFileTransferId);
                    } else {
                        System.err.println("Error: Server responded READY_TO_RECEIVE_FILE but no transfer_id found in data.");
                    }
                    continue;
                }
                System.out.println(" ---------- new message ");

                String commandcall = response.getMessage();
                switch (commandcall) {
                    case "VIDEO_CALL_OFFER":
                        Map<String, Object> offerData = gson.fromJson(response.getData(), new TypeToken<Map<String, Object>>(){}.getType());
                        int callerId = ((Double) offerData.get("caller_id")).intValue();
                        String callerUsername = (String) offerData.get("caller_username");
                        // Retrieve separate video and audio IPs/ports
                        String callerPublicVideoIp = (String) offerData.get("caller_public_video_ip");
                        int callerUdpVideoPort = ((Double) offerData.get("caller_udp_video_port")).intValue();
                        String callerPublicAudioIp = (String) offerData.get("caller_public_audio_ip"); // NEW
                        int callerUdpAudioPort = ((Double) offerData.get("caller_udp_audio_port")).intValue(); // NEW

                        System.out.println("Incoming video call from " + callerUsername + " (Video: " + callerPublicVideoIp + ":" + callerUdpVideoPort + ", Audio: " + callerPublicAudioIp + ":" + callerUdpAudioPort + ")");

                        final int finalCallerId = callerId;
                        final String finalCallerPublicVideoIp = callerPublicVideoIp;
                        final int finalCallerUdpVideoPort = callerUdpVideoPort;
                        final String finalCallerPublicAudioIp = callerPublicAudioIp; // NEW
                        final int finalCallerUdpAudioPort = callerUdpAudioPort;    // NEW
                        final String finalCallerUsername = callerUsername;

                        // TODO : call oafer ui

                        SwingUtilities.invokeLater(() -> {
                            int choice = JOptionPane.showConfirmDialog(
                                    null,
                                    "Incoming video call from " + finalCallerUsername + ".\nDo you want to accept?",
                                    "Incoming Video Call",
                                    JOptionPane.YES_NO_OPTION,
                                    JOptionPane.QUESTION_MESSAGE
                            );
                            boolean acceptCall = (choice == JOptionPane.YES_OPTION);

                            try {
                                // Get own public video and audio addresses via STUN
                                InetSocketAddress publicVideoAddress = getPublicAddress(udpVideoSocket);
                                InetSocketAddress publicAudioAddress = getPublicAddress(udpAudioSocket);

                                String myPublicVideoIp = publicVideoAddress != null ? publicVideoAddress.getAddress().getHostAddress() : null;
                                int myPublicVideoPort = publicVideoAddress != null ? publicVideoAddress.getPort() : -1;
                                String myPublicAudioIp = publicAudioAddress != null ? publicAudioAddress.getAddress().getHostAddress() : null;
                                int myPublicAudioPort = publicAudioAddress != null ? publicAudioAddress.getPort() : -1;

                                if (myPublicVideoIp == null || myPublicVideoPort == -1 || myPublicAudioIp == null || myPublicAudioPort == -1) {
                                    System.err.println("Could not determine own public video/audio IP/port via STUN. Cannot answer call.");
                                    acceptCall = false; // Force reject if STUN fails
                                }

                                Map<String, Object> answerPayload = new HashMap<>();
                                answerPayload.put("caller_id", finalCallerId);
                                answerPayload.put("accepted", acceptCall);
                                if (acceptCall) {
                                    answerPayload.put("recipient_public_video_ip", myPublicVideoIp);
                                    answerPayload.put("recipient_udp_video_port", myPublicVideoPort);
                                    answerPayload.put("recipient_public_audio_ip", myPublicAudioIp); // NEW
                                    answerPayload.put("recipient_udp_audio_port", myPublicAudioPort); // NEW
                                }

                                Request request = new Request(Command.VIDEO_CALL_ANSWER, answerPayload);
                                out.println(gson.toJson(request));

                                if (acceptCall) {
                                    // Store remote video and audio IPs/ports
                                    remoteVideoIp = InetAddress.getByName(finalCallerPublicVideoIp);
                                    remoteVideoUdpPort = finalCallerUdpVideoPort;
                                    remoteAudioIp = InetAddress.getByName(finalCallerPublicAudioIp); // NEW
                                    remoteAudioUdpPort = finalCallerUdpAudioPort;    // NEW

                                    sendUdpPunchingPackets(); // Punch holes for both streams
                                    startMediaCallThreads();
                                    System.out.println("Accepted call from " + finalCallerUsername + ". Initiating media stream...");
                                } else {
                                    System.out.println("Rejected call from " + finalCallerUsername + ".");
                                }
                            } catch (IOException e) {
                                System.err.println("Error responding to video call offer: " + e.getMessage());
                                e.printStackTrace();
                            } catch (Exception e) {
                                System.err.println("STUN discovery error during call answer: " + e.getMessage());
                                e.printStackTrace();
                            }
                        });
                        break;

                    case "VIDEO_CALL_ACCEPTED":
                        Map<String, Object> acceptedData = gson.fromJson(response.getData(), new TypeToken<Map<String, Object>>(){}.getType());
                        // Retrieve separate video and audio IPs/ports
                        String calleePublicVideoIp = (String) acceptedData.get("callee_public_video_ip");
                        int calleeUdpVideoPort = ((Double) acceptedData.get("callee_udp_video_port")).intValue();
                        String calleePublicAudioIp = (String) acceptedData.get("callee_public_audio_ip"); // NEW
                        int calleeUdpAudioPort = ((Double) acceptedData.get("callee_udp_audio_port")).intValue(); // NEW

                        try {
                            remoteVideoIp = InetAddress.getByName(calleePublicVideoIp);
                            remoteAudioIp = InetAddress.getByName(calleePublicAudioIp); // NEW
                        } catch (UnknownHostException e) {
                            System.err.println("Invalid callee IP address: " + calleePublicVideoIp + " or " + calleePublicAudioIp + " - " + e.getMessage());
                            break;
                        }
                        remoteVideoUdpPort = calleeUdpVideoPort;
                        remoteAudioUdpPort = calleeUdpAudioPort; // NEW

                        sendUdpPunchingPackets(); // Punch holes for both streams
                        startMediaCallThreads();
                        System.out.println("Call accepted by " + (String)acceptedData.get("callee_username") + ". Starting media stream.");
                        break;

                    case "VIDEO_CALL_REJECTED":
                        Map<String, Object> rejectedData = gson.fromJson(response.getData(), new TypeToken<Map<String, Object>>(){}.getType());
                        System.out.println("Video call rejected by " + (String)rejectedData.get("callee_username"));
                        stopMediaCallThreads();
                        break;

                    case "VIDEO_CALL_ENDED":
                        Map<String, Object> endedData = gson.fromJson(response.getData(), new TypeToken<Map<String, Object>>(){}.getType());
                        System.out.println("Video call ended by " + (String)endedData.get("ender_id"));
                        stopMediaCallThreads();
                        break;

                    default:
                }

                if (response.isSuccess() && "New message received".equals(response.getMessage())) {
                    Message newMessage = gson.fromJson(response.getData(), Message.class);
                    String senderInfo = (newMessage.getSenderId() == currentUser.getId()) ? "You" : "User " + newMessage.getSenderId();
                    String contentToDisplay = newMessage.getContent() != null ? newMessage.getContent() : "[No text content]";
                    String mediaInfo = "";
                    if (newMessage.getMedia() != null) {
                        mediaInfo = String.format(" [Media Type: %s, File: %s]",
                                newMessage.getMedia().getMediaType(), newMessage.getMedia().getFileName());
                    }
                    System.out.println(String.format("\n[NEW MESSAGE from %s in Chat ID %d]: %s%s",
                            senderInfo, contentToDisplay, mediaInfo));
                    System.out.print("> ");
                } else {
                    responseQueue.put(response);
                }
            }
        } catch (SocketException e) {
            System.out.println("Server connection lost: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Error reading from server: " + e.getMessage());
        } catch (InterruptedException e) {
            System.err.println("Listener thread interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
        } finally {
            closeConnection();
        }
    }


    public void startClient() {
        System.out.println("Welcome to the Tuasil Messaging Client!");

        while (currentUser == null) {
            System.out.println("\n--- Auth Options ---");
            System.out.println("1. Login");
            System.out.println("2. Register");
            System.out.print("Choose an option: ");
            String authChoice = scanner.nextLine();

            Map<String, Object> authData = new HashMap<>();
            String phoneNumber, password, firstName, lastName;

            if ("1".equals(authChoice)) {
                System.out.print("Enter phone number: ");
                phoneNumber = scanner.nextLine();
                System.out.print("Enter password: ");
                password = scanner.nextLine();
                authData.put("phone_number", phoneNumber);
                authData.put("password", password);

                Request loginRequest = new Request(Command.LOGIN, authData);
                Response loginResponse = sendRequestAndAwaitResponse(loginRequest);

                if (loginResponse != null && loginResponse.isSuccess()) {
                    this.currentUser = gson.fromJson(loginResponse.getData(), User.class);
                    System.out.println("Logged in as: " + currentUser.getPhoneNumber() + " (" + currentUser.getFirstName() + " " + currentUser.getLastName() + ")");
                    try {
                        // Initialize separate UDP sockets for video and audio
                        udpVideoSocket = new DatagramSocket();
                        localVideoUdpPort = udpVideoSocket.getLocalPort();
                        System.out.println("Client UDP video socket opened on port: " + localVideoUdpPort);

                        udpAudioSocket = new DatagramSocket();
                        localAudioUdpPort = udpAudioSocket.getLocalPort();
                        System.out.println("Client UDP audio socket opened on port: " + localAudioUdpPort);

                    } catch (SocketException e) {
                        System.err.println("Error opening UDP sockets: " + e.getMessage());
                    }
                    break;
                } else if (loginResponse != null) {
                    System.out.println("Login failed: " + loginResponse.getMessage());
                }
            } else if ("2".equals(authChoice)) {
                System.out.print("Enter phone number: ");
                phoneNumber = scanner.nextLine();
                System.out.print("Enter password: ");
                password = scanner.nextLine();
                System.out.print("Enter first name: ");
                firstName = scanner.nextLine();
                System.out.print("Enter last name: ");
                lastName = scanner.nextLine();

                authData.put("phone_number", phoneNumber);
                authData.put("password", password);
                authData.put("first_name", firstName);
                authData.put("last_name", lastName);

                Request registerRequest = new Request(Command.REGISTER, authData);
                Response registerResponse = sendRequestAndAwaitResponse(registerRequest);

                if (registerResponse != null && registerResponse.isSuccess()) {
                    System.out.println("Registration successful! You can now log in.");
                } else if (registerResponse != null) {
                    System.out.println("Registration failed: " + registerResponse.getMessage());
                }
            } else {
                System.out.println("Invalid option.");
            }
        }

        while (currentUser != null) {
            displayCommands();
            System.out.print("Enter command number: ");
            String commandInput = scanner.nextLine();
            handleUserInput(commandInput);
        }
    }

    private void displayCommands() {
        System.out.println("\n--- Commands ---");
        System.out.println("1. Send Message (Text/Media)");
        System.out.println("2. Get Chat Messages");
        System.out.println("3. Create Chat");
        System.out.println("4. Manage Profile (View/Update/Delete)");
        System.out.println("5. Get All Users");
        System.out.println("6. My Chats");
        System.out.println("7. Manage Chat Participants");
        System.out.println("8. My Contacts (View/Manage)");
        System.out.println("9. Block/Unblock User");
        System.out.println("10. My Notifications");
        System.out.println("11. Update/Delete Message");
        System.out.println("12. Delete Chat");
        System.out.println("13. Logout");
        System.out.println("14. Get Media File");
        System.out.println("15. get messages after id ");
        System.out.println("16. Get User by id");
        System.out.println("17. Get User by phone number");
        System.out.println("18. Get chat by id");
        System.out.println("19. Initiate Video Call");
        System.out.println("20. End Video Call");
    }

    private void handleUserInput(String commandInput) {
        Request request = null;
        Map<String, Object> data = new HashMap<>();

        try {
            switch (commandInput) {
                case "1": // Send Message (Unified)
                    System.out.print("Enter Chat ID: ");
                    int messageChatId = Integer.parseInt(scanner.nextLine());
                    System.out.print("Is this a media message? (yes/no): ");
                    String isMedia = scanner.nextLine().toLowerCase();

                    data.put("chat_id", messageChatId);

                    if ("yes".equals(isMedia)) {
                        System.out.print("Enter local file path (e.g., C:/images/photo.jpg or /home/user/video.mp4): ");
                        String filePath = scanner.nextLine();

                        filePath = filePath.trim();
                        filePath = filePath.replace('\\', '/').replace("\u202A", "").replace("\u202B", "");

                        System.out.println("Processed Path: '" + filePath + "'");
                        System.out.println("Processed Path Length: " + filePath.length());


                        File file = new File(filePath);


                        if (!file.exists()) {
                            System.out.println("Error: File does not exist at " + filePath);
                            System.out.println("Current working directory: " + System.getProperty("user.dir"));
                            return;
                        }

                        if (!file.isFile()) {
                            System.out.println("Error: Path is not a regular file (it might be a directory or special file) at " + filePath);
                            return;
                        }


                        if (!file.exists() || !file.isFile()) {
                            System.out.println("Error: File not found or is not a regular file at " + filePath);
                            return;
                        }
                        currentFilePathToSend = filePath;
                        long fileSize = file.length();
                        String fileName = file.getName();

                        System.out.print("Enter caption (optional, press Enter to skip): ");
                        String caption = scanner.nextLine();

                        String mediaType;
                        if (fileName.matches(".*\\.(jpg|jpeg|png|gif)$")) {
                            mediaType = "image";
                        } else if (fileName.matches(".*\\.(mp4|avi|mov|wmv)$")) {
                            mediaType = "video";
                        } else if (fileName.matches(".*\\.(mp3|wav|ogg)$")) {
                            mediaType = "voiceNote";
                        } else {
                            System.out.print("Enter media type (image, video, voiceNote, file): ");
                            mediaType = scanner.nextLine();
                        }

                        Media media = new Media();
                        media.setFileName(fileName);
                        media.setFileSize(fileSize);
                        media.setMediaType(mediaType);
                        media.setUploadedByUserId(currentUser.getId());
                        media.setUploadedAt(LocalDateTime.now());

                        data.put("content", caption.isEmpty() ? null : caption);
                        data.put("media", media);
                        request = new Request(Command.SEND_MESSAGE, data);

                    } else {
                        System.out.print("Enter message content: ");
                        String textContent = scanner.nextLine();
                        if (textContent.trim().isEmpty()) {
                            System.out.println("Text message content cannot be empty.");
                            return;
                        }
                        System.out.println("hello "+ textContent);
                        data.put("content", textContent);
                        request = new Request(Command.SEND_MESSAGE, data);
                    }
                    break;

                case "2": // Get Chat Messages
                    System.out.print("Enter Chat ID: ");
                    int getChatId = Integer.parseInt(scanner.nextLine());
                    System.out.print("Enter limit (number of messages to fetch): ");
                    int limit = Integer.parseInt(scanner.nextLine());
                    System.out.print("Enter offset (starting point): ");
                    int offset = Integer.parseInt(scanner.nextLine());
                    data.put("chat_id", getChatId);
                    data.put("limit", limit);
                    data.put("offset", offset);
                    request = new Request(Command.GET_CHAT_MESSAGES, data);

                    Response messagesResponse = sendRequestAndAwaitResponse(request);
                    if (messagesResponse != null && messagesResponse.isSuccess() && "Messages retrieved.".equals(messagesResponse.getMessage())) {
                        Type messageListType = new TypeToken<List<Message>>() {}.getType();
                        List<Message> messages = gson.fromJson(messagesResponse.getData(), messageListType);
                        System.out.println("\n--- Messages in Chat ID: " + getChatId + " ---");
                        if (messages == null || messages.isEmpty()) {
                            System.out.println("No messages found in this chat.");
                        } else {
                            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                            for (Message msg : messages) {
                                String senderInfo = (msg.getSenderId() == currentUser.getId()) ? "You" : "User " + msg.getSenderId();
                                String contentToDisplay = msg.getContent() != null ? msg.getContent() : "[No text content]";
                                String mediaInfo = "";
                                if (msg.getMedia() != null) {
                                    mediaInfo = String.format(" [Media Type: %s, File: %s, ID: %s]",
                                            msg.getMedia().getMediaType(), msg.getMedia().getFileName(), msg.getMedia().getTransferId());
                                }
                                System.out.printf("[%s] %s: %s%s (Views: %d)\n",
                                        msg.getSentAt().format(formatter), senderInfo, contentToDisplay, mediaInfo, msg.getViewCount());
                            }
                        }
                    } else if (messagesResponse != null) {
                        System.out.println("Failed to get messages: " + messagesResponse.getMessage());
                    }
                    return;

                case "3": // Create Chat
                    System.out.print("Enter chat type (private, group, channel): ");
                    String chatType = scanner.nextLine();
                    System.out.print("Enter chat name (optional for private, required for group/channel): ");
                    String chatName = scanner.nextLine();
                    System.out.print("Enter chat description (optional): ");
                    String chatDescription = scanner.nextLine();
                    System.out.print("Enter public link (optional, for public channels only): ");
                    String publicLink = scanner.nextLine();

                    data.put("chat_type", chatType);
                    data.put("chat_name", chatName.isEmpty() ? null : chatName);
                    data.put("chat_description", chatDescription.isEmpty() ? null : chatDescription);
                    data.put("public_link", publicLink.isEmpty() ? null : publicLink);
                    request = new Request(Command.CREATE_CHAT, data);
                    break;

                case "4": // Manage Profile
                    manageProfile(scanner);
                    return;
                case "5": // Get All Users
                    request = new Request(Command.GET_ALL_USERS);
                    Response allUsersResponse = sendRequestAndAwaitResponse(request);
                    if (allUsersResponse != null && allUsersResponse.isSuccess() && "All users retrieved.".equals(allUsersResponse.getMessage())) {
                        Type userListType = new TypeToken<List<User>>() {}.getType();
                        List<User> users = gson.fromJson(allUsersResponse.getData(), userListType);
                        System.out.println("\n--- All Registered Users ---");
                        if (users == null || users.isEmpty()) {
                            System.out.println("No users found.");
                        } else {
                            for (User user : users) {
                                System.out.printf("ID: %d, Name: %s %s, Phone: %s, Online: %s\n",
                                        user.getId(), user.getFirstName(), user.getLastName(), user.getPhoneNumber(), user.isOnline());
                            }
                        }
                    } else if (allUsersResponse != null) {
                        System.out.println("Failed to get all users: " + allUsersResponse.getMessage());
                    }
                    return;

                case "6": // My Chats
                    getUserChats();
                    return;
                case "7": // Manage Chat Participants
                    manageChatParticipants(scanner);
                    return;
                case "8": // My Contacts
                    manageContacts(scanner);
                    return;
                case "9": // Block/Unblock User
                    System.out.print("Enter User ID to block/unblock: ");
                    int targetUserId = Integer.parseInt(scanner.nextLine());
                    System.out.print("Action (block/unblock): ");
                    String action = scanner.nextLine();
                    blockUnblockUser(targetUserId, action);
                    return;
                case "10": // My Notifications
                    getNotifications();
                    return;
                case "11": // Update/Delete Message
                    updateDeleteMessage(scanner);
                    return;
                case "12": // Delete Chat
                    System.out.print("Enter Chat ID to delete: ");
                    int deleteChatId = Integer.parseInt(scanner.nextLine());
                    deleteChat(deleteChatId);
                    return;
                case "13": // Logout
                    request = new Request(Command.LOGOUT);
                    Response logoutResponse = sendRequestAndAwaitResponse(request);
                    if (logoutResponse != null && logoutResponse.isSuccess()) {
                        System.out.println(logoutResponse.getMessage());
                        currentUser = null;
                    } else if (logoutResponse != null) {
                        System.out.println("Logout failed: " + logoutResponse.getMessage());
                    }
                    return;

                case "14": // New command to get a media file
                    System.out.print("Enter Media ID (as shown in messages): ");
                    String mediaId = scanner.nextLine();
                    System.out.print("Enter File Name: ");
                    String fileName = scanner.nextLine();
                    System.out.print("Enter save directory path (e.g., C:/downloads): ");
                    String saveDir = scanner.nextLine();

                    Media mediaToDownload = new Media();
                    mediaToDownload.setId(Integer.parseInt(mediaId));
                    mediaToDownload.setFileName(fileName);

                    getFileByMedia(mediaToDownload, saveDir);
                    return;

                case "15":
                    System.out.print("Enter Chat ID: ");
                    int getCerntChatId = Integer.parseInt(scanner.nextLine());
                    System.out.print("Enter lastMessage (last read): ");
                    int lastMessageId = Integer.parseInt(scanner.nextLine());
                    data.put("chat_id", getCerntChatId);
                    data.put("lastMessageId", lastMessageId);
                    request = new Request(Command.GET_CHAT_UNREADMESSAGES, data);

                    Response UnReadmessagesResponse = sendRequestAndAwaitResponse(request);
                    if (UnReadmessagesResponse != null && UnReadmessagesResponse.isSuccess() && "Messages retrieved.".equals(UnReadmessagesResponse.getMessage())) {
                        Type messageListType = new TypeToken<List<Message>>() {}.getType();
                        List<Message> messages = gson.fromJson(UnReadmessagesResponse.getData(), messageListType);
                        System.out.println("\n--- Messages in Chat ID: " + getCerntChatId + " ---");
                        if (messages == null || messages.isEmpty()) {
                            System.out.println("No messages found in this chat.");
                        } else {
                            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                            for (Message msg : messages) {
                                String senderInfo = (msg.getSenderId() == currentUser.getId()) ? "You" : "User " + msg.getSenderId();
                                String contentToDisplay = msg.getContent() != null ? msg.getContent() : "[No text content]";
                                String mediaInfo = "";
                                if (msg.getMedia() != null) {
                                    mediaInfo = String.format(" [Media Type: %s, File: %s, ID: %s]",
                                            msg.getMedia().getMediaType(), msg.getMedia().getFileName(), msg.getMedia().getTransferId());
                                }
                                System.out.printf("[%s] %s: %s%s (Views: %d)\n",
                                        msg.getSentAt().format(formatter), senderInfo, contentToDisplay, mediaInfo, msg.getViewCount());
                            }
                        }
                    } else if (UnReadmessagesResponse != null) {
                        System.out.println("Failed to get messages: " + UnReadmessagesResponse.getMessage());
                    }
                    return;
                case "16":
                    System.out.println("Enter user id ");
                    int getUserId = Integer.parseInt(scanner.nextLine());
                    getUserById(getUserId);
                    return;
                case "17":
                    System.out.println("Enter user Phone Number ");
                    String getUserPhoneNumber = scanner.nextLine();
                    getUserByPhoneNumber(getUserPhoneNumber);
                    return;
                case "18":
                    System.out.println("Enter chat id ");
                    int  getAChatId = Integer.parseInt(scanner.nextLine());
                    getChatById(getAChatId);
                    return;
                case "19": // Initiate Video Call (now includes audio)
                    System.out.print("Enter the client ID to call: ");
                    String targetID = scanner.nextLine();
                    initiateVideoCall(targetID);
                    return;
                case "20": // End Video Call
                    if (remoteVideoIp != null || remoteAudioIp != null) { // Check if a call is active
                        System.out.print("Confirm ending call? (yes/no): ");
                        if (scanner.nextLine().equalsIgnoreCase("yes")) {
                            // You need the remote user ID here to send to the server.
                            // This would ideally be stored when the call is accepted/initiated.
                            // For now, let's prompt the user for it.
                            System.out.print("Enter the ID of the user you are currently calling/called by: ");
                            int remoteUserIdForEndCall = Integer.parseInt(scanner.nextLine());
                            endCurrentVideoCall(remoteUserIdForEndCall);
                        }
                    } else {
                        System.out.println("No active video call to end.");
                    }
                    return;
                default:
                    System.out.println("Invalid command number.");
                    return;
            }

            if (request != null) {
                Response response = sendRequestAndAwaitResponse(request);
                if (response != null) {
                    System.out.println("Server Response: " + response.getMessage());
                    if (response.isSuccess() && "Message sent successfully!".equals(response.getMessage()) && currentFilePathToSend != null) {
                        System.out.println("File transfer details cleared.");
                        currentFilePathToSend = null;
                        pendingFileTransferId = null;
                    }
                }
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid number format. Please enter a valid number for IDs/limits.");
        } catch (Exception e) {
            System.err.println("Error handling user input: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void manageProfile(Scanner scanner) {
        System.out.println("Manage Profile functionality not yet fully implemented in this example.");
    }

    private void getUserChats() {
        Request request = new Request(Command.GET_USER_CHATS);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess() && "User chats retrieved.".equals(response.getMessage())) {
            Type chatListType = new TypeToken<List<Chat>>() {}.getType();
            List<Chat> chats = gson.fromJson(response.getData(), chatListType);
            System.out.println("\n--- Your Chats ---");
            if (chats == null || chats.isEmpty()) {
                System.out.println("You are not a participant in any chats.");
            } else {
                for (Chat chat : chats) {
                    System.out.printf("ID: %d, Name: %s (Type: %s), Created by User %d at %s\n",
                            chat.getId(),
                            (chat.getChatName() != null ? chat.getChatName() : "Private Chat"),
                            chat.getChatType(),
                            chat.getCreatorId(),
                            chat.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
                }
            }
        } else if (response != null) {
            System.out.println("Failed to get your chats: " + response.getMessage());
        }
    }

    private void manageChatParticipants(Scanner scanner) {
        System.out.println("\n--- Chat Participant Management ---");
        System.out.println("1. Add Participant to Chat");
        System.out.println("2. Get Chat Participants");
        System.out.println("3. Update Participant Role");
        System.out.println("4. Remove Participant from Chat");
        System.out.println("5. Back to main menu");
        System.out.print("Choose an option: ");
        String choice = scanner.nextLine();

        switch (choice) {
            case "1":
                System.out.print("Enter Chat ID: ");
                int addPartChatId = Integer.parseInt(scanner.nextLine());
                System.out.print("Enter User ID to add: ");
                int userIdToAdd = Integer.parseInt(scanner.nextLine());
                System.out.print("Enter Role (e.g., member, admin, creator): ");
                String role = scanner.nextLine();
                addChatParticipant(addPartChatId, userIdToAdd, role);
                break;
            case "2":
                System.out.print("Enter Chat ID to list participants: ");
                int getPartChatId = Integer.parseInt(scanner.nextLine());
                getChatParticipants(getPartChatId);
                break;
            case "3":
                System.out.print("Enter Chat ID where participant exists: ");
                int updatePartChatId = Integer.parseInt(scanner.nextLine());
                System.out.print("Enter User ID of participant to update role: ");
                int userIdToUpdateRole = Integer.parseInt(scanner.nextLine());
                System.out.print("Enter new Role (e.g., member, admin, creator): ");
                String newRole = scanner.nextLine();
                updateChatParticipantRole(updatePartChatId, userIdToUpdateRole, newRole);
                break;
            case "4":
                System.out.print("Enter Chat ID to remove from: ");
                int removePartChatId = Integer.parseInt(scanner.nextLine());
                System.out.print("Enter User ID of the participant to remove: ");
                int userIdToRemove = Integer.parseInt(scanner.nextLine());
                removeChatParticipant(removePartChatId, userIdToRemove);
                break;
            case "5":
                break;
            default:
                System.out.println("Invalid option.");
        }
    }

    private void addChatParticipant(int chatId, int userId, String role) {
        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);
        data.put("user_id", userId);
        data.put("role", role);

        Request request = new Request(Command.ADD_CHAT_PARTICIPANT, data);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess()) {
            System.out.println("Participant added successfully!");
        } else if (response != null) {
            System.out.println("Failed to add participant: " + response.getMessage());
        }
    }

    private void getChatParticipants(int chatId) {
        Map<String, Object> params = new HashMap<>();
        params.put("chat_id", chatId);
        Request request = new Request(Command.GET_CHAT_PARTICIPANTS, params);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess()) {
            Type participantListType = new TypeToken<List<orgs.model.ChatParticipant>>() {}.getType();
            List<orgs.model.ChatParticipant> participants = gson.fromJson(response.getData(), participantListType);
            System.out.println("\n--- Participants in Chat ID: " + chatId + " ---");
            if (participants == null || participants.isEmpty()) {
                System.out.println("No participants found in this chat or you don't have permission to view them.");
            } else {
                for (orgs.model.ChatParticipant p : participants) {
                    System.out.println("User ID: " + p.getUserId() + ", Role: " + p.getRole() + ", Joined: " + p.getJoinedAt().format(DateTimeFormatter.ofPattern("MMM dd, HH:mm")));
                }
            }
        } else if (response != null) {
            System.out.println("Failed to get chat participants: " + response.getMessage());
        }
    }

    private void updateChatParticipantRole(int chatId, int userId, String newRole) {
        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);
        data.put("user_id", userId);
        data.put("new_role", newRole);

        Request request = new Request(Command.UPDATE_CHAT_PARTICIPANT_ROLE, data);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess()) {
            System.out.println("Participant role updated successfully!");
        } else if (response != null) {
            System.out.println("Failed to update participant role: " + response.getMessage());
        }
    }

    private void removeChatParticipant(int chatId, int userId) {
        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", chatId);
        data.put("user_id", userId);

        Request request = new Request(Command.REMOVE_CHAT_PARTICIPANT, data);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess()) {
            System.out.println("Participant removed successfully!");
        } else if (response != null) {
            System.out.println("Failed to remove participant: " + response.getMessage());
        }
    }

    private void manageContacts(Scanner scanner) {
        System.out.println("\n--- Contact Management ---");
        System.out.println("1. Add Contact");
        System.out.println("2. Get My Contacts");
        System.out.println("3. Remove Contact");
        System.out.println("4. Back to main menu");
        System.out.print("Choose an option: ");
        String choice = scanner.nextLine();

        switch (choice) {
            case "1":
                System.out.print("Enter User ID to add as contact: ");
                int contactIdToAdd = Integer.parseInt(scanner.nextLine());
                addContact(contactIdToAdd);
                break;
            case "2":
                getContacts();
                break;
            case "3":
                System.out.print("Enter User ID to remove from contacts: ");
                int contactIdToRemove = Integer.parseInt(scanner.nextLine());
                removeContact(contactIdToRemove);
                break;
            case "4":
                break;
            default:
                System.out.println("Invalid option.");
        }
    }

    private void addContact(int contactUserId) {
        Map<String, Object> data = new HashMap<>();
        data.put("contact_user_id", contactUserId);
        Request request = new Request(Command.ADD_CONTACT, data);
        Response response = sendRequestAndAwaitResponse(request);
        if (response != null && response.isSuccess()) {
            System.out.println("Contact added successfully!");
        } else if (response != null) {
            System.out.println("Failed to add contact: " + response.getMessage());
        }
    }

    private void getContacts() {
        Request request = new Request(Command.GET_CONTACTS);
        Response response = sendRequestAndAwaitResponse(request);
        if (response != null && response.isSuccess() && "User contacts retrieved.".equals(response.getMessage())) {
            Type contactListType = new TypeToken<List<User>>() {}.getType();
            List<User> contacts = gson.fromJson(response.getData(), contactListType);
            System.out.println("\n--- Your Contacts ---");
            if (contacts == null || contacts.isEmpty()) {
                System.out.println("You have no contacts.");
            } else {
                for (User contact : contacts) {
                    System.out.printf("ID: %d, Name: %s %s, Phone: %s, Online: %s\n",
                            contact.getId(), contact.getFirstName(), contact.getLastName(), contact.getPhoneNumber(), contact.isOnline());
                }
            }
        } else if (response != null) {
            System.out.println("Failed to get contacts: " + response.getMessage());
        }
    }

    private void removeContact(int contactUserId) {
        Map<String, Object> data = new HashMap<>();
        data.put("contact_user_id", contactUserId);
        Request request = new Request(Command.REMOVE_CONTACT, data);
        Response response = sendRequestAndAwaitResponse(request);
        if (response != null && response.isSuccess()) {
            System.out.println("Contact removed successfully!");
        } else if (response != null) {
            System.out.println("Failed to remove contact: " + response.getMessage());
        }
    }

    private void blockUnblockUser(int targetUserId, String action) {
        Map<String, Object> data = new HashMap<>();
        data.put("target_user_id", targetUserId);
        data.put("action", action); // "block" or "unblock"

        Request request = new Request(Command.BLOCK_UNBLOCK_USER, data);
        Response response = sendRequestAndAwaitResponse(request);
        if (response != null && response.isSuccess()) {
            System.out.println(response.getMessage());
        } else if (response != null) {
            System.out.println("Action failed: " + response.getMessage());
        }
    }

    private void getNotifications() {
        Request request = new Request(Command.MY_NOTIFICATIONS);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess() && "User notifications retrieved.".equals(response.getMessage())) {
            Type notificationListType = new TypeToken<List<orgs.model.Notification>>() {}.getType();
            List<orgs.model.Notification> notifications = gson.fromJson(response.getData(), notificationListType);
            System.out.println("\n--- Your Notifications ---");
            if (notifications == null || notifications.isEmpty()) {
                System.out.println("You have no notifications.");
            } else {
                for (orgs.model.Notification notif : notifications) {
                    String status = notif.isRead() ? "(READ)" : "(UNREAD)";
                    System.out.printf("ID: %d %s, Type: %s, Content: %s, Created: %s\n",
                            notif.getId(), status, notif.getEventType(), notif.getMessage(),
                            notif.getTimestamp().format(DateTimeFormatter.ofPattern("MMM dd, HH:mm")));
                }

                System.out.println("\nNotification Options:");
                System.out.println("1. Mark Notification as Read");
                System.out.println("2. Delete Notification");
                System.out.println("3. Back");
                System.out.print("Choose an option: ");
                String choice = scanner.nextLine();
                switch (choice) {
                    case "1":
                        System.out.print("Enter Notification ID to mark as read: ");
                        int notifIdToMarkRead = Integer.parseInt(scanner.nextLine());
                        markNotificationAsRead(notifIdToMarkRead);
                        break;
                    case "2":
                        System.out.print("Enter Notification ID to delete: ");
                        int notifIdToDelete = Integer.parseInt(scanner.nextLine());
                        deleteNotification(notifIdToDelete);
                        break;
                    case "3":
                        break;
                    default:
                        System.out.println("Invalid option.");
                }
            }
        } else if (response != null) {
            System.out.println("Failed to get notifications: " + response.getMessage());
        }
    }

    private void markNotificationAsRead(int notificationId) {
        Map<String, Object> data = new HashMap<>();
        data.put("notification_id", notificationId);
        Request request = new Request(Command.MARK_NOTIFICATION_AS_READ, data);
        Response response = sendRequestAndAwaitResponse(request);
        if (response != null && response.isSuccess()) {
            System.out.println("Notification marked as read!");
        } else if (response != null) {
            System.out.println("Failed to mark notification as read: " + response.getMessage());
        }
    }

    private void deleteNotification(int notificationId) {
        Map<String, Object> data = new HashMap<>();
        data.put("notification_id", notificationId);
        Request request = new Request(Command.DELETE_NOTIFICATION, data);
        Response response = sendRequestAndAwaitResponse(request);
        if (response != null && response.isSuccess()) {
            System.out.println("Notification deleted!");
        } else if (response != null) {
            System.out.println("Failed to delete notification: " + response.getMessage());
        }
    }

    private void updateDeleteMessage(Scanner scanner) {
        System.out.println("\n--- Message Management ---");
        System.out.println("1. Update Message Content");
        System.out.println("2. Delete Message");
        System.out.println("3. Mark Message as Read");
        System.out.println("4. Back to main menu");
        System.out.print("Choose an option: ");
        String choice = scanner.nextLine();

        switch (choice) {
            case "1":
                System.out.print("Enter Message ID to update: ");
                int updateMsgId = Integer.parseInt(scanner.nextLine());
                System.out.print("Enter new content: ");
                String newContent = scanner.nextLine();
                updateMessage(updateMsgId, newContent);
                break;
            case "2":
                System.out.print("Enter Message ID to delete: ");
                int deleteMsgId = Integer.parseInt(scanner.nextLine());
                deleteMessage(deleteMsgId);
                break;
            case "3":
                System.out.print("Enter Message ID to mark as read: ");
                int readMsgId = Integer.parseInt(scanner.nextLine());
                markMessageAsRead(readMsgId);
                break;
            case "4":
                break;
            default:
                System.out.println("Invalid option.");
        }
    }

    private void updateMessage(int messageId, String newContent) {
        Map<String, Object> data = new HashMap<>();
        data.put("message_id", messageId);
        data.put("new_content", newContent);
        Request request = new Request(Command.UPDATE_MESSAGE, data);
        Response response = sendRequestAndAwaitResponse(request);
        if (response != null && response.isSuccess()) {
            System.out.println("Message updated successfully!");
        } else if (response != null) {
            System.out.println("Failed to update message: " + response.getMessage());
        }
    }

    private void deleteMessage(int messageId) {
        Map<String, Object> data = new HashMap<>();
        data.put("message_id", messageId);
        Request request = new Request(Command.DELETE_MESSAGE, data);
        Response response = sendRequestAndAwaitResponse(request);
        if (response != null && response.isSuccess()) {
            System.out.println("Message deleted successfully!");
        } else if (response != null) {
            System.out.println("Failed to delete message: " + response.getMessage());
        }
    }

    private void markMessageAsRead(int messageId) {
        Map<String, Object> data = new HashMap<>();
        data.put("message_id", messageId);
        data.put("user_id", currentUser.getId()); // Server will need to know who marked it read
        Request request = new Request(Command.MARK_MESSAGE_AS_READ, data);
        Response response = sendRequestAndAwaitResponse(request);
        if (response != null && response.isSuccess()) {
            System.out.println("Message marked as read!");
        } else if (response != null) {
            System.out.println("Failed to mark message as read: " + response.getMessage());
        }
    }

    private void deleteChat(int chatId) {
        Map<String, Object> params = new HashMap<>();
        params.put("chat_id", chatId);
        Request request = new Request(Command.DELETE_CHAT, params);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess()) {
            System.out.println("Chat " + chatId + " deleted successfully!");
        } else if (response != null) {
            System.out.println("Failed to delete chat: " + response.getMessage());
        }
    }


    private Response sendRequestAndAwaitResponse(Request request) {
        try {
            responseQueue.clear(); // Clear any stale responses

            out.println(gson.toJson(request));

            Response response = responseQueue.poll(30, TimeUnit.SECONDS); // 30-second timeout

            if (response == null) {
                System.err.println("No response from server within timeout for command: " + request.getCommand());
                return new Response(false, "Server response timed out.", null);
            }
            return response;
        } catch (InterruptedException e) {
            System.err.println("Waiting for response interrupted: " + e.getMessage());
            Thread.currentThread().interrupt();
            return new Response(false, "Client interrupted.", null);
        }
    }

    private void sendFileBytes(String filePath, String transferId) {
        if (filePath == null || filePath.isEmpty()) {
            System.err.println("No file path provided for transfer.");
            return;
        }
        if (transferId == null || transferId.isEmpty()) {
            System.err.println("No transfer ID provided by server for file transfer.");
            return;
        }

        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            System.err.println("File not found or is not a regular file: " + filePath);
            return;
        }

        try (Socket fileSocket = new Socket(SERVER_IP, FILE_TRANSFER_PORT);
             OutputStream os = fileSocket.getOutputStream();
             BufferedReader serverResponseReader = new BufferedReader(new InputStreamReader(fileSocket.getInputStream()));
             FileInputStream fis = new FileInputStream(file)) {

            System.out.println("Connecting to file transfer server on port " + FILE_TRANSFER_PORT + "...");

            PrintWriter socketWriter = new PrintWriter(os, true);
            socketWriter.println(transferId);
            System.out.println("Sent transferId: " + transferId + " to file server.");

            byte[] buffer = new byte[4096];
            int bytesRead;
            long totalBytesSent = 0;
            long fileSize = file.length();

            System.out.println("Sending file: " + file.getName() + " (" + fileSize + " bytes)");

            while ((bytesRead = fis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
                totalBytesSent += bytesRead;
            }
            os.flush();

            System.out.println("\nFile '" + file.getName() + "' sent successfully!");

            String fileTransferStatus = serverResponseReader.readLine();
            if (fileTransferStatus != null) {
                System.out.println("File server response: " + fileTransferStatus);
            }

        } catch (IOException e) {
            System.err.println("Error during file transfer: " + e.getMessage());
            e.printStackTrace();
        }
    }


    public void getFileByMedia(Media media, String saveDirectory) {
        if (media == null ) {
            System.err.println("Error: Invalid media object. Missing mediaId or fileName.");
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
                System.err.println("Server response timed out for file download request.");
                return;
            }

            if (response.isSuccess() && "READY_TO_SEND_FILE".equals(response.getMessage())) {
                Type type = new TypeToken<Map<String, Object>>() {}.getType();
                Map<String, Object> responseData = gson.fromJson(response.getData(), type);
                String transferId = (String) responseData.get("transfer_id");
                long fileSize = ((Double) responseData.get("fileSize")).longValue();

                System.out.println("Server is ready to send the file. Initiating download...");
                receiveFileBytes(transferId, media.getFileName(), fileSize, saveDirectory);

            } else {
                System.err.println("Server failed to initiate file download: " + response.getMessage());
            }

        } catch (Exception e) {
            System.err.println("Error during file download process: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void receiveFileBytes(String transferId, String fileName, long fileSize, String saveDirectory) {
        try {
            File saveDir = new File(saveDirectory);
            if (!saveDir.exists()) {
                saveDir.mkdirs();
            }
            File outputFile = new File(saveDir, fileName);

            try (Socket fileSocket = new Socket(SERVER_IP, FILE_TRANSFER_PORT);
                 InputStream is = fileSocket.getInputStream();
                 OutputStream os = fileSocket.getOutputStream();
                 FileOutputStream fos = new FileOutputStream(outputFile)) {

                System.out.println("Connecting to file transfer server for download...");
                PrintWriter socketWriter = new PrintWriter(os, true);

                socketWriter.println(transferId);
                System.out.println("Sent transferId: " + transferId + " to file server for download.");

                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalBytesReceived = 0;

                System.out.println("Receiving file: " + fileName + " (" + fileSize + " bytes)");

                while (totalBytesReceived < fileSize && (bytesRead = is.read(buffer, 0, (int) Math.min(buffer.length, fileSize - totalBytesReceived))) != -1) {
                    System.out.print("\rReceived: " + totalBytesReceived + " / " + fileSize + " bytes");
                    fos.write(buffer, 0, bytesRead);
                    totalBytesReceived += bytesRead;
                }
                System.out.println("the loop is finsh +++++");
                fos.flush();

                if (totalBytesReceived == fileSize) {
                    System.out.println("\nFile '" + fileName + "' received successfully and saved to " + outputFile.getAbsolutePath());
                } else {
                    System.err.println("\nFile transfer incomplete. Expected: " + fileSize + ", Received: " + totalBytesReceived);
                    outputFile.delete();
                }
            }
        } catch (IOException e) {
            System.err.println("Error during file download: " + e.getMessage());
            e.printStackTrace();
        }
    }




    private void getUserByPhoneNumber(String getUserPhoneNumber) {
        Map<String, Object> data = new HashMap<>();
        data.put("chat_phone_number", getUserPhoneNumber);
        Request request = new Request(Command.GET_USER_BY_PHONENUMBER,data);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess() && "Chats retrieved by phone number.".equals(response.getMessage())) {
            Type userType = new TypeToken<User>() {
            }.getType();
            User user = gson.fromJson(response.getData(), userType);
            System.out.println("\n--- The user ---");
            System.out.println(user.getFirstName());
        }
    }
    private void getUserById(int getUserId) {
        Map<String, Object> data = new HashMap<>();
        data.put("user_id", getUserId);
        Request request = new Request(Command.GET_USER_BY_ID,data);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess() && "User retrieved by id.".equals(response.getMessage())) {
            Type userType = new TypeToken<User>() {
            }.getType();
            User user = gson.fromJson(response.getData(), userType);
            System.out.println("\n--- The user ---");
            System.out.println(user.getFirstName());
        }
    }



    private void getChatById(int getChatId) {
        Map<String, Object> data = new HashMap<>();
        data.put("chat_id", getChatId);
        Request request = new Request(Command.GET_CHAT_BY_ID,data);
        Response response = sendRequestAndAwaitResponse(request);

        if (response != null && response.isSuccess() && "Chats retrieved by id.".equals(response.getMessage())) {
            Type chatType = new TypeToken<Chat>() {
            }.getType();
            Chat chat = gson.fromJson(response.getData(), chatType);
            System.out.println("\n--- The Chat ---");
            System.out.println(chat.getChatName());
        }
    }
    /**
     * Initiates a video call to a target user.
     * This method now discovers the client's public IP address and separate ports
     * for video and audio using STUN, then sends them to the server.
     * @param targetUserId The ID of the user to call.
     */
    public void initiateVideoCall(String targetUserId) throws Exception {
        // Ensure UDP sockets are initialized before STUN
        if (udpVideoSocket == null || udpVideoSocket.isClosed()) {
            try {
                udpVideoSocket = new DatagramSocket();
                localVideoUdpPort = udpVideoSocket.getLocalPort();
                System.out.println("Client UDP video socket opened on port: " + localVideoUdpPort);
            } catch (SocketException e) {
                System.err.println("Error opening UDP video socket for call: " + e.getMessage());
                return;
            }
        }
        if (udpAudioSocket == null || udpAudioSocket.isClosed()) {
            try {
                udpAudioSocket = new DatagramSocket();
                localAudioUdpPort = udpAudioSocket.getLocalPort();
                System.out.println("Client UDP audio socket opened on port: " + localAudioUdpPort);
            } catch (SocketException e) {
                System.err.println("Error opening UDP audio socket for call: " + e.getMessage());
                return;
            }
        }

        // Get public addresses for both video and audio sockets via STUN
        InetSocketAddress publicVideoAddress = getPublicAddress(udpVideoSocket);
        InetSocketAddress publicAudioAddress = getPublicAddress(udpAudioSocket);

        String myPublicVideoIp = publicVideoAddress != null ? publicVideoAddress.getAddress().getHostAddress() : null;
        int myPublicVideoPort = publicVideoAddress != null ? publicVideoAddress.getPort() : -1;
        String myPublicAudioIp = publicAudioAddress != null ? publicAudioAddress.getAddress().getHostAddress() : null;
        int myPublicAudioPort = publicAudioAddress != null ? publicAudioAddress.getPort() : -1;


        if (myPublicVideoIp == null || myPublicVideoPort == -1 || myPublicAudioIp == null || myPublicAudioPort == -1) {
            System.err.println("Could not determine public video and/or audio IP/port via STUN. Cannot initiate video call.");
            return;
        }

        // Send call initiation request to server with both IP/port pairs
        Map<String, Object> payload = new HashMap<>();
        payload.put("target_user_id", targetUserId);
        payload.put("sender_public_video_ip", myPublicVideoIp);
        payload.put("sender_udp_video_port", myPublicVideoPort);
        payload.put("sender_public_audio_ip", myPublicAudioIp); // NEW
        payload.put("sender_udp_audio_port", myPublicAudioPort); // NEW

        sendRequestAndAwaitResponse(new Request(Command.INITIATE_VIDEO_CALL, payload));
        System.out.println("Video call initiation request sent to server for user: " + targetUserId +
                " (Video: " + myPublicVideoIp + ":" + myPublicVideoPort +
                ", Audio: " + myPublicAudioIp + ":" + myPublicAudioPort + ")");
    }

    /**
     * Starts both video and audio capture/receiver threads.
     */
    private void startMediaCallThreads() {
        System.out.println("@@@@@ Starting Media Call Threads @@@@@");
        System.out.println("Local Video UDP Port: " + udpVideoSocket.getLocalPort());
        System.out.println("Local Audio UDP Port: " + udpAudioSocket.getLocalPort());
        System.out.println("Remote Video IP: " + remoteVideoIp + ", Port: " + remoteVideoUdpPort);
        System.out.println("Remote Audio IP: " + remoteAudioIp + ", Port: " + remoteAudioUdpPort);


        // Initialize and start Video Capture/Receiver Threads
        if (videoCaptureThread == null || !videoCaptureThread.isAlive()) {
            videoCaptureThread = new VideoCaptureThread(udpVideoSocket, remoteVideoIp, remoteVideoUdpPort);
            videoCaptureThread.start();
        }
        if (videoReceiverThread == null || !videoReceiverThread.isAlive()) {
            videoReceiverThread = new VideoReceiverThread(udpVideoSocket);
            videoReceiverThread.start();
        }

        // Initialize and start Audio Capture/Receiver Threads
        if (audioCaptureThread == null || !audioCaptureThread.isAlive()) {
            audioCaptureThread = new AudioCaptureThread(udpAudioSocket, remoteAudioIp, remoteAudioUdpPort);
            audioCaptureThread.start();
        }
        if (audioReceiverThread == null || !audioReceiverThread.isAlive()) {
            audioReceiverThread = new AudioReceiverThread(udpAudioSocket);
            audioReceiverThread.start();
        }

        // Setup UI for video display
        if (videoFrame == null) {
            videoFrame = new JFrame("Video Call");
            videoLabel = new JLabel();
            videoFrame.add(videoLabel);
            videoFrame.setSize(640, 480);
            videoFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            videoFrame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                    int confirm = JOptionPane.showConfirmDialog(videoFrame,
                            "Are you sure you want to end the call?", "End Call?",
                            JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
                    if (confirm == JOptionPane.YES_OPTION) {
                        // This will stop local threads. Server/peer notification needs to be handled
                        // by explicitly calling endCurrentVideoCall(remoteUserId).
                        System.out.println("User closed video window. Ending call locally. Remember to notify server/peer.");
                        stopMediaCallThreads();
                        videoFrame.dispose();
                        videoFrame = null;
                    }
                }
            });
            videoFrame.setVisible(true);
        } else {
            videoFrame.setVisible(true);
        }
        videoReceiverThread.setVideoDisplayLabel(videoLabel);
    }

    /**
     * Stops both video and audio capture/receiver threads.
     */
    private void stopMediaCallThreads() {
        System.out.println("Stopping Media Call Threads...");
        if (videoCaptureThread != null) {
            videoCaptureThread.stopCapture();
            videoCaptureThread.interrupt();
            videoCaptureThread = null;
        }
        if (videoReceiverThread != null) {
            videoReceiverThread.stopReceiving();
            videoReceiverThread.interrupt();
            videoReceiverThread = null;
        }
        if (audioCaptureThread != null) {
            audioCaptureThread.stopCapture();
            audioCaptureThread.interrupt();
            audioCaptureThread = null;
        }
        if (audioReceiverThread != null) {
            audioReceiverThread.stopReceiving();
            audioReceiverThread.interrupt();
            audioReceiverThread = null;
        }

        if (videoFrame != null) {
            videoFrame.dispose();
            videoFrame = null;
            videoLabel = null;
        }
        System.out.println("Media call threads stopped and window closed.");
    }

    /**
     * Sends an END_VIDEO_CALL request to the server.
     * @param targetUserId The ID of the user with whom the call is to be ended.
     */
    public void endCurrentVideoCall(int targetUserId) {
        if (currentUser != null && targetUserId != -1) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("target_user_id", targetUserId);
            sendRequestAndAwaitResponse(new Request(Command.END_VIDEO_CALL, payload));
            System.out.println("Sent end call request to server for user: " + targetUserId);
            stopMediaCallThreads();
        } else {
            System.out.println("Cannot end call: No current user or target user ID is unknown.");
        }
    }

    /**
     * Sends UDP punching packets for both video and audio streams.
     */
    private void sendUdpPunchingPackets() {
        // Send punching packets for video stream
        if (udpVideoSocket != null && !udpVideoSocket.isClosed() && remoteVideoIp != null) {
            sendSingleUdpPunchingPacket(udpVideoSocket, remoteVideoIp, remoteVideoUdpPort, "Video");
        } else {
            System.err.println("Video UDP socket not ready for punching.");
        }

        // Send punching packets for audio stream
        if (udpAudioSocket != null && !udpAudioSocket.isClosed() && remoteAudioIp != null) {
            sendSingleUdpPunchingPacket(udpAudioSocket, remoteAudioIp, remoteAudioUdpPort, "Audio");
        } else {
            System.err.println("Audio UDP socket not ready for punching.");
        }
    }

    /**
     * Helper method to send a single set of UDP punching packets.
     */
    private void sendSingleUdpPunchingPacket(DatagramSocket socket, InetAddress remoteIp, int remoteUdpPort, String streamType) {
        try {
            byte[] data = new byte[1]; // Minimal data
            DatagramPacket packet = new DatagramPacket(data, data.length, remoteIp, remoteUdpPort);
            for (int i = 0; i < 5; i++) {
                socket.send(packet);
                // System.out.println("Sent " + streamType + " UDP punching packet " + (i + 1) + " to " + remoteIp.getHostAddress() + ":" + remoteUdpPort);
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            System.out.println("Sent " + streamType + " UDP punching packets to " + remoteIp.getHostAddress() + ":" + remoteUdpPort);
        } catch (IOException e) {
            System.err.println("Error sending " + streamType + " UDP punching packet: " + e.getMessage());
        }
    }

    @Override
    public void close() throws Exception {
        closeConnection();
    }

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
            if (scanner != null) {
                scanner.close();
            }
            // Close both UDP sockets
            if (udpVideoSocket != null && !udpVideoSocket.isClosed()) {
                udpVideoSocket.close();
            }
            if (udpAudioSocket != null && !udpAudioSocket.isClosed()) {
                udpAudioSocket.close();
            }
            stopMediaCallThreads();
            System.out.println("Client connection closed.");
        } catch (IOException e) {
            System.err.println("Error closing client resources: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        try (ChatClient5 client = new ChatClient5()) {
            System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
            client.startClient();
        } catch (Exception e) {
            System.err.println("Client application error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
