import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements the small RESP subset needed for arrays of bulk-string commands.
 */
public final class RespProtocol {
    private static final int MAX_ARGUMENTS = 1_024;
    private static final int MAX_BULK_STRING_LENGTH = 1_048_576;
    private static final int MAX_LENGTH_LINE_SIZE = 64;
    private static final byte[] CRLF = {'\r', '\n'};

    private RespProtocol() {
        // Utility class.
    }

    /**
     * Reads one RESP array containing bulk strings. Returns null at a clean EOF.
     */
    public static List<String> readCommand(InputStream input)
            throws IOException, ProtocolException {
        int firstByte = input.read();
        if (firstByte == -1) {
            return null;
        }
        if (firstByte != '*') {
            throw new ProtocolException("expected an array");
        }

        int argumentCount = readLength(input, "array length");
        if (argumentCount < 0) {
            throw new ProtocolException("array length cannot be negative");
        }
        if (argumentCount > MAX_ARGUMENTS) {
            throw new ProtocolException("too many command arguments");
        }

        List<String> arguments = new ArrayList<>(argumentCount);
        for (int index = 0; index < argumentCount; index++) {
            int typeByte = input.read();
            if (typeByte != '$') {
                throw new ProtocolException("expected a bulk string");
            }

            int byteLength = readLength(input, "bulk string length");
            if (byteLength < 0) {
                throw new ProtocolException("null bulk strings are not supported in commands");
            }
            if (byteLength > MAX_BULK_STRING_LENGTH) {
                throw new ProtocolException("bulk string is too large");
            }

            byte[] value = readExactly(input, byteLength);
            expectCrlf(input);
            arguments.add(new String(value, StandardCharsets.UTF_8));
        }

        return arguments;
    }

    public static void writeSimpleString(OutputStream output, String value) throws IOException {
        writeAscii(output, "+" + sanitizeLine(value));
        output.write(CRLF);
    }

    public static void writeError(OutputStream output, String message) throws IOException {
        writeAscii(output, "-" + sanitizeLine(message));
        output.write(CRLF);
    }

    public static void writeBulkString(OutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeAscii(output, "$" + bytes.length);
        output.write(CRLF);
        output.write(bytes);
        output.write(CRLF);
    }

    public static void writeNullBulkString(OutputStream output) throws IOException {
        writeAscii(output, "$-1");
        output.write(CRLF);
    }

    public static void writeInteger(OutputStream output, long value) throws IOException {
        writeAscii(output, ":" + value);
        output.write(CRLF);
    }

    private static int readLength(InputStream input, String description)
            throws IOException, ProtocolException {
        String line = readLine(input);

        try {
            return Integer.parseInt(line);
        } catch (NumberFormatException exception) {
            throw new ProtocolException("invalid " + description);
        }
    }

    private static String readLine(InputStream input) throws IOException, ProtocolException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();

        while (line.size() <= MAX_LENGTH_LINE_SIZE) {
            int nextByte = input.read();
            if (nextByte == -1) {
                throw new ProtocolException("unexpected end of input");
            }
            if (nextByte == '\r') {
                if (input.read() != '\n') {
                    throw new ProtocolException("expected LF after CR");
                }
                return new String(line.toByteArray(), StandardCharsets.US_ASCII);
            }
            if (nextByte == '\n') {
                throw new ProtocolException("expected CR before LF");
            }

            line.write(nextByte);
        }

        throw new ProtocolException("length line is too long");
    }

    private static byte[] readExactly(InputStream input, int byteCount)
            throws IOException, ProtocolException {
        byte[] bytes = new byte[byteCount];
        int offset = 0;

        while (offset < byteCount) {
            int bytesRead = input.read(bytes, offset, byteCount - offset);
            if (bytesRead == -1) {
                throw new ProtocolException("unexpected end of bulk string");
            }
            offset += bytesRead;
        }

        return bytes;
    }

    private static void expectCrlf(InputStream input) throws IOException, ProtocolException {
        if (input.read() != '\r' || input.read() != '\n') {
            throw new ProtocolException("expected CRLF after bulk string");
        }
    }

    private static String sanitizeLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }

    private static void writeAscii(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.US_ASCII));
    }

    public static final class ProtocolException extends Exception {
        private static final long serialVersionUID = 1L;

        public ProtocolException(String message) {
            super(message);
        }
    }
}
