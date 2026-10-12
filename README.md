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
updates.enabled=true
```

The same values every backend has under `database.redis` in
`plugins/ExyliaLib/database.yml`, with the same `key-prefix`. **Server names
on the proxy must equal the backends' `server-id`s**: that is the address an
answer goes back to, and the name a `connect` moves a player to.

## Updates

Every push to `main` with a new version in `build.gradle` publishes a GitHub
release. The proxy checks for one when it starts and when it stops: a newer
release of the same major replaces the jar in `plugins/` and loads on the
next restart. A new major version is only logged, to install by hand.
`updates.enabled=false` turns this off.

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
| `kick` | `<uuid>\|<mini>\|<legacy>`, split on the first two pipes (the legacy screen may hold more) | `OK` once the player was disconnected, with the MiniMessage screen on Velocity and the §-coded one on BungeeCord; `REJECTED not connected` otherwise. ExyliaStaff kicks and bans players on other servers this way. |
| `punish-sources` | ignored | `OK` with the ban plugins installed on this proxy, comma-separated origins (`LITEBANS,LIBERTYBANS`), empty for none. |
| `punish-import` | an origin: `LITEBANS`, `LIBERTYBANS`, `ADVANCEDBAN` or `BANMANAGER` | `OK started <ORIGIN>`, and that plugin's whole history goes into the punishment stream on a thread of its own, with `import:true`, logging its progress every 1000 entries and the total at the end; `REJECTED <ORIGIN> not installed` (or `... import already running`). |

On the Paper side these are the `player-proxy:` and `console-proxy:` prefixes
every Exylia plugin already accepts in its command lists, the cross-server
teleports, and `net.exylia.lib.proxy.Proxy.request(module, payload)` for
anything else.

## Punishments

ExyliaStaff owns punishments; the proxy carries the part only the proxy can
see, over the same Redis and `key-prefix`.

### Ban plugins on the proxy

When one of these is installed on the proxy, every punishment made through it
(a plain `/ban` included, and every removal) is written to the stream below.
Each hook checks the plugin is there before touching any of its classes, so
none is needed; they are soft dependencies, loaded first when present.

| Plugin | Velocity | BungeeCord | `originId` |
| --- | --- | --- | --- |
| LiteBans | yes | yes | `ban:<id>`, `mute:<id>`, `warn:<id>`, `kick:<id>` |
| LibertyBans | yes | yes | `<id>` |
| AdvancedBan | no build exists | yes | `<id>`; a live kick (AdvancedBan gives kicks no id) `kick:<uuid>:<start>`; an import's history-only entry `history:<id>` |
| BanManager | yes | yes | `ban:<id>`, `mute:<id>`, `warn:<id>`, `kick:<id>`; an import's lifted bans and mutes `banrecord:<id>`, `muterecord:<id>` |

Entries that name no player (address-only bans) are skipped: the stream's
`target` is always a uuid. Names the plugin does not keep (LibertyBans keeps
none) are filled from whoever is online, else null.

### Stream `<prefix>:punish:external`

`XADD <prefix>:punish:external MAXLEN ~ 200000 * json <json>`, read by
ExyliaStaff with the consumer group `exyliastaff`. Live entries go through one
writer thread, so a ban plugin's thread never waits on Redis and a removal
never overtakes its punishment; a write is tried three times, and one that
still fails is logged with its JSON.

```json
{"v":1,"kind":"added","origin":"LITEBANS","originId":"ban:123","type":"TEMPBAN",
 "target":"<uuid>","targetName":"Steve","staff":null,"staffName":"Console",
 "reason":"max 255","server":"proxy","created":1730000000000,"expires":1730086400000,
 "removedAt":0,"removedBy":null,"removeReason":null,"import":false}
```

`kind` is `added` or `removed`; `type` one of `BAN TEMPBAN IPBAN MUTE TEMPMUTE
WARN KICK`; times are epoch ms, 0 for never; `server` is the plugin's own
origin server when it keeps one, else `proxy`. An import writes an active
punishment as `added` and a lifted or expired one as `added` then `removed`.

### Native ban mirror

ExyliaStaff keeps its own active bans in Redis:
`<prefix>:punish:ban:<uuid lowercase>` and
`<prefix>:punish:ipban:<sha256 hex of the address as text, e.g. "1.2.3.4">`,
each `{"id":123,"type":"BAN","expires":0,"mini":"...","legacy":"..."}`. At
login (Velocity `LoginEvent`, BungeeCord `LoginEvent`, both off the network
thread) the proxy reads the uuid key, then the address key, and turns the
player away with `mini` (Velocity) or `legacy` (BungeeCord) unless `expires`
has passed. Redis down lets the player in, with a warning at most once a
minute: the backends check their own database too.

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
           punish/: the stream, the ban mirror, LiteBans, LibertyBans and BanManager (shared part)
velocity/  @Plugin main + VelocityPlatform, BanManager's Velocity events
bungee/    Plugin main + BungeePlatform, AdvancedBan, BanManager's BungeeCord events, bungee.yml
```

`./gradlew buildAll` tests `common` and writes the universal jar (Jedis
shaded and relocated) to `/root/Java/jars/plugins/`. Java 21.
