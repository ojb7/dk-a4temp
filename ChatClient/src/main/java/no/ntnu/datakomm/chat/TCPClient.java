package no.ntnu.datakomm.chat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.LinkedList;
import java.util.List;
import java.net.UnknownHostException;
import java.io.IOException;
import java.net.ConnectException;

public class TCPClient {
    private PrintWriter toServer;
    private BufferedReader fromServer;
    private Socket connection;

    // Hint: if you want to store a message for the last error, store it here
    private String lastError = null;

    private final List<ChatListener> listeners = new LinkedList<>();

    /**
     * Connect to a chat server.
     *
     * @param host host name or IP address of the chat server
     * @param port TCP port of the chat server
     * @return True on success, false otherwise
     */
    public boolean connect(String host, int port) {
        // Hint: Remember to process all exceptions and return false on error
        // Hint: Remember to set up all the necessary input/output stream variables
        boolean success = false;
        try {
            this.connection = new Socket(host, port);
            InputStream input = this.connection.getInputStream();
            OutputStream output = this.connection.getOutputStream();
            this.toServer = new PrintWriter(output, true);
            this.fromServer = new BufferedReader(new InputStreamReader(input));
            System.out.println("Connected!");
            success = true;
        } catch (UnknownHostException e) {
            System.out.println("Unknown host: " + e.getMessage());
        } catch (ConnectException e) {
            System.out.println("Unknown port: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("I/O error: " + e.getMessage());
        }
        return success;
    }

    /**
     * Close the socket. This method must be synchronized, because several
     * threads may try to call it. For example: When "Disconnect" button is
     * pressed in the GUI thread, the connection will get closed. Meanwhile, the
     * background thread trying to read server's response will get error in the
     * input stream and may try to call this method when the socket is already
     * in the process of being closed. with "synchronized" keyword we make sure
     * that no two threads call this method in parallel.
     */
    public synchronized void disconnect() {
        // Hint: remember to check if connection is active
        if (this.connection != null) {
            try {
                this.toServer.close();
                this.fromServer.close();
                this.connection.close();
            } catch (IOException e) {
                this.connection = null;
                System.out.println("Error disconnecting: " + e.getMessage());
            }
        } else {
            System.out.println("No connection established, can not close.");
        }
        System.out.println("Disconnected!");
        this.connection = null;
    }

    /**
     * @return true if the connection is active (opened), false if not.
     */
    public boolean isConnectionActive() {
        return connection != null;
    }

    /**
     * Send a command to server.
     *
     * @param cmd A command. It should include the command word and optional attributes, according to the protocol.
     * @return true on success, false otherwise
     */
    private boolean sendCommand(String cmd) {
        // Hint: Remember to check if connection is active
        boolean success = false;
        if (this.connection != null) {
            this.toServer.println(cmd);
            success = true;
        }
        return success;
    }

    /**
     * Send a public message to all the recipients.
     *
     * @param message Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPublicMessage(String message) {
        boolean success = false;
        if ((message != null) && (message.indexOf('\n') < 0)) {
            sendCommand("msg " + message);
            success = true;
        }
        return success;
    }

    /**
     * Send a login request to the chat server.
     *
     * @param username Username to use
     */
    public void tryLogin(String username) {
        // Hint: Reuse sendCommand() method
        if (username != null) {
            sendCommand("login " + username);
        }
    }

    /**
     * Send a request for latest user list to the server. To get the new users,
     * clear your current user list and use events in the listener.
     */
    public void refreshUserList() {
        // Hint: Use Wireshark and the provided chat client reference app to find out what commands the
        // client and server exchange for user listing.
        sendCommand("users");
    }

    /**
     * Send a private message to a single recipient.
     *
     * @param recipient username of the chat user who should receive the message
     * @param message   Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPrivateMessage(String recipient, String message) {
        // Hint: update lastError if you want to store the reason for the error.
        boolean success = false;
        if (((message != null) && (recipient != null)) && (message.indexOf('\n') < 0)) {
            sendCommand("privmsg " + recipient + " " + message);
            success = true;
        }
        return success;
    }

    /**
     * Send a request for the list of commands that server supports.
     */
    public void askSupportedCommands() {
        // TODO Step 8: Implement this method
        // Hint: Reuse sendCommand() method
        sendCommand("help");
    }

    /**
     * Wait for chat server's response
     *
     * @return one line of text (one command) received from the server
     */
    private String waitServerResponse() {
        // If you get I/O Exception or null from the stream, it means that something has gone wrong
        // with the stream and hence the socket. Probably a good idea to close the socket in that case.
        String response = null;
        try {
            response = this.fromServer.readLine();
            if (response == null) {
                disconnect();
            }
        } catch (IOException e) {
            System.out.println("Error on response: " + e.getMessage());
            disconnect();
            onDisconnect();
        }
        System.out.println("Response from server: " + response);
        return response;
    }

    /**
     * Get the last error message
     *
     * @return Error message or "" if there has been no error
     */
    public String getLastError() {
        if (lastError != null) {
            return lastError;
        } else {
            return "";
        }
    }

    /**
     * Start listening for incoming commands from the server in a new CPU thread.
     */
    public void startListenThread() {
        Thread t = new Thread(() ->
                parseIncomingCommands());
        t.start();
    }

    /**
     * Read incoming messages one by one, generate events for the listeners. A loop that runs until
     * the connection is closed.
     */
    private void parseIncomingCommands() {
        while (isConnectionActive()) {
            //The whole response from server, example: loginok, loginerr, privmsg <username> <message>, etc...
            String serverResponse = waitServerResponse();

            //Check if server response (String) is not null and it has more than 0 characters
            if ((serverResponse != null) && (serverResponse.length() > 0)) {

                //Command word, the first word (String) the server returns
                String serverCommand;
                //message from server without command word (the first word server sends back)
                String serverMessage;

                //The index number of the first space character in serverResponse
                int firstSpaceInServerResponseIndex = serverResponse.indexOf(' ');

                //If there is space character in server response then serverCommand is set to first word in
                // serverResponse and the rest om serverResponse is set to serverMessage. If there is only one
                // word in serverResponse then serverCommand is set to it and serverMessage is "".
                if (firstSpaceInServerResponseIndex >= 0) {
                    serverCommand = serverResponse.substring(0, firstSpaceInServerResponseIndex);
                    serverMessage = serverResponse.substring(firstSpaceInServerResponseIndex + 1);
                } else {
                    serverCommand = serverResponse;
                    serverMessage = "";
                }

                switch (serverCommand) {
                    case "loginok":
                        onLoginResult(true, null);
                        break;

                    case "loginerr":
                        onLoginResult(false, serverMessage);
                        break;

                    case "users":
                        onUsersList(serverMessage.split(" "));
                        break;

                    case "msg":
                        //The index number of the first space character in serverMessage, used with substring to separate
                        // sender when private message
                        int firstSpaceInServerMessageIndex = serverMessage.indexOf(' ');
                        //Sets the sender of the message to the first word in serverMessage
                        String userOfMessage = serverMessage.substring(0, firstSpaceInServerMessageIndex);
                        //Sets the message from the user to the rest of serverMessage
                        String messageFromUser = serverMessage.substring(firstSpaceInServerMessageIndex + 1);

                        onMsgReceived(false, userOfMessage, messageFromUser);
                        break;

                    case "privmsg":
                        firstSpaceInServerMessageIndex = serverMessage.indexOf(' ');
                        userOfMessage = serverMessage.substring(0, firstSpaceInServerMessageIndex);
                        messageFromUser = serverMessage.substring(firstSpaceInServerMessageIndex + 1);
                        onMsgReceived(true, userOfMessage, messageFromUser);
                        break;

                    case "msgerr":
                        onMsgError(serverMessage);
                        break;

                    case "cmderr":
                        onCmdError(serverMessage);
                        break;

                    case "supported":
                        onSupported(serverMessage.split(" "));
                        break;
                }
            }
        }
    }

    /**
     * Register a new listener for events (login result, incoming message, etc)
     *
     * @param listener
     */
    public void addListener(ChatListener listener) {
        if (!listeners.contains(listener)) {
            this.listeners.add(listener);
        }
    }

    /**
     * Unregister an event listener
     *
     * @param listener
     */
    public void removeListener(ChatListener listener) {
        this.listeners.remove(listener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////////////////////
    // The following methods are all event-notificators - notify all the listeners about a specific event.
    // By "event" here we mean "information received from the chat server".
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Notify listeners that login operation is complete (either with success or
     * failure)
     *
     * @param success When true, login successful. When false, it failed
     * @param errMsg  Error message if any
     */
    private void onLoginResult(boolean success, String errMsg) {
        for (ChatListener l : this.listeners) {
            l.onLoginResult(success, errMsg);
        }
    }

    /**
     * Notify listeners that socket was closed by the remote end (server or
     * Internet error)
     */
    private void onDisconnect() {
        // Hint: all the onXXX() methods will be similar to onLoginResult()
        for (ChatListener l : this.listeners) {
            l.onDisconnect();
        }
    }

    /**
     * Notify listeners that server sent us a list of currently connected users
     *
     * @param users List with usernames
     */
    private void onUsersList(String[] users) {
        for (ChatListener l : this.listeners) {
            l.onUserList(users);
        }
    }

    /**
     * Notify listeners that a message is received from the server
     *
     * @param priv   When true, this is a private message
     * @param sender Username of the sender
     * @param text   Message text
     */
    private void onMsgReceived(boolean priv, String sender, String text) {
        for (ChatListener l : this.listeners) {
            l.onMessageReceived(new TextMessage(sender, priv, text));
        }
    }

    /**
     * Notify listeners that our message was not delivered
     *
     * @param errMsg Error description returned by the server
     */
    private void onMsgError(String errMsg) {
        for (ChatListener l : this.listeners) {
            l.onMessageError(errMsg);
        }
    }

    /**
     * Notify listeners that command was not understood by the server.
     *
     * @param errMsg Error message
     */
    private void onCmdError(String errMsg) {
        for (ChatListener l : this.listeners) {
            l.onCommandError(errMsg);
        }
    }

    /**
     * Notify listeners that a help response (supported commands) was received
     * from the server
     *
     * @param commands Commands supported by the server
     */
    private void onSupported(String[] commands) {
        for (ChatListener l : this.listeners) {
            l.onSupportedCommands(commands);
        }
    }
}