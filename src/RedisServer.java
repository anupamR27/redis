import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accepts TCP clients and executes the supported Redis commands.
 */
public final class RedisServer {
    private final String host;
    private final int port;
    private final Map<String, String> data = new ConcurrentHashMap<>();
    private final ExecutorService clientExecutor = Executors.newCachedThreadPool(
            new ClientThreadFactory()
    );

    public RedisServer(String host, int port) {
        this.host = host;
        this.port = port;
    }

    /**
     * Starts the server and keeps accepting connections until the process stops.
     */
    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(host, port));
            System.out.println("Redis-like server listening on " + host + ":" + port);

            while (!serverSocket.isClosed()) {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                clientExecutor.execute(() -> handleClient(clientSocket));
            }
        } finally {
            clientExecutor.shutdown();
        }
    }

    private void handleClient(Socket clientSocket) {
        try (Socket socket = clientSocket;
             InputStream input = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(socket.getOutputStream())) {

            while (true) {
                List<String> arguments;

                try {
                    arguments = RespProtocol.readCommand(input);
                } catch (RespProtocol.ProtocolException exception) {
                    RespProtocol.writeError(
                            output,
                            "ERR Protocol error: " + exception.getMessage()
                    );
                    output.flush();
                    return;
                }

                if (arguments == null) {
                    return;
                }

                execute(arguments, output);
                output.flush();
            }
        } catch (IOException exception) {
            // A client may disconnect without sending another command.
            System.err.println("Client connection closed: " + exception.getMessage());
        }
    }

    private void execute(List<String> arguments, OutputStream output) throws IOException {
        if (arguments.isEmpty()) {
            RespProtocol.writeError(output, "ERR empty command");
            return;
        }

        String command = arguments.get(0).toUpperCase(Locale.ROOT);

        switch (command) {
            case "PING":
                executePing(arguments, output);
                break;
            case "SET":
                executeSet(arguments, output);
                break;
            case "GET":
                executeGet(arguments, output);
                break;
            default:
                RespProtocol.writeError(
                        output,
                        "ERR unknown command '" + arguments.get(0) + "'"
                );
        }
    }

    private void executePing(List<String> arguments, OutputStream output) throws IOException {
        if (arguments.size() != 1) {
            writeWrongArgumentCount(output, "ping");
            return;
        }

        RespProtocol.writeSimpleString(output, "PONG");
    }

    private void executeSet(List<String> arguments, OutputStream output) throws IOException {
        if (arguments.size() != 3) {
            writeWrongArgumentCount(output, "set");
            return;
        }

        data.put(arguments.get(1), arguments.get(2));
        RespProtocol.writeSimpleString(output, "OK");
    }

    private void executeGet(List<String> arguments, OutputStream output) throws IOException {
        if (arguments.size() != 2) {
            writeWrongArgumentCount(output, "get");
            return;
        }

        String value = data.get(arguments.get(1));
        if (value == null) {
            RespProtocol.writeNullBulkString(output);
        } else {
            RespProtocol.writeBulkString(output, value);
        }
    }

    private void writeWrongArgumentCount(OutputStream output, String command) throws IOException {
        RespProtocol.writeError(
                output,
                "ERR wrong number of arguments for '" + command + "' command"
        );
    }

    private static final class ClientThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "redis-client-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
