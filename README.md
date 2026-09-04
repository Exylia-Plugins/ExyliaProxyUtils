# ExyliaProxyUtils

The proxy half of [ExyliaLib](https://github.com/DiGround-s/ExyliaLib). One
jar for Velocity and BungeeCord: drop `ExyliaProxyUtils-<version>.jar` in the
proxy's `plugins/`, start once, fill in `plugins/ExyliaProxyUtils/config.properties`
with the network's Redis, restart.

```properties
redis.host=10.0.0.6
redis.port=6379
redis.password=
redis.database=0
redis.key-prefix=exylia
```

The same values every backend has under `database.redis` in
`plugins/ExyliaLib/database.yml`, with the same `key-prefix`. **Server names
on the proxy must equal the backends' `server-id`s**: that is the address an
answer goes back to, and the name a `connect` moves a player to.

## Why Redis and not plugin messages

A plugin message travels down a player's connection, and a modified client
can write one. A bridge built on them has to trust the proxy to filter the
client's bytes out before they reach a backend — one missed check and a
player is teleporting themselves anywhere or running proxy console commands.
Redis is on the network's own side of the wall: only a backend can publish
there, and it works with nobody online at all. Versions 1.x used plugin
messages; 2.0.0 does not.

## What it does

A backend server cannot run `/server`, find a player on another server, or
land a teleport on the other side. ExyliaLib asks over Redis; this plugin
answers. Every request names a **module** and the module decides what the
payload means.

| Module | Payload | Answer |
| --- | --- | --- |
| `ping` | ignored | `OK` with `ExyliaProxyUtils <version> on <Velocity/BungeeCord>`. ExyliaLib sends one at startup and prints it. |
| `commands` | `<actor>:<command>` — `player-proxy:server lobby` runs as the player the request is about, `console-proxy:alert hi` as the proxy console (logged) | `OK` if the proxy found and ran the command; `REJECTED` with the command's name if it did not. |
| `connect` | `<server>\|<uuid>\|<memo>`, the last two optional: empty server = the one asking, empty uuid = the player the request is about | `OK` once connected (or already there); `REJECTED` starting with `no server "<name>"` for a name the proxy does not know, or with the reason the connection was refused. A memo is sent to the destination server as an `arrive` **push** once the proxy has connected the player; ExyliaLib's cross-server teleports send the destination this way. |
| `player` | a name or a uuid | `OK` with `uuid\|name\|server` for a connected player (server empty while between servers); `REJECTED` otherwise. |
| `players` | ignored | `OK` with every connected name, comma-separated. ExyliaLib asks every 10 s and keeps the answer for tab completion. |

On the Paper side these are the `player-proxy:` and `console-proxy:` prefixes
every Exylia plugin already accepts in its command lists, the cross-server
teleports, and `net.exylia.lib.proxy.Proxy.request(module, payload)` for
anything else.

## Wire

```
backend -> proxy   on <prefix>:bridge:proxy       : <server-id>|<uuid or empty>|<module>|<id>|<payload>
proxy   -> backend on <prefix>:bridge:<server-id> : <module>|<id>|<status>|<uuid or empty>|<detail>
```

Status: `0 OK`, `1 REJECTED`, `2 FAILED`, `3 UNKNOWN_MODULE`. Payload and
detail come last and may contain pipes. A frame with id 0 is a **push**:
something the proxy tells a server unasked (today only `arrive`). Every
request is answered, including ones for modules that do not exist and ones
whose module threw; the only thing dropped is text that is not a request at
all, and that is logged.

## Adding a module

One class in `common`, one line in `Bridge.withBuiltIns`:

```java
public final class ServersModule implements BridgeModule {
    @Override public String name() { return "servers"; }
    @Override public CompletableFuture<Reply> handle(Request request) {
        return CompletableFuture.completedFuture(Reply.ok(String.join(",", names())));
    }
}
```

Modules see the proxy only through `Platform`, so a module written once runs on
both. `Request` carries the payload, the player it is about (if any) and the
backend it came from. A module that wants to tell a server something later
takes the `Publisher`. A module runs on the Redis subscriber thread: return a
future for anything slow.

## Layout

```
common/    Bridge, BridgeModule, Platform, Publisher, Request, Reply, Frames, RedisBridge, BridgeConfig, module/
velocity/  @Plugin main + VelocityPlatform
bungee/    Plugin main + BungeePlatform, bungee.yml
```

`./gradlew buildAll` tests `common` and writes the universal jar (Jedis
shaded and relocated) to `/root/Java/jars/plugins/`. Java 21.
