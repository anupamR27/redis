import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Main {
    private static final int DEFAULT_PORT = 6379;

    public static void main(String[] args) throws IOException {
        int port = parsePort(args);
        RedisStore store = new RedisStore();

        try (ServerSocket server = new ServerSocket(port)) {
            server.setReuseAddress(true);
            System.out.println("Mini Redis listening on port " + port);

            while (true) {
                Socket client = server.accept();
                new Thread(() -> handleClient(client, store)).start();
            }
        }
    }

    private static int parsePort(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--port")) {
                return Integer.parseInt(args[i + 1]);
            }
        }
        return DEFAULT_PORT;
    }

    private static void handleClient(Socket client, RedisStore store) {
        try (client;
             InputStream in = client.getInputStream();
             OutputStream out = client.getOutputStream()) {

            while (!client.isClosed()) {
                List<String> command = Resp.readCommand(in);
                if (command == null) {
                    break;
                }

                String response = runCommand(command, store);
                out.write(response.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (EOFException ignored) {
            // Client disconnected cleanly.
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }

    private static String runCommand(List<String> command, RedisStore store) {
        if (command.isEmpty()) {
            return Resp.error("ERR empty command");
        }

        String name = command.get(0).toUpperCase(Locale.ROOT);

        return switch (name) {
            case "PING" -> ping(command);
            case "ECHO" -> echo(command);
            case "SET" -> set(command, store);
            case "GET" -> get(command, store);
            default -> Resp.error("ERR unknown command '" + command.get(0) + "'");
        };
    }

    private static String ping(List<String> command) {
        if (command.size() == 1) {
            return Resp.simple("PONG");
        }
        if (command.size() == 2) {
            return Resp.bulk(command.get(1));
        }
        return Resp.error("ERR wrong number of arguments for 'ping' command");
    }

    private static String echo(List<String> command) {
        if (command.size() != 2) {
            return Resp.error("ERR wrong number of arguments for 'echo' command");
        }
        return Resp.bulk(command.get(1));
    }

    private static String set(List<String> command, RedisStore store) {
        if (command.size() != 3 && command.size() != 5) {
            return Resp.error("ERR wrong number of arguments for 'set' command");
        }

        String key = command.get(1);
        String value = command.get(2);
        long ttlMillis = 0;

        if (command.size() == 5) {
            String option = command.get(3).toUpperCase(Locale.ROOT);
            if (!option.equals("PX")) {
                return Resp.error("ERR only PX expiry is supported right now");
            }

            try {
                ttlMillis = Long.parseLong(command.get(4));
            } catch (NumberFormatException e) {
                return Resp.error("ERR PX value is not an integer");
            }

            if (ttlMillis <= 0) {
                return Resp.error("ERR PX value must be positive");
            }
        }

        store.set(key, value, ttlMillis);
        return Resp.simple("OK");
    }

    private static String get(List<String> command, RedisStore store) {
        if (command.size() != 2) {
            return Resp.error("ERR wrong number of arguments for 'get' command");
        }

        String value = store.get(command.get(1));
        if (value == null) {
            return Resp.nullBulk();
        }
        return Resp.bulk(value);
    }

    private static final class RedisStore {
        private final Map<String, StoredValue> data = new ConcurrentHashMap<>();

        void set(String key, String value, long ttlMillis) {
            long expiresAtMillis = ttlMillis == 0
                    ? Long.MAX_VALUE
                    : System.currentTimeMillis() + ttlMillis;
            data.put(key, new StoredValue(value, expiresAtMillis));
        }

        String get(String key) {
            StoredValue stored = data.get(key);
            if (stored == null) {
                return null;
            }

            if (stored.isExpired()) {
                data.remove(key, stored);
                return null;
            }

            return stored.value();
        }
    }

    private record StoredValue(String value, long expiresAtMillis) {
        boolean isExpired() {
            return System.currentTimeMillis() >= expiresAtMillis;
        }
    }

    private static final class Resp {
        private static final String CRLF = "\r\n";

        static List<String> readCommand(InputStream in) throws IOException {
            int firstByte = in.read();
            if (firstByte == -1) {
                return null;
            }

            if (firstByte != '*') {
                return readInlineCommand(firstByte, in);
            }

            int count = Integer.parseInt(readLine(in));
            List<String> command = new ArrayList<>(count);

            for (int i = 0; i < count; i++) {
                int marker = in.read();
                if (marker != '$') {
                    throw new IOException("Expected bulk string marker '$'");
                }

                int length = Integer.parseInt(readLine(in));
                byte[] bytes = in.readNBytes(length);
                if (bytes.length != length) {
                    throw new EOFException();
                }

                expectCrLf(in);
                command.add(new String(bytes, StandardCharsets.UTF_8));
            }

            return command;
        }

        private static List<String> readInlineCommand(int firstByte, InputStream in) throws IOException {
            String line = (char) firstByte + readLine(in);
            String[] parts = line.trim().split("\\s+");
            List<String> command = new ArrayList<>();

            for (String part : parts) {
                if (!part.isEmpty()) {
                    command.add(part);
                }
            }

            return command;
        }

        private static String readLine(InputStream in) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            while (true) {
                int b = in.read();
                if (b == -1) {
                    throw new EOFException();
                }
                if (b == '\r') {
                    int next = in.read();
                    if (next != '\n') {
                        throw new IOException("Expected LF after CR");
                    }
                    break;
                }
                if (b == '\n') {
                    break;
                }
                bytes.write(b);
            }

            return bytes.toString(StandardCharsets.UTF_8);
        }

        private static void expectCrLf(InputStream in) throws IOException {
            int cr = in.read();
            int lf = in.read();
            if (cr != '\r' || lf != '\n') {
                throw new IOException("Expected CRLF");
            }
        }

        static String simple(String value) {
            return "+" + value + CRLF;
        }

        static String error(String message) {
            return "-" + message + CRLF;
        }

        static String bulk(String value) {
            int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
            return "$" + byteLength + CRLF + value + CRLF;
        }

        static String nullBulk() {
            return "$-1" + CRLF;
        }
    }
}
