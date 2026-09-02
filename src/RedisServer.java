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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accepts TCP clients and executes the supported Redis commands.
 */
public final class RedisServer {
    private static final long NO_EXPIRATION = 0L;

    private final String host;
    private final int port;
    private final ConcurrentMap<String, StoredValue> data = new ConcurrentHashMap<>();
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
            case "DEL":
                executeDel(arguments, output);
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
        if (arguments.size() != 3 && arguments.size() != 5) {
            writeWrongArgumentCount(output, "set");
            return;
        }

        long expiresAtMillis = NO_EXPIRATION;
        if (arguments.size() == 5) {
            if (!"PX".equalsIgnoreCase(arguments.get(3))) {
                RespProtocol.writeError(output, "ERR syntax error");
                return;
            }

            Long expiration = parseExpiration(arguments.get(4), output);
            if (expiration == null) {
                return;
            }
            expiresAtMillis = expiration;
        }

        data.put(
                arguments.get(1),
                new StoredValue(arguments.get(2), expiresAtMillis)
        );
        RespProtocol.writeSimpleString(output, "OK");
    }

    private void executeGet(List<String> arguments, OutputStream output) throws IOException {
        if (arguments.size() != 2) {
            writeWrongArgumentCount(output, "get");
            return;
        }

        StoredValue storedValue = getLiveValue(arguments.get(1));
        if (storedValue == null) {
            RespProtocol.writeNullBulkString(output);
        } else {
            RespProtocol.writeBulkString(output, storedValue.getValue());
        }
    }

    private void executeDel(List<String> arguments, OutputStream output) throws IOException {
        if (arguments.size() < 2) {
            writeWrongArgumentCount(output, "del");
            return;
        }

        long deletedCount = 0;
        for (int index = 1; index < arguments.size(); index++) {
            if (deleteLiveValue(arguments.get(index))) {
                deletedCount++;
            }
        }

        RespProtocol.writeInteger(output, deletedCount);
    }

    private Long parseExpiration(String millisecondsText, OutputStream output)
            throws IOException {
        final long milliseconds;

        try {
            milliseconds = Long.parseLong(millisecondsText);
        } catch (NumberFormatException exception) {
            RespProtocol.writeError(output, "ERR value is not an integer or out of range");
            return null;
        }

        long now = System.currentTimeMillis();
        if (milliseconds <= 0 || milliseconds > Long.MAX_VALUE - now) {
            RespProtocol.writeError(output, "ERR invalid expire time in 'set' command");
            return null;
        }

        return now + milliseconds;
    }

    private StoredValue getLiveValue(String key) {
        while (true) {
            StoredValue storedValue = data.get(key);
            if (storedValue == null) {
                return null;
            }
            if (!storedValue.isExpired(System.currentTimeMillis())) {
                return storedValue;
            }

            // Remove only the expired value observed above. If another client
            // replaced it meanwhile, retry without deleting the newer value.
            if (data.remove(key, storedValue)) {
                return null;
            }
        }
    }

    private boolean deleteLiveValue(String key) {
        while (true) {
            StoredValue storedValue = data.get(key);
            if (storedValue == null) {
                return false;
            }

            boolean expired = storedValue.isExpired(System.currentTimeMillis());
            if (data.remove(key, storedValue)) {
                return !expired;
            }
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

    /**
     * Keeps a value and its optional expiration together as one atomic map entry.
     */
    private static final class StoredValue {
        private final String value;
        private final long expiresAtMillis;

        private StoredValue(String value, long expiresAtMillis) {
            this.value = value;
            this.expiresAtMillis = expiresAtMillis;
        }

        private String getValue() {
            return value;
        }

        private boolean isExpired(long nowMillis) {
            return expiresAtMillis != NO_EXPIRATION && nowMillis >= expiresAtMillis;
        }
    }
}
