import java.io.IOException;

/**
 * Application entry point for the minimal Redis-like server.
 */
public final class Main {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6379;

    private Main() {
        // This class is not meant to be instantiated.
    }

    public static void main(String[] args) {
        RedisServer server = new RedisServer(HOST, PORT);

        try {
            server.start();
        } catch (IOException exception) {
            System.err.println("Server stopped: " + exception.getMessage());
        }
    }
}
