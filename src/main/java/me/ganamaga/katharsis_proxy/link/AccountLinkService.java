package me.ganamaga.katharsis_proxy.link;

import me.ganamaga.katharsis_proxy.database.AccountLinkRepository;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.entity.AccountLink;
import me.ganamaga.katharsis_proxy.entity.LinkCode;
import me.ganamaga.katharsis_proxy.player.PlayerCache;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Links Minecraft accounts to Discord accounts: issues the one-time codes on
 * the Discord side, redeems them in game, and keeps each player's link cached
 * while they are online and for {@link #CACHE_RETENTION} after they leave.
 *
 * <p>Every method that throws {@link SQLException} blocks on the database, so
 * call those off the event and Discord threads.</p>
 */
public final class AccountLinkService {

    /** How long a code works after it is issued. */
    public static final Duration CODE_VALIDITY = Duration.ofHours(6);

    /** How long a player's link stays cached after they disconnect. */
    public static final Duration CACHE_RETENTION = Duration.ofMinutes(10);

    /** Attempts at finding an unused code before giving up. Collisions are astronomically rare. */
    private static final int CODE_ATTEMPTS = 5;

    private final DatabaseManager database;
    private final AccountLinkRepository repository;
    private final LinkCodes codes;
    private final Clock clock;

    /** Empty inner optional: known not to be linked. */
    private final PlayerCache<Optional<AccountLink>> cache;

    public AccountLinkService(DatabaseManager database, AccountLinkRepository repository) {
        this(database, repository, new LinkCodes(), Clock.systemUTC(), CACHE_RETENTION);
    }

    AccountLinkService(DatabaseManager database, AccountLinkRepository repository, LinkCodes codes, Clock clock,
                       Duration cacheRetention) {
        this.database = database;
        this.repository = repository;
        this.codes = codes;
        this.clock = clock;
        this.cache = new PlayerCache<>(cacheRetention);
    }

    /** Whether the database is up, without which nothing can be linked. */
    public boolean isAvailable() {
        return database.isAvailable();
    }

    /**
     * The player's link as cached, without touching the database. Empty when
     * they are not linked or not cached; use {@link #linkOf} to tell apart.
     */
    public Optional<AccountLink> cachedLink(UUID playerUuid) {
        return cache.get(playerUuid).flatMap(Function.identity());
    }

    /** The player's link, from the cache when it has the answer, else from the database. */
    public Optional<AccountLink> linkOf(UUID playerUuid) throws SQLException {
        Optional<Optional<AccountLink>> cached = cache.get(playerUuid);
        return cached.isPresent() ? cached.get() : load(playerUuid);
    }

    /** Reads the player's link from the database into the cache, e.g. on join. */
    public Optional<AccountLink> load(UUID playerUuid) throws SQLException {
        Optional<AccountLink> link = repository.findByPlayer(playerUuid);
        cache.put(playerUuid, link);
        return link;
    }

    /**
     * Makes a new code for a Discord user, replacing any earlier one, unless
     * their account is already linked.
     */
    public IssueResult issueCode(long discordUserId, String discordUsername) throws SQLException {
        Optional<AccountLink> existing = repository.findByDiscordUser(discordUserId);
        if (existing.isPresent()) {
            return new IssueResult.AlreadyLinked(existing.get());
        }
        Instant now = now();
        for (int attempt = 0; attempt < CODE_ATTEMPTS; attempt++) {
            LinkCode code = new LinkCode(codes.generate(), discordUserId, discordUsername, now.plus(CODE_VALIDITY));
            if (repository.replaceCode(code, now)) {
                return new IssueResult.Issued(code);
            }
        }
        throw new SQLException("Could not find an unused link code in " + CODE_ATTEMPTS + " attempts");
    }

    /**
     * Links the player to the Discord account behind {@code input}, a code as
     * the player typed it. On success the code is used up and the link cached.
     */
    public RedeemResult redeem(UUID playerUuid, String playerName, String input) throws SQLException {
        Optional<String> code = LinkCodes.normalise(input);
        if (code.isEmpty()) {
            return new RedeemResult.InvalidCode();
        }
        RedeemResult result = repository.redeem(code.get(), playerUuid, playerName, now());
        switch (result) {
            case RedeemResult.Linked linked -> cache.put(playerUuid, Optional.of(linked.link()));
            case RedeemResult.PlayerAlreadyLinked already -> cache.put(playerUuid, Optional.of(already.link()));
            case RedeemResult.InvalidCode ignored -> {
            }
            case RedeemResult.DiscordAlreadyLinked ignored -> {
            }
        }
        return result;
    }

    /** Starts the cache retention window for a player who just disconnected. */
    public void expireLater(UUID playerUuid) {
        cache.expireLater(playerUuid);
    }

    /** Drops cached links whose retention window has passed; returns how many. */
    public int evictExpired() {
        return cache.evictExpired();
    }

    /** Forgets every cached link, e.g. on shutdown. */
    public void clearCache() {
        cache.clear();
    }

    /** Millisecond precision, which every database stores exactly. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
