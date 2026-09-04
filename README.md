# ExyliaProxyUtils

The proxy half of [ExyliaLib](https://github.com/DiGround-s/ExyliaLib). One
jar for Velocity and BungeeCord: drop `ExyliaProxyUtils-<version>.jar` in the
proxy's `plugins/` and restart. Nothing to configure.

## What it does

A backend server cannot run `/server`, or anything else the proxy owns.
ExyliaLib sends such requests down a player's connection on the
`exylia:bridge` plugin channel; this plugin answers them. Every request names a
**module** and the module decides what the payload means.

| Module | Payload | Answer |
| --- | --- | --- |
| `ping` | ignored | `OK` with `ExyliaProxyUtils <version> on <Velocity/BungeeCord>`. ExyliaLib sends one through the first player to join and prints it. |
| `connect` | `<server>\|<uuid>\|<memo>`, the last two optional: empty server = the one the request came from, empty uuid = the carrying player | `OK` once connected (or already there); `REJECTED` starting with `no server "<name>"` for a name the proxy does not know, or with the reason the connection was refused. A memo is delivered to the destination server as an `arrive` **push** (an answer frame with id 0) through the moved player once they have joined it. ExyliaLib's cross-server teleports send the destination this way, so no Redis is needed for a player to land next to somebody; without the bridge they fall back to the `BungeeCord` channel and Redis. |
| `player` | a name or a uuid | `OK` with `uuid\|name\|server` for a connected player (server empty while between servers); `REJECTED` otherwise. What lets a backend resolve `/tp <name>` for somebody it has never seen. |
| `players` | ignored | `OK` with every connected name, comma-separated. ExyliaLib asks every 10 s through whoever is online and keeps the answer for tab completion. |
| `commands` | `<actor>:<command>` — `player-proxy:server lobby` runs as the carrying player, `console-proxy:alert hi` as the proxy console (logged) | `OK` if the proxy found and ran the command; `REJECTED` with the command's name if it did not. |

On the Paper side these are the `player-proxy:` and `console-proxy:` prefixes
every Exylia plugin already accepts in its command lists, and
`net.exylia.lib.proxy.Proxy.request(player, module, payload)` for anything else.

## Wire

Both directions on `exylia:bridge`, `DataOutputStream` framing:

```
server -> proxy : UTF module, int id, UTF payload
proxy  -> server: UTF module, int id, byte status, UTF detail
```

Status: `0 OK`, `1 REJECTED`, `2 FAILED`, `3 UNKNOWN_MODULE`. A frame with
id 0 is a **push**: something the proxy tells a server unasked (today only
`arrive`), and ExyliaLib routes it to the handler registered for that module. Every request is
answered, including ones for modules that do not exist and ones whose module
threw; the only thing dropped is bytes that are not a request at all, and
those are logged.

Bytes a *client* writes on the channel never reach a module: both platforms
check the message came from a backend server before routing it, and mark the
channel handled so the client never sees bridge traffic either.

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
both. `Request` carries the payload, the carrying player and the backend server
it came from. A module runs on the proxy's network thread: return a future for
anything slow.

## Layout

```
common/    Bridge, BridgeModule, Platform, Request, Reply, Wire, module/   (no proxy on the classpath; tested)
velocity/  @Plugin main + VelocityPlatform
bungee/    Plugin main + BungeePlatform, bungee.yml
```

`./gradlew buildAll` tests `common` and writes the universal jar to
`/root/Java/jars/plugins/`. Java 21.
