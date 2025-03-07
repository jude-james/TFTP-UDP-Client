import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;

public class Client extends Thread {
    private DatagramSocket socket;
    private InetAddress address;
    private byte[] buffer;

    public int serverPort = 69;

    public static void main(String[] args) throws IOException {
        Client client = new Client();
        client.sendToServer((short) 1, "bobby", "octet");
        // mode will always be octet
    }

    public Client() throws SocketException, UnknownHostException {
        socket = new DatagramSocket(4000);
        address = InetAddress.getByName("localhost");
    }

    public void sendToServer(short operation, String fileName, String mode) throws IOException {
        // short opcode = Short.parseShort(operation);
        byte[] opcode = new byte[2];
        opcode[0] = (byte)((operation >> 8));
        opcode[1] = (byte) operation;

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // put the opcode at the start of the buffer
        out.write(opcode);

        // put the fileName next
        out.write(fileName.getBytes());

        // put one byte (0)
        out.write(0);

        // put the mode
        out.write(mode.getBytes());

        // put one byte (0)
        out.write(0);

        byte[] buffer = out.toByteArray();
        out.close();

        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, address, serverPort);
        socket.send(packet);

        socket.receive(packet);

        String received = new String(packet.getData(), 0, packet.getLength());
        System.out.println(received);
    }

    public void close() {
        socket.close();
    }
}
