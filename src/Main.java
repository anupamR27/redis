import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;

public class Main {
    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(6379);

        System.out.println("Mini Redis server started on port 6379");

        while (true) {
            System.out.println("Waiting for a client...");

            Socket clientSocket = serverSocket.accept();

            System.out.println("Client connected");

            handleClient(clientSocket);

            System.out.println("Client disconnected");
        }
    }

    private static void handleClient(Socket clientSocket) throws IOException {
        BufferedReader input = new BufferedReader(
                new InputStreamReader(clientSocket.getInputStream())
        );

        PrintWriter output = new PrintWriter(clientSocket.getOutputStream(), true);

        while (true) {
            String command = input.readLine();

            if (command == null) {
                break;
            }

            if ("PING".equalsIgnoreCase(command)) {
                output.println("PONG");
            } else {
                output.println("Unknown command");
            }
        }

        clientSocket.close();
    }
}