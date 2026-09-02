# Redis-Like In-Memory Server

A small, dependency-free Java TCP server that implements a focused subset of Redis. It accepts Redis Serialization Protocol (RESP) commands and keeps key-value data in memory, with optional millisecond expiration.

This is an educational Redis-like server, not a fully compatible Redis implementation.

## Features

- Listens on `127.0.0.1:6379`
- Accepts multiple concurrent client connections
- Handles multiple commands per connection
- Supports `PING`, `SET`, `GET`, and `DEL`
- Supports optional millisecond expiration with `SET key value PX milliseconds`
- Stores values and expiration metadata in a thread-safe `ConcurrentHashMap`
- Removes expired keys lazily when they are accessed or deleted
- Parses RESP arrays containing bulk strings
- Encodes RESP simple strings, bulk strings, null bulk strings, integers, and errors
- Validates commands, argument counts, PX values, and malformed protocol input
- Uses only the Java standard library

## Architecture

```text
Client (redis-cli or netcat)
             |
             | TCP + RESP
             v
        RedisServer
        /         \
       v           v
Command handling  RespProtocol
       |
       v
ConcurrentHashMap<String, StoredValue>
       |
       +-- value
       +-- optional expiration timestamp
```

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
- `RedisServer` accepts clients, manages shared storage and expiration, validates commands, and sends responses.
- `RespProtocol` reads the required RESP request format and writes RESP responses.

Each client is handled independently. Values and their optional expiration timestamps are kept together in immutable map entries, which prevents the value and TTL from becoming inconsistent during concurrent updates.

## Requirements

- macOS, Linux, or Windows
- JDK 8 or newer
- Optional: `netcat` (`nc`) and `redis-cli`

Check that Java is installed:

```bash
java -version
javac -version
```

## Compile and Run

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

Leave that terminal running while testing. Press `Control-C` to stop the server. Data exists only for the lifetime of the process.

## Supported Commands

| Command | Example | Response |
| --- | --- | --- |
| `PING` | `PING` | `PONG` |
| `SET key value` | `SET language Java` | `OK` |
| `SET key value PX milliseconds` | `SET session abc123 PX 5000` | `OK` |
| `GET key` | `GET language` | The stored value, or `(nil)` if absent or expired |
| `DEL key [key ...]` | `DEL language session` | Number of keys actually deleted |

Command names and the `PX` option are case-insensitive.

### Expiration behavior

- `PX` accepts only a positive integer number of milliseconds.
- Expired keys behave as if they do not exist for both `GET` and `DEL`.
- Setting a key again without `PX` removes its previous expiration.
- Setting a key again with `PX` replaces its previous expiration.
- Expiration is lazy: no background cleanup thread is used. An expired entry is removed when `GET` or `DEL` accesses it, or when a later `SET` replaces it.

## Test with redis-cli

If Redis CLI is installed, start the Java server and run these commands in another terminal:

```bash
redis-cli -h 127.0.0.1 -p 6379 PING
redis-cli -h 127.0.0.1 -p 6379 SET greeting hello
redis-cli -h 127.0.0.1 -p 6379 GET greeting
redis-cli -h 127.0.0.1 -p 6379 DEL greeting
redis-cli -h 127.0.0.1 -p 6379 GET greeting
```

Expiration example:

```bash
redis-cli -h 127.0.0.1 -p 6379 SET session abc123 PX 1000
redis-cli -h 127.0.0.1 -p 6379 GET session
sleep 2
redis-cli -h 127.0.0.1 -p 6379 GET session
```

The first `GET` returns `abc123`; the second returns `(nil)`.

Multiple-key deletion:

```bash
redis-cli -h 127.0.0.1 -p 6379 SET first one
redis-cli -h 127.0.0.1 -p 6379 SET second two
redis-cli -h 127.0.0.1 -p 6379 DEL first second missing
```

The final command returns `(integer) 2`.

Invalid expiration examples:

```bash
redis-cli -h 127.0.0.1 -p 6379 SET key value PX nope
redis-cli -h 127.0.0.1 -p 6379 SET key value PX 0
redis-cli -h 127.0.0.1 -p 6379 SET key value PX -10
```

Each command returns a Redis-style error.

## Test with netcat

These commands send RESP directly. `sed -n l` makes carriage returns visible in the output.

### PING

```bash
printf '*1\r\n$4\r\nPING\r\n' | nc -w 1 127.0.0.1 6379 | sed -n l
```

Expected response: `+PONG\r$`

### SET, GET, and DEL

```bash
printf '*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$3\r\nAda\r\n*2\r\n$3\r\nGET\r\n$4\r\nname\r\n*2\r\n$3\r\nDEL\r\n$4\r\nname\r\n*2\r\n$3\r\nGET\r\n$4\r\nname\r\n' | nc -w 1 127.0.0.1 6379 | sed -n l
```

Expected response:

```text
+OK\r$
$3\r$
Ada\r$
:1\r$
$-1\r$
```

### SET with PX

```bash
printf '*5\r\n$3\r\nSET\r\n$7\r\nsession\r\n$6\r\nabc123\r\n$2\r\nPX\r\n$4\r\n1000\r\n' | nc -w 1 127.0.0.1 6379 | sed -n l
sleep 2
printf '*2\r\n$3\r\nGET\r\n$7\r\nsession\r\n' | nc -w 1 127.0.0.1 6379 | sed -n l
```

Expected responses are `+OK\r$` followed by `$-1\r$`.

## Limitations

- Data is not persisted and is lost when the server stops.
- Only `PING`, `SET`, `GET`, and `DEL` are implemented.
- `SET` supports only the optional `PX milliseconds` form; `EX`, `NX`, `XX`, and other options are not implemented.
- Expiration uses lazy cleanup rather than a background cleanup process.
- Only RESP arrays containing bulk strings are accepted as requests; inline commands are not supported.
- Keys and values are handled as UTF-8 strings rather than arbitrary binary data.
- Bulk strings are limited to 1 MiB and commands to 1,024 arguments.
- Replication, transactions, Pub/Sub, streams, clustering, authentication, RDB, and AOF are not implemented.
- This project is designed for local learning and testing, not production use.

## Tech Stack

- Java
- Java standard library networking and I/O
- `ConcurrentHashMap` for in-memory storage
- RESP for client/server communication
