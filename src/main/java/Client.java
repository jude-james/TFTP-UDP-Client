import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.InputMismatchException;
import java.util.Random;
import java.util.Scanner;

public class Client {
    private final DatagramSocket socket;

    private DatagramPacket receivedPacket;
    private InetAddress address;

    private final int serverPort = 69;
    private int connectionPort;

    private byte[] buffer = new byte[516];

    // 2 byte opcode / operation
    private final short RRQ = 1;
    private final short WRQ = 2;
    private final short DATA = 3;
    private final short ACK = 4;
    private final short ERROR = 5;

    private final int timeoutTime = 2000;

    private File file;
    private String fileName;
    private final String path = "src/main/java/";
    private short request = -1;

    public Client() throws IOException {
        int port = new Random().nextInt(65_536); // > 1024 ??
        socket = new DatagramSocket(port);
        address = InetAddress.getByName("localhost");
    }

    /**
     * Reads the file name and request type from the user
     */
    private void getUserRequest() {
        System.out.println("Type (1) to retrieve a file, type (2) to store a file, type (3) to exit: ");

        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNext()) {
            try {
                request = scanner.nextShort();
            }
            catch (InputMismatchException e) {
                System.out.println("Please type (1), (2) or (3): ");
                scanner.next();
                continue;
            }

            if (request == RRQ || request == WRQ) {
                break;
            }
            else if (request == 3) {
                System.exit(0);
            }
            else {
                System.out.println("Please type (1), (2) or (3): ");
            }
        }

        System.out.println("Type the name of the file: ");
        fileName = scanner.next();

