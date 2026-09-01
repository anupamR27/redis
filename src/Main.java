import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

public class Main {
    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(6379);

        System.out.println("Mini Redis server started on port 6379");
        System.out.println("Waiting for a client");

        Socket clientSocket = serverSocket.accept();

        System.out.println("Client connected");

        clientSocket.close();
        serverSocket.close();


        while (true) {
            // Keep the server running for now
        }
    }
}