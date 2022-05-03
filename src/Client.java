import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The client class for this project. It will receive its initial cost vector
 * from the server, then iterate using the Bellman-Ford equation.
 * 
 * @author jgt31
 */
public class Client {
    // Console colors (because why not!)
    static final String CONSOLE_RESET = "\u001B[0m";
    static final String CONSOLE_RED = "\u001B[31m";
    static final String CONSOLE_GREEN = "\u001B[32m";
    static final String CONSOLE_YELLOW = "\u001B[33m";
    static final String CONSOLE_CYAN = "\u001B[36m";

    public static void main(String[] args) {
        // Argument validation
        if (args.length != 2) {
            System.err.println(CONSOLE_RED + "Usage: java Client <host-addr> <host-port>" + CONSOLE_RESET);
            System.exit(1);
        }

        String addr = args[0];
        int port = Integer.parseInt(args[1]);
        SocketAddress servAddr = new InetSocketAddress(addr, port);

        // Enter the main listening loop
        listen(servAddr);
    }

    /**
     * Main listening loop for this thread
     */
    private static void listen(SocketAddress bindAddr) {
        try (DatagramSocket socket = new DatagramSocket()) {
            // Here, we setup and dispatch the handler thread.
            // This will also send the initial JOIN message to the server.
            ClientHandlerThread handler = new ClientHandlerThread(bindAddr, socket);
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
            System.exit(1);
        }
    }
}

/**
 * A separate thread to handle incoming packets.
 * This is the main thread that will conduct the calculations in the DV algorithm.
 */
class ClientHandlerThread implements Runnable {
    // Parameters for the DV algorithm
    private int id = -1;
    private int[] costVector = null;
    private int[] fwdTable = null;

    // Socket variables
    private SocketAddress servAddr;
    private DatagramSocket socket;
    private BlockingQueue<DatagramPacket> packetQueue;

    // Timer variables
    private ScheduledExecutorService timerThread = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> timeout = null, ackTimeout = null;

    /**
     * This points the thread to send to the server's socket address.
     * 
     * @param servAddr The server's address.
     * @param socket The socket to use for transmission.
     */
    public ClientHandlerThread(SocketAddress servAddr, DatagramSocket socket) {
        this.servAddr = servAddr;
        this.socket = socket;
        packetQueue = new ArrayBlockingQueue<>(3);
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
     * Starts the execution of this handler thread.
     * First, the thread will send a JOIN message to the server, then enter the waiting loop.
     */
    @Override
    public void run() {
        // First, we start off by sending a JOIN message to the server.
        DVMessage joinMsg = DVMessage.createJoinMessage();
        sendMessage(joinMsg);

        // Start a timer that will go off if we don't receive an ACK after 5 seconds.
        ScheduledExecutorService ackThread = Executors.newSingleThreadScheduledExecutor();
        ackTimeout = ackThread.schedule(() -> socket.close(), 5, TimeUnit.SECONDS);

        // Now we enter the main waiting loop.
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
     * Handles an incoming packet.
     */
    private void handlePacket(DatagramPacket pkt) {
        ByteArrayInputStream binIn = new ByteArrayInputStream(pkt.getData(), pkt.getOffset(), pkt.getLength());

        // We need to deserialize the incoming packet first.
        try (ObjectInputStream objIn = new ObjectInputStream(binIn)) {
            DVMessage msg = (DVMessage) objIn.readObject();
            DVMessage.MessageType type = msg.getType();

            StringBuilder log = new StringBuilder(Client.CONSOLE_GREEN + "Receiving " + type.name() + " message from ");
            
            // Execute based on the message type
            switch (type) {
                case ACK:
                    // Stop our timer
                    ackTimeout.cancel(false);

                    log.append("server." + Client.CONSOLE_RESET);
                    System.out.println(log.toString());
                    break;

                case READY:
                    // Instantiate our id and initial distance vector values, then send an UPDATE as our initial broadcast
                    id = msg.getId();
                    costVector = msg.getCostVector();

                    fwdTable = new int[costVector.length];
                    for (int i = 0; i < fwdTable.length; i++)
                        fwdTable[i] = i;

                    log.append("server, DV = " + Arrays.toString(msg.getCostVector()) + Client.CONSOLE_RESET);
                    System.out.println(log.toString());

                    DVMessage updMsg = DVMessage.createUpdateMessage(id, costVector);
                    sendMessage(updMsg);
                    break;
                
                case UPDATE:
                    // If an out-of-order transmission occurred and we don't have a valid cost vector yet, requeue the packet.
                    log.append("node " + msg.getId() + ", DV = " +
                                Arrays.toString(msg.getCostVector()) + Client.CONSOLE_RESET);
                    System.out.println(log.toString());

                    if (costVector == null) {
                        queuePacket(pkt);
                        return;
                    }

                    updateDV(msg);
                    break;
                    
                default:
                    // We can just ignore every other case.
                    System.out.println(Client.CONSOLE_RED + "Unexpected message: " + type.toString() + Client.CONSOLE_RESET);
                    break;
            }

        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Update our cost vector based on the Bellman-Ford equation.
     * 
     * @param msg The received message.
     */
    private void updateDV(DVMessage msg) {
        int senderId = msg.getId();
        int costToSender = costVector[senderId];
        int[] senderDV = msg.getCostVector();

        boolean updated = false;
        
        // Iterate for each node in our network
        // We use some special cases for negative weights, since that represents the absence of a connection
        for (int i = 0; i < costVector.length; i++) {
            if (senderDV[i] <= -1)
                continue;

            // This is the Bellman-Ford equation!
            if (costVector[i] <= -1 || costVector[i] > costToSender + senderDV[i]) {
                updated = true;
                costVector[i] = costToSender + senderDV[i];
                fwdTable[i] = fwdTable[senderId];
            }
        }

        // If we've changed our cost vector at all, then re-broadcast
        if (updated) {
            // Start a timer - if we haven't updated our DV in 3 seconds then we can close the connection.
            if (timeout != null)
                timeout.cancel(false);
            
            timeout = timerThread.schedule(() -> close(), 3, TimeUnit.SECONDS);

            DVMessage updMessage = DVMessage.createUpdateMessage(id, costVector);
            sendMessage(updMessage);
        }
    }

    /**
     * Sends a given message to the server.
     * 
     * @param msg The message to transmit.
     */
    private void sendMessage(DVMessage msg) {
        // Log the outgoing packet
        DVMessage.MessageType type = msg.getType();
        StringBuilder log = new StringBuilder(Client.CONSOLE_YELLOW + "Sending " + type.name());

        if (type == DVMessage.MessageType.JOIN)
            log.append(" to server at " + servAddr);
        else if (type == DVMessage.MessageType.CLOSE)
            log.append(" to server.");
        else
            log.append(", DV = " + Arrays.toString(msg.getCostVector()));

        log.append(Client.CONSOLE_RESET);
        System.out.println(log.toString());
        
        ByteArrayOutputStream binOut = new ByteArrayOutputStream();

        // Serialize the message and send it to the specified destination.
        try (ObjectOutputStream objOut = new ObjectOutputStream(binOut)) {
            objOut.writeObject(msg);
            objOut.flush();

            byte[] serialMsg = binOut.toByteArray();
            DatagramPacket pkt = new DatagramPacket(serialMsg, serialMsg.length, servAddr);

            socket.send(pkt);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
    

    /**
     * Logs the final routing table, sends a CLOSE message and closes the socket.
     */
    private void close() {
        StringBuilder sb = new StringBuilder("Routing table for node " + id + ":\n" + Client.CONSOLE_CYAN);

        for (int i = 0; i < costVector.length; i++) {
            sb.append("To node " + i + ": {nextHop = " + fwdTable[i] + ", totalCost = " + costVector[i] + "}\n");
        }

        System.out.print(sb.append(Client.CONSOLE_RESET));

        DVMessage closeMsg = DVMessage.createCloseMessage(id);
        sendMessage(closeMsg);

        socket.close();
    }
}