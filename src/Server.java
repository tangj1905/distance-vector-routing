import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * The server class for this project. It will load the configuration file,
 * then send the pre-defined <node, cost> table to each client connecting to the server.
 * 
 * @author jgt31
 */
public class Server {
    // Console colors!
    static final String CONSOLE_RESET = "\u001B[0m";
    static final String CONSOLE_RED = "\u001B[31m";
    static final String CONSOLE_GREEN = "\u001B[32m";
    static final String CONSOLE_YELLOW = "\u001B[33m";
    static final String CONSOLE_CYAN = "\u001B[36m";

    /**
     * The main method to start the server.
     * 
     * @param args The command line arguments to be passed in.
     */
    public static void main(String[] args) {
        // Argument validation
        if (args.length != 1) {
            System.err.println(CONSOLE_RED + "Usage: java Server <port-number>" + CONSOLE_RESET);
            System.exit(1);
        }

        int port = Integer.parseInt(args[0]);
        listen(port);
    }

    /**
     * Creates the main listening loop for the server.
     * 
     * @param port The port number to listen on.
     */
    private static void listen(int port) {
        int[][] costMatrix = readCosts();

        try (DatagramSocket socket = new DatagramSocket(port)) {
            System.out.println("Listening on port " + port + "...");
            
            // Dispatch the handler thread
            ServerHandlerThread handler = new ServerHandlerThread(costMatrix, socket);
            Thread handlerThread = new Thread(handler);
            handlerThread.start();

            // Main listening loop
            while (true) {
                byte[] buffer = new byte[512];
                DatagramPacket rcv = new DatagramPacket(buffer, buffer.length);
                socket.receive(rcv);

                // Queue the packet for handling by the other thread
                handler.queuePacket(rcv);
            }

        } catch (SocketException e) {
            // Indicates that the socket was closed.
            System.out.println(CONSOLE_RED + "The socket has been closed." + CONSOLE_RESET);
            System.exit(0);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Reads in the configuration CSV file.
     * The file can be found in <code>{classpath}/config/config.csv</code>.
     * <p>
     * By default, the weights form the following network configuration:
     * <pre>
     * 
     * (u = 0, v = 1, w = 2, ...)
     * 
     *             +---+--------------------------+
     *   +---------+ 3 |                         9|
     *  5|         +-+-+--------------+           |
     *   |           |               7|           |
     *   |          4|                |           |
     *   |           |        +--+    |           |
     * +-+-+   3   +-+-+     8|  |  +-+-+   2   +-+-+
     * | 0 +-------+ 2 +---+  |  +--+ 4 +-------+ 5 |
     * +-+-+       +-+-+   |  |     +-+-+       +---+
     *   |           |     +--+       |
     *   |          3|                |
     *   |           |                |
     *  7|         +-+-+             4|
     *   +---------+ 1 +--------------+
     *             +---+
     * </pre>
     * 
     * In the configuration file, any negative weight indicates that there is no connection
     * between the two nodes. In general, the algorithm will assume all costs between
     * two distinct nodes will be positive.
     * 
     * @return A 2D array that holds the costs between each node pair.
     */
    private static int[][] readCosts() {
        try (BufferedReader br = new BufferedReader(new FileReader("config\\config.csv"))) {
            String line;
            List<int[]> costs = new ArrayList<>();

            // Read the file line by line, splitting by comma
            while ((line = br.readLine()) != null) {
                int[] costRow = Arrays.stream(line.split(","))
                                        .map(String::trim)
                                        .mapToInt(Integer::parseInt)
                                        .toArray();

                costs.add(costRow);
            }

            // Convert to 2D array
            int[][] costMatrix = new int[costs.size()][];
            for (int i = 0; i < costs.size(); i++)
                costMatrix[i] = costs.get(i);
            
            // Check for configuration validity.
            for (int i = 0; i < costMatrix.length; i++) {
                for (int j = i; j < costMatrix.length; j++) {
                    if (j == i && costMatrix[i][j] != 0) {
                        System.err.println(CONSOLE_RED + "Config: node cost to self must be zero." + CONSOLE_RESET);
                        System.exit(1);
                    }

                    if (costMatrix[i][j] != costMatrix[j][i]) {
                        System.err.println(CONSOLE_RED + "Config: node costs must be symmetric." + CONSOLE_RESET);
                        System.exit(1);
                    }
                }
            }

            return costMatrix;
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }

        return null;
    }
}

/**
 * A separate thread to handle incoming packets.
 */
class ServerHandlerThread implements Runnable {
    private int[][] costMatrix;
    private DatagramSocket socket;
    private BlockingQueue<DatagramPacket> packetQueue;
    private SocketAddress[] clientAddresses;

    private int closedCtr = 0;

    /**
     * Public constructor for this thread.
     * The parent thread reads the costs specified in the config file, which is passed here.
     * 
     * @param costs The cost matrix for all of the nodes in our network.
     * @param socket The socket to use for transmission.
     */
    public ServerHandlerThread(int[][] costs, DatagramSocket sock) {
        costMatrix = costs;
        socket = sock;
        clientAddresses = new SocketAddress[costMatrix.length];
        packetQueue = new ArrayBlockingQueue<>(3 * costMatrix.length);
    }

    /**
     * Inserts a packet into the buffer.
     * 
     * @param packet The packet to insert into the queue.
     */
    public void queuePacket(DatagramPacket packet) {
        packetQueue.offer(packet);
    }

    /**
     * Main run method which will handle all packet reception.
     */
    @Override
    public void run() {
        try {
            while (true) {
                DatagramPacket pkt = packetQueue.take();
                handlePacket(pkt);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Handles an incoming packet. This will take care of the all of the relevant stateful aspects.
     * This method follows a basic protocol, which is described in the message format.
     * 
     * @param pkt The incoming packet to handle.
     * @see DVMessage
     */
    private void handlePacket(DatagramPacket pkt) {
        ByteArrayInputStream binIn = new ByteArrayInputStream(pkt.getData(), pkt.getOffset(), pkt.getLength());

        // Deserialize the contents of the packet
        try (ObjectInputStream objIn = new ObjectInputStream(binIn)) {
            DVMessage msg = (DVMessage) objIn.readObject();
            DVMessage.MessageType type = msg.getType();

            // Log the incoming packet
            StringBuilder log = new StringBuilder(Server.CONSOLE_GREEN + "Receiving " + type.name() + " message from ");

            if (type == DVMessage.MessageType.JOIN)
                log.append("client at " + pkt.getSocketAddress());
            else
                log.append("node " + msg.getId());
            
            log.append(Server.CONSOLE_RESET);
            System.out.println(log.toString());
            
            // Execute based on the message type
            switch (type) {
                case JOIN:
                    SocketAddress addr = pkt.getSocketAddress();
                    addClient(msg, addr);
                    break;
                
                case UPDATE:
                    forwardToNeighbors(msg);
                    break;
                
                case CLOSE:
                    incrementCloseCt();
                    break;
                
                default:
                    // Otherwise, we can just ignore the incoming packets
                    break;
            }

        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Maps a new id to the client's address and allows the client to join the network.
     * Once every client has joined, the server will broadcast a "READY" message to everyone.
     */
    private void addClient(DVMessage msg, SocketAddress clientAddr) {
        // Find the smallest client ID that hasn't been taken yet
        int clientId = -1;

        for (int id = 0; id < clientAddresses.length; id++) {
            if (clientAddresses[id] == null) {
                // If this position hasn't been assigned yet, do so
                clientAddresses[id] = clientAddr;
                clientId = id;
                break;
            }
        }

        // Send an ACK back to the client
        DVMessage ackMsg = DVMessage.createAckMessage();
        sendMessage(ackMsg, clientId);
        
        if (clientId == clientAddresses.length - 1) {
            // If the network has just filled, broadcast the READY message to everyone
            for (int i = 0; i < costMatrix.length; i++) {
                int[] initCostVector = costMatrix[i];
                DVMessage readyMsg = DVMessage.createReadyMessage(i, initCostVector);

                sendMessage(readyMsg, i);
            }
        }
    }

    /**
     * Broadcasts the message to the client's neighbors.
     */
    private void forwardToNeighbors(DVMessage msg) {
        int clientId = msg.getId();

        for (int i = 0; i < costMatrix.length; i++) {
            // Indicates that there's a direct connection between these two nodes.
            if (costMatrix[clientId][i] > 0)
                sendMessage(msg, i);
        }
    }

    /**
     * Increases the closed counter. If every client has closed their connection, then
     * the server can close its socket.
     */
    private void incrementCloseCt() {
        closedCtr++;

        if (closedCtr == costMatrix.length) {
            // Indicates that every client has closed their connection.
            socket.close();
        }
    }

    /**
     * Sends a given message to the specified user id.
     * 
     * @param msg The message to transmit.
     * @param clientID The ID of the client to send to.
     */
    private void sendMessage(DVMessage msg, int clientID) {
        ByteArrayOutputStream binOut = new ByteArrayOutputStream();
        
        // Log outgoing node
        System.out.println(Server.CONSOLE_YELLOW + "Sending " + msg.getType().name() + " to node " + clientID);

        // Serialize the message and send it to the specified destination.
        try (ObjectOutputStream objOut = new ObjectOutputStream(binOut)) {
            objOut.writeObject(msg);
            objOut.flush();

            byte[] serialMsg = binOut.toByteArray();
            SocketAddress destination = clientAddresses[clientID];
            DatagramPacket pkt = new DatagramPacket(serialMsg, serialMsg.length, destination);

            socket.send(pkt);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}