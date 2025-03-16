import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.InputMismatchException;
import java.util.Random;
import java.util.Scanner;

public class Client {
    private final DatagramSocket socket;
    private InetAddress address;
    private byte[] buffer = new byte[516];

    private final int serverPort = 69;

    private final short RRQ = 1;
    private final short WRQ = 2;
    private final short DATA = 3;
    private final short ACK = 4;
    private final short ERROR = 5;

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
        System.out.println("Type (1) to retrieve a file, type (2) to store a file: ");

        Scanner scanner = new Scanner(System.in);
        while (scanner.hasNext()) {
            try {
                request = scanner.nextShort();
            }
            catch (InputMismatchException e) {
                System.out.println("Please enter (1) or (2): ");
                scanner.next();
                continue;
            }

            if (request == RRQ || request == WRQ) {
                break;
            }
            else {
                System.out.println("Please enter (1) or (2): ");
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

    public void sendRequest() throws IOException {
        getUserRequest();

        // Create a request packet and send it to the server
        DatagramPacket requestPacket = constructRequestPacket(request, fileName, "octet", address, serverPort);
        socket.send(requestPacket);
        System.out.println("Sent request packet to server at port: " + serverPort);

        // Wait for server to send a packet back
        DatagramPacket receivedPacket = new DatagramPacket(buffer, buffer.length);
        socket.receive(receivedPacket);

        // Check TIDs match
        if (receivedPacket.getPort() == serverPort) {
            short receivedOpcode = parseOpcode(receivedPacket);
            System.out.println("Received opcode " + receivedOpcode + " from server at port: " + receivedPacket.getPort());

            File file = new File(path + fileName);

            if (request == RRQ) {
                if (receivedOpcode == DATA) {
                    System.out.println("Connection established");

                    short blockNumber = 1;
                    short receivedBlockNumber = parseBlockNumber(receivedPacket);

                    DataOutputStream outputStream = new DataOutputStream(new FileOutputStream(file));

                    // Write the first data packet that established a connection to the file
                    outputStream.write(receivedPacket.getData(), 4, receivedPacket.getLength() - 4);

                    // Then send and received the rest in a loop
                    while (receivedPacket.getLength() >= 512) {
                        // Send ACK packet to server with the same block number as the data packet
                        DatagramPacket ACKPacket = constructACKPacket(receivedBlockNumber, requestPacket.getAddress(), requestPacket.getPort());
                        socket.send(ACKPacket);
                        System.out.println("Sent ACK packet with block number: " + receivedBlockNumber);

                        socket.receive(receivedPacket);

                        receivedOpcode = parseOpcode(receivedPacket);
                        receivedBlockNumber = parseBlockNumber(receivedPacket);
                        if (receivedOpcode == DATA && receivedBlockNumber == blockNumber + 1) {
                            System.out.println("Success, received DATA packet with block number: " + receivedBlockNumber);
                            outputStream.write(receivedPacket.getData(), 4, receivedPacket.getLength() - 4);
                            blockNumber = receivedBlockNumber;
                        }
                        else {
                            System.err.println("Received block number incorrect, duplicate packet?");
                        }
                    }

                    System.out.println("Finished reading file from server.");
                    outputStream.close();
                    close();
                }
                else if (receivedOpcode == ERROR) {
                    // TODO handle this error, which would be file not found error because thats the only error I have to handle
                }
            }
            else if (request == WRQ) {
                if (receivedOpcode == ACK) {
                    System.out.println("Connection established");

                    FileInputStream inputStream;
                    try {
                        inputStream = new FileInputStream(file);
                    } catch (FileNotFoundException e) {
                        System.err.println("Requested file not found. Sending error packet.");
                        // TODO construct and send error packet
                        return;
                    }

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
                        DatagramPacket DATAPacket = constructDataPacket(blockNumber, dataBuffer, receivedPacket.getAddress(), requestPacket.getPort());
                        socket.send(DATAPacket);

                        System.out.println("Sent DATA packet with block number: " + blockNumber + " and " + bytesRead + " bytes of data");

                        if (bytesRead < 512) {
                            System.out.println("Finished writing file to server.");
                            inputStream.close();
                            close();
                            break;
                        }

                        receivedPacket = new DatagramPacket(buffer, buffer.length);
                        socket.receive(receivedPacket);

                        // check opcode and block number of received packet
                        receivedOpcode = parseOpcode(receivedPacket);
                        short receivedBlockNumber = parseBlockNumber(receivedPacket);
                        if (receivedOpcode == ACK && receivedBlockNumber == blockNumber) {
                            System.out.println("Success, received ACK packet with block number: " + receivedBlockNumber);
                            blockNumber++;
                        }
                        else {
                            System.err.println("Received unknown packet. Disregarding packet.");
                        }
                    }
                }
                else {
                    System.err.println("Received unknown packet. Disregarding packet.");
                    // TODO send another error?
                }
            }
        }
        else {
            System.err.println("Received unknown packet. Disregarding packet.");
        }
    }

    /**
     * Creates a packet following the TFTP request packet format
     * @param operation The type of request, (Read request or write request)
     * @param fileName The name of the file
     * @param mode The mode of transfer used by TFTP
     * @param address The address to send the packet to
     * @param port The port to send the packet to
     * @return A datagram packet
     */
    private DatagramPacket constructRequestPacket(short operation, String fileName, String mode, InetAddress address, int port) throws IOException {
        byte[] buffer;
        byte[] opcode = new byte[2];
        opcode[0] = (byte)((operation >> 8));
        opcode[1] = (byte) operation;

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        out.write(opcode);
        out.write(fileName.getBytes());
        out.write(0);
        out.write(mode.getBytes());
        out.write(0);
        buffer = out.toByteArray();
        out.close();

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

    public void close() {
        socket.close();
    }
}