        if (request == RRQ) {
            System.out.println("Retrieving file '" + fileName + "' from server.");
        }
        else if (request == WRQ) {
            System.out.println("Storing file '" + fileName + "' on server.");
        }
    }


    /**
     * Sends the initial request packet to the server and receives the first response, checking if it can establish a connection
     */
    public void sendRequest() throws IOException {
        getUserRequest();

        file = new File(path + fileName);
        if (request == WRQ) {
            if (!file.exists()) {
                System.out.println("Could not locate file with name: '" + fileName + "' in " + path);
                sendRequest();
                return;
            }
        }

        // Create a request packet and send it to the server
        DatagramPacket requestPacket = constructRequestPacket(request, fileName, "octet", address, serverPort);
        socket.send(requestPacket);
        System.out.println("Sent request packet to server at port: " + serverPort);

        socket.setSoTimeout(timeoutTime);

        // Wait for server to send a packet back
        receivedPacket = new DatagramPacket(buffer, buffer.length);
        try {
            socket.receive(receivedPacket);
        }
        catch (SocketTimeoutException e) {
            System.err.println("Timeout. Server may not be running.");
            return;
        }

        connectionPort = receivedPacket.getPort();
        short receivedOpcode = parseOpcode(receivedPacket);
        System.out.println("Received opcode " + receivedOpcode + " from server at port: " + connectionPort);

        if (receivedOpcode == DATA && request == RRQ) {
            System.out.println("Connection established");
            CompleteReadRequest();
        }
        else if (receivedOpcode == ACK && request == WRQ ) {
            System.out.println("Connection established");
            CompleteWriteRequest();
        }
        else if (receivedOpcode == ERROR) {
            String errorMessage = parseErrorPacket(receivedPacket);
            System.out.println("Received an error from server with message: '" + errorMessage + "'");
            sendRequest();
        }
        else {
            System.err.println("Received unknown packet. Disregarding packet.");
        }
    }

    /**
     * Sends acknowledgement packets to the server and receives data packets, writing the data into a new file
     */
    private void CompleteReadRequest() throws IOException {
        short blockNumber = 1;
        short receivedBlockNumber = parseBlockNumber(receivedPacket);

        DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(file));

        // Write the first data packet that established a connection to the file
        outputStream.write(receivedPacket.getData(), 4, receivedPacket.getLength() - 4);

        // Then send and received the rest in a loop
        while (receivedPacket.getLength() >= 512) {
            // Send ACK packet to server with the same block number as the data packet
            DatagramPacket ACKPacket = constructACKPacket(receivedBlockNumber, receivedPacket.getAddress(), receivedPacket.getPort());
            socket.send(ACKPacket);
            System.out.println("Sent ACK packet with block number: " + receivedBlockNumber);

            socket.setSoTimeout(timeoutTime);

            try {
                socket.receive(receivedPacket);
            }
            catch (SocketTimeoutException e) {
                System.err.println("Timeout occurred. Retransmitting packet.");
                continue;
            }

            // Check TIDs match and block number is correct
            if (receivedPacket.getPort() == connectionPort) {
                receivedBlockNumber = parseBlockNumber(receivedPacket);
                if (parseOpcode(receivedPacket) == DATA && receivedBlockNumber == blockNumber + 1) {
                    System.out.println("Success, received DATA packet with block number: " + receivedBlockNumber);
                    outputStream.write(receivedPacket.getData(), 4, receivedPacket.getLength() - 4);
                    blockNumber = receivedBlockNumber;
                }
                else {
                    System.err.println("Received unknown packet or incorrect block number. Disregarding packet and retransmitting ACK packet...");
                }
            }
            else {
                System.err.println("Received packet from unknown port. Disregarding packet.");
            }
        }

        System.out.println("Finished reading file from server.");
        outputStream.close();
        socket.close();
    }

    /**
     * Sends the file to the server in data packets and waits for acknowledgement packets in return
     */
    private void CompleteWriteRequest() throws IOException {
        FileInputStream inputStream = new FileInputStream(file);

        byte[] dataBuffer;
        int bytesRead;
        short blockNumber = parseBlockNumber(receivedPacket); // should be zero
        blockNumber++;

        while (true) {
            // Read up to 512 bytes from the file and store in the data buffer
            dataBuffer = new byte[512];
            bytesRead = inputStream.read(dataBuffer);

            if (bytesRead == -1) {
                dataBuffer = new byte[0];
            }
            else {
                dataBuffer = Arrays.copyOf(dataBuffer, bytesRead);
            }

            // Create a data packet from the data buffer and the current block number
            DatagramPacket DATAPacket = constructDataPacket(blockNumber, dataBuffer, receivedPacket.getAddress(), receivedPacket.getPort());
            socket.send(DATAPacket);

            socket.setSoTimeout(timeoutTime);

            System.out.println("Sent DATA packet with block number: " + blockNumber + " and " + bytesRead + " bytes of data");

            if (bytesRead < 512) {
                System.out.println("Finished writing file to server.");
                inputStream.close();
                socket.close();
                break;
            }

            receivedPacket = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(receivedPacket);
            }
            catch (SocketTimeoutException e) {
                System.err.println("Timeout occurred. Retransmitting packet.");
                socket.send(DATAPacket);
            }

            // Check TIDs match and block number is correct
            if (receivedPacket.getPort() == connectionPort) {
                short receivedBlockNumber = parseBlockNumber(receivedPacket);
                if (parseOpcode(receivedPacket) == ACK && receivedBlockNumber == blockNumber) {
                    System.out.println("Success, received ACK packet with block number: " + receivedBlockNumber);
                    blockNumber++;
                }
                else {
                    System.err.println("Received unknown packet or incorrect block number. Disregarding packet and retransmitting DATA packet...");
                    socket.send(DATAPacket);
                }
            }
            else {
                System.err.println("Received packet from unknown port. Disregarding packet.");
            }
        }

    }

    /**
     * Creates a packet following the TFTP request packet format
     * @param opcode The type of request, (Read request or write request)
     * @param fileName The name of the file
     * @param mode The mode of transfer used by TFTP
     * @param address The address to send the packet to
     * @param port The port to send the packet to
     * @return A datagram packet
     */
    private DatagramPacket constructRequestPacket(short opcode, String fileName, String mode, InetAddress address, int port) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        out.writeShort(opcode);
        out.write(fileName.getBytes());
        out.writeByte(0);
        out.write(mode.getBytes());
        out.writeByte(0);
        out.close();

        byte[] buffer = baos.toByteArray();
        return new DatagramPacket(buffer, buffer.length, address, port);
    }

    /**
     * Creates a packet following the TFTP data packet format
     * @param blockNumber The block number of the packet
     * @param data The actual data to send
     * @param address The address to send the packet to
     * @param port The port to send the packet to
     * @return A datagram packet
     */
    private DatagramPacket constructDataPacket(short blockNumber, byte[] data, InetAddress address, int port) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        out.writeShort(DATA);
        out.writeShort(blockNumber);
        out.write(data);
        out.close();

        byte[] buffer = baos.toByteArray();
        return new DatagramPacket(buffer, buffer.length, address, port);
    }

    /**
     * Creates a packet following the TFTP acknowledgement packet format
     * @param blockNumber The block number of the packet
     * @param address The address to send the packet to
     * @param port The port to send the packet to
     * @return A datagram packet
     */
    private DatagramPacket constructACKPacket(short blockNumber, InetAddress address, int port) {
        int ACKPacketSize = 4;
        ByteBuffer byteBuffer = ByteBuffer.allocate(ACKPacketSize);
        byteBuffer.putShort(ACK);
        byteBuffer.putShort(blockNumber);
        return new DatagramPacket(byteBuffer.array(), ACKPacketSize, address, port);
    }

    /**
     * Reads the two byte opcode at the start of all TFTP packets
     * @param packet The TFTP packet
     * @return short value representing the opcode
     */
    private short parseOpcode(DatagramPacket packet) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(packet.getData());
        return byteBuffer.getShort();
    }

    /**
     * Reads the two byte block number in certain TFTP packets
     * @param packet The TFTP packet
     * @return short value representing the opcode
     */
    private short parseBlockNumber(DatagramPacket packet) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(packet.getData());
        return byteBuffer.getShort(2);
    }

    /**
     * Reads the error message string contained in a TFTP error packet
     * @param packet The TFTP packet
     */
    private String parseErrorPacket(DatagramPacket packet) {
        byte[] buffer = packet.getData();

        int delimiter = - 1;

        // start at 4, because we know the first 2 bytes will contain opcode and the second 2 the error message
        for (int i = 4; i < buffer.length; i++) {
            if (buffer[i] == 0) { // loop until it finds first 0
                delimiter = i;
                break;
            }
        }

        return new String(buffer, 4, delimiter - 4);
    }
}