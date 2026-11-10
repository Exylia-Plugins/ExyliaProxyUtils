package net.exylia.proxyutils.wire;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import net.exylia.proxyutils.Platform;
import org.bson.Document;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The database wire on MongoDB: ExyliaLib's {@code MongoBus} collections,
 * numbered by the same {@code exylia_bus_counters} documents.
 */
final class MongoWire extends PollingWire {

    private static final String COUNTERS = "exylia_bus_counters";

    private final MongoClient client;
    private final MongoCollection<Document> counters;
    private final MongoCollection<Document> messages;
    private final MongoCollection<Document> keys;
    private final MongoCollection<Document> entries;
    private final String describe;

    private MongoWire(Platform platform, MongoClient client, MongoDatabase database, String describe) {
        super(platform);
        this.client = client;
        this.counters = database.getCollection(COUNTERS);
        this.messages = database.getCollection(MESSAGES);
        this.keys = database.getCollection(KEYS);
        this.entries = database.getCollection(ENTRIES);
        this.describe = describe;
    }

    static @NotNull MongoWire open(@NotNull Platform platform, @NotNull DatabaseTarget target) {
        MongoClientSettings.Builder builder = MongoClientSettings.builder()
                .applicationName("exylia-proxyutils")
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(5, TimeUnit.SECONDS));
        String name = target.name();
        String where;
        if (!target.connectionString().isBlank()) {
            ConnectionString uri = new ConnectionString(target.connectionString());
            builder.applyConnectionString(uri);
            if (uri.getDatabase() != null && !uri.getDatabase().isBlank()) {
                name = uri.getDatabase();
            }
            where = "mongodb " + String.join(",", uri.getHosts()) + '/' + name;
        } else {
            builder.applyToClusterSettings(cluster -> cluster.hosts(
                    List.of(new ServerAddress(target.host(), target.portOr(27017)))));
            if (!target.username().isBlank()) {
                builder.credential(MongoCredential.createCredential(target.username(), target.name(),
                        target.password().toCharArray()));
            }
            where = "mongodb " + target.host() + ':' + target.portOr(27017) + '/' + name;
        }
        MongoClient client = MongoClients.create(builder.build());
        MongoDatabase database = client.getDatabase(name);
        try {
            database.runCommand(new Document("ping", 1));
        } catch (RuntimeException unreachable) {
            client.close();
            throw unreachable;
        }
        return new MongoWire(platform, client, database, where);
    }

    private long next(String counter) {
        Document updated = counters.findOneAndUpdate(Filters.eq("_id", counter), Updates.inc("seq", 1L),
                new FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER));
        return number(updated, "seq");
    }

    private static long number(@Nullable Document document, String field) {
        Object value = document == null ? null : document.get(field);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    @Override
    long newestId() {
        Document found = counters.find(Filters.eq("_id", "messages")).first();
        return number(found, "seq");
    }

    @Override
    @NotNull List<Envelope> readAfter(@NotNull String channel, long after, int limit) {
        List<Envelope> found = new ArrayList<>();
        for (Document document : messages.find(Filters.and(Filters.gt("_id", after), Filters.eq("channel", channel)))
                .sort(Sorts.ascending("_id")).limit(limit)) {
            found.add(new Envelope(number(document, "_id"), document.getString("payload")));
        }
        return found;
    }

    @Override
    void purge(long before) {
        messages.deleteMany(Filters.lt("created", before));
    }

    @Override
    public void publish(@NotNull String channel, @NotNull String message) {
        messages.insertOne(new Document("_id", next("messages")).append("channel", channel)
                .append("payload", message).append("created", System.currentTimeMillis()));
    }

    @Override
    public @Nullable String get(@NotNull String key) {
        Document found = keys.find(Filters.eq("_id", key)).first();
        if (found == null) {
            return null;
        }
        long expires = number(found, "expires");
        return expires > 0 && expires <= System.currentTimeMillis() ? null : found.getString("v");
    }

    @Override
    public void streamAdd(@NotNull String stream, @NotNull Map<String, String> fields, long maxLength) {
        long id = next("entries");
        entries.insertOne(new Document("_id", id).append("stream", stream)
                .append("fields", new Document(new LinkedHashMap<>(fields))));
        if (maxLength > 0) {
            entries.deleteMany(Filters.and(Filters.eq("stream", stream), Filters.lte("_id", id - maxLength)));
        }
    }

    @Override
    public @NotNull String describe() {
        return describe;
    }

    @Override
    void closeStore() {
        client.close();
    }
}
