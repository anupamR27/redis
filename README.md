# Redis-Like In-Memory Server

A small, dependency-free Java TCP server that implements a focused subset of Redis. It accepts Redis Serialization Protocol (RESP) commands, keeps key-value data in memory, and works with standard clients such as `redis-cli` for the supported commands.

This is an educational Redis-like server, not a fully compatible Redis implementation.

## Features

- Listens on `127.0.0.1:6379`
- Keeps running and accepts multiple client connections
- Handles multiple commands on each connection
- Supports `PING`, `SET`, and `GET`
- Stores data in a thread-safe in-memory map
- Parses RESP arrays containing bulk strings
- Encodes simple strings, bulk strings, null values, and Redis-style errors
- Validates command names, argument counts, and malformed protocol input
- Uses only the Java standard library

## Architecture

The project intentionally has a small structure:

```text
.
├── README.md
└── src
    ├── Main.java
    ├── RedisServer.java
    └── RespProtocol.java
```

- `Main` configures the host and port and starts the application.
- `RedisServer` accepts clients, stores data, validates commands, and sends responses.
- `RespProtocol` reads the required RESP command format and writes RESP responses.

Each client is handled on its own lightweight server thread. All clients share one `ConcurrentHashMap`, so a value set by one connection can be read by another.

## Requirements

- macOS
- JDK 8 or newer
- Optional: `netcat` (`nc`, included with macOS) and `redis-cli`

Check that Java is installed:

```bash
java -version
javac -version
```

## Compile and Run on macOS

From the repository root:

```bash
mkdir -p out
javac -d out src/*.java
java -cp out Main
```

The server prints:

```text
Redis-like server listening on 127.0.0.1:6379
```

Leave that terminal running. Press `Control-C` to stop the server. Data exists only for the lifetime of the process.

## Test with netcat

Open another terminal in the repository root. These examples send RESP directly and use `sed -n l` to make the response's carriage returns visible.

### PING

```bash
printf '*1\r\n$4\r\nPING\r\n' | nc -w 1 127.0.0.1 6379 | sed -n l
```

Expected response:

```text
+PONG\r$
```

### SET and GET on one connection

```bash
printf '*3\r\n$3\r\nSET\r\n$8\r\ngreeting\r\n$5\r\nhello\r\n*2\r\n$3\r\nGET\r\n$8\r\ngreeting\r\n' | nc -w 1 127.0.0.1 6379 | sed -n l
```

Expected response:

```text
+OK\r$
$5\r$
hello\r$
```

### Invalid command

```bash
printf '*1\r\n$7\r\nINVALID\r\n' | nc -w 1 127.0.0.1 6379 | sed -n l
```

Expected response:

```text
-ERR unknown command 'INVALID'\r$
```

## Test with redis-cli

If Redis CLI is installed, run these commands while the Java server is running:

```bash
redis-cli -h 127.0.0.1 -p 6379 PING
redis-cli -h 127.0.0.1 -p 6379 SET greeting hello
redis-cli -h 127.0.0.1 -p 6379 GET greeting
redis-cli -h 127.0.0.1 -p 6379 INVALID
```

Expected results are `PONG`, `OK`, `hello`, and an `ERR unknown command` error.

To test multiple commands on the same connection, start interactive mode:

```bash
redis-cli -h 127.0.0.1 -p 6379
```

Then enter `PING`, `SET name Ada`, and `GET name` before exiting with `quit`. (`quit` is handled locally by `redis-cli`; it is not a server command.)

## Supported Commands

| Command | Example | Response |
| --- | --- | --- |
| `PING` | `PING` | `PONG` |
| `SET key value` | `SET language Java` | `OK` |
| `GET key` | `GET language` | The stored value, or `(nil)` if absent |

Command names are case-insensitive. Keys and values are handled as UTF-8 strings.

## Limitations

- Data is not persisted and is lost when the server stops.
- Only `PING`, `SET`, and `GET` are implemented.
- Only the RESP array-of-bulk-strings request format used by `redis-cli` is accepted; inline commands are not supported.
- Bulk strings are limited to 1 MiB and commands to 1,024 arguments.
- This project does not implement expiration, replication, transactions, Pub/Sub, streams, clustering, authentication, RDB, or AOF.
- It is designed for local learning and testing, not production use.

## Tech Stack

- Java
- Java standard library networking and I/O
- `ConcurrentHashMap` for in-memory storage
- RESP for client/server communication
