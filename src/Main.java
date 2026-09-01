import java.io.IOException;
import java.net.ServerSocket;

public class Main {
    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(6379);

        System.out.println("Mini Redis server started on port 6379");

        while (true) {
            // Keep the server running for now
        }
    }
}