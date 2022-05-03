import java.io.Serializable;

/**
 * A basic utility class that encapsulates all relevant messages regarding distance vectors.
 * <p>
 * This class is used to implement a basic protocol between the server and client in this project.
 * Because the server needs to be able to resolve the client's identity, we would need to use a 
 * stateful communication protocol.
 * <p>
 * The actual protocol here is fairly simple. The client first sends a JOIN message to the server,
 * to which the server will respond with an ACK and wait for the rest of the clients to join.
 * Once that happens, the server will send a READY message to everyone with their initial DV and id.
 * All future UPDATE messages that the client sends will contain its id so that the server can forward accordingly.
 * <p>
 * Clients initiate (and reset) a 3 second timer every time it updates its own distance vector table.
 * When this timer expires, then the client closes the connection and sends a CLOSE message to the server.
 * The server closes its socket once every client has done so.
 * 
 * @author jgt31
 */
public class DVMessage implements Serializable {
    enum MessageType {JOIN, ACK, UPDATE, READY, CLOSE}

    private MessageType type;
    private int id;
    private int[] costVector;

    /**
     * Private constructor. Will be called by the factory methods in this class.
     */
    private DVMessage(MessageType t, int id, int[] vec) {
        this.type = t;
        this.id = id;
        this.costVector = vec;
    }

    /**
     * Creates a default join DVMessage and returns it.
     */
    public static DVMessage createJoinMessage() {
        return new DVMessage(MessageType.JOIN, -1, null);
    }

    /**
     * Creates a "ready" DVMessage with the user's id and initial distance vector.
     * This is sent when all users have joined and the server is ready for the DV algorithm to begin.
     */
    public static DVMessage createReadyMessage(int id, int[] vec) {
        if (vec == null)
            throw new NullPointerException("The distance vector cannot be null.");
        
        return new DVMessage(MessageType.READY, id, vec);
    }

    /**
     * Creates an "ack" DVMessage, signifying that the server has successfully received the client's join.
     */
    public static DVMessage createAckMessage() {
        return new DVMessage(MessageType.ACK, -1, null);
    }

    /**
     * Creates an update DVMessage with the sender's id and their updated distance vector.
     */
    public static DVMessage createUpdateMessage(int id, int[] vec) {
        if (vec == null)
            throw new NullPointerException("The distance vector cannot be null.");
        
        return new DVMessage(MessageType.UPDATE, id, vec);
    }

    /**
     * Creates a closing message with the sender's id.
     */
    public static DVMessage createCloseMessage(int id) {
        if (id < 0)
            throw new IllegalArgumentException("Attempted to close client with invalid ID: " + id);
        
        return new DVMessage(MessageType.CLOSE, id, null);
    }

    /**
     * Gets the current message's type.
     * @return The message's type
     */
    public MessageType getType() {
        return type;
    }

    /**
     * Gets the ID held by this message.
     * @return The sender's id, or -1 if the id is unknown
     */
    public int getId() {
        return id;
    }

    /**
     * Gets the cost vector of this message.
     * This method will return null if there is no associated cost vector.
     * @return The cost vector, if present
     */
    public int[] getCostVector() {
        return costVector;
    }
}
