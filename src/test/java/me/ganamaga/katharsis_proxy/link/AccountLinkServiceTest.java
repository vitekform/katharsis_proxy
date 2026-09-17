package me.ganamaga.katharsis_proxy.link;

import me.ganamaga.katharsis_proxy.config.ConfigManager;
import me.ganamaga.katharsis_proxy.database.AccountLinkRepository;
import me.ganamaga.katharsis_proxy.database.DatabaseManager;
import me.ganamaga.katharsis_proxy.entity.AccountLink;
import me.ganamaga.katharsis_proxy.entity.LinkCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the whole linking flow against an H2 database. */
class AccountLinkServiceTest {

    private static final Instant START = Instant.parse("2026-09-17T12:00:00Z");
    private static final UUID STEVE = UUID.fromString("1e1f2b3c-4d5e-6f70-8192-a3b4c5d6e7f8");
    private static final UUID ALEX = UUID.fromString("2e1f2b3c-4d5e-6f70-8192-a3b4c5d6e7f8");
    private static final long DISCORD_A = 123456789012345678L;
    private static final long DISCORD_B = 223456789012345678L;
    private static final Duration CACHE_RETENTION = Duration.ofMillis(300);

    /** A clock the test can move forward. */
    private static final class MovableClock extends Clock {

        Instant now = START;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private DatabaseManager database;
    private AccountLinkRepository repository;
    private AccountLinkService links;

    @BeforeEach
    void setUp(@TempDir Path directory) throws Exception {
        var logger = LoggerFactory.getLogger(AccountLinkServiceTest.class);
        ConfigManager config = new ConfigManager(logger, directory);
        config.load();
        database = new DatabaseManager(logger, directory);
        database.start(config.config().database());
        repository = new AccountLinkRepository(database);
        repository.createTables();
        links = service();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private AccountLinkService service() {
        return new AccountLinkService(database, repository, new LinkCodes(), clock, CACHE_RETENTION);
    }

    private String issue(long discordUserId, String username) throws Exception {
        IssueResult result = links.issueCode(discordUserId, username);
        return assertInstanceOf(IssueResult.Issued.class, result).code().code();
    }

    @Test
    @DisplayName("a code is issued for six hours and links the player to that Discord account")
    void issueAndRedeem() throws Exception {
        IssueResult.Issued issued = assertInstanceOf(IssueResult.Issued.class, links.issueCode(DISCORD_A, "alice"));
        assertEquals(START.plus(Duration.ofHours(6)), issued.code().expiresAt());
        assertTrue(LinkCodes.normalise(issued.code().code()).isPresent());

        RedeemResult result = links.redeem(STEVE, "Steve", issued.code().code());

        AccountLink expected = new AccountLink(STEVE, "Steve", DISCORD_A, "alice", START);
        assertEquals(new RedeemResult.Linked(expected), result);
        assertEquals(Optional.of(expected), repository.findByPlayer(STEVE));
        assertEquals(Optional.of(expected), repository.findByDiscordUser(DISCORD_A));
        assertEquals(Optional.of(expected), links.cachedLink(STEVE), "cached straight away");
    }

    @Test
    @DisplayName("a code works once")
    void codeIsOneTime() throws Exception {
        String code = issue(DISCORD_A, "alice");
        links.redeem(STEVE, "Steve", code);

        assertInstanceOf(RedeemResult.InvalidCode.class, links.redeem(ALEX, "Alex", code));
        assertTrue(repository.findByPlayer(ALEX).isEmpty());
    }

    @Test
    @DisplayName("a code stops working after six hours")
    void codeExpires() throws Exception {
        String code = issue(DISCORD_A, "alice");

        clock.now = START.plus(Duration.ofHours(6));
        assertInstanceOf(RedeemResult.InvalidCode.class, links.redeem(STEVE, "Steve", code));

        clock.now = START.plus(Duration.ofHours(6)).minusMillis(1);
        assertInstanceOf(RedeemResult.Linked.class, links.redeem(STEVE, "Steve", code), "just inside the window");
    }

    @Test
    @DisplayName("asking again replaces the earlier code")
    void reissueReplaces() throws Exception {
        String first = issue(DISCORD_A, "alice");
        String second = issue(DISCORD_A, "alice");

        assertInstanceOf(RedeemResult.InvalidCode.class, links.redeem(STEVE, "Steve", first));
        assertInstanceOf(RedeemResult.Linked.class, links.redeem(STEVE, "Steve", second));
    }

    @Test
    @DisplayName("case, spaces and dashes in a typed code are ignored; garbage is rejected")
    void typedCodeIsNormalised() throws Exception {
        String code = issue(DISCORD_A, "alice");
        String typed = " " + code.substring(0, 4).toLowerCase() + "-" + code.substring(4) + " ";

        assertInstanceOf(RedeemResult.InvalidCode.class, links.redeem(STEVE, "Steve", "not a code"));
        assertInstanceOf(RedeemResult.Linked.class, links.redeem(STEVE, "Steve", typed));
    }

    @Test
    @DisplayName("a linked player cannot link again, and the code is left for someone else")
    void playerLinksOnce() throws Exception {
        links.redeem(STEVE, "Steve", issue(DISCORD_A, "alice"));
        String other = issue(DISCORD_B, "bob");

        RedeemResult again = links.redeem(STEVE, "Steve", other);

        assertEquals(DISCORD_A, assertInstanceOf(RedeemResult.PlayerAlreadyLinked.class, again).link().discordUserId());
        assertInstanceOf(RedeemResult.Linked.class, links.redeem(ALEX, "Alex", other), "code not spent");
    }

    @Test
    @DisplayName("a linked Discord account gets no new code")
    void discordLinksOnce() throws Exception {
        links.redeem(STEVE, "Steve", issue(DISCORD_A, "alice"));

        IssueResult result = links.issueCode(DISCORD_A, "alice");

        assertEquals(STEVE, assertInstanceOf(IssueResult.AlreadyLinked.class, result).link().playerUuid());
    }

    @Test
    @DisplayName("a code whose Discord account was linked in the meantime is refused")
    void discordLinkedMeanwhile() throws Exception {
        links.redeem(STEVE, "Steve", issue(DISCORD_A, "alice"));
        // Only reachable by a race, since issuing checks first: plant the code directly.
        repository.replaceCode(new LinkCode("ABCDEFGH", DISCORD_A, "alice", START.plus(Duration.ofHours(1))), START);

        assertInstanceOf(RedeemResult.DiscordAlreadyLinked.class, links.redeem(ALEX, "Alex", "ABCDEFGH"));
        assertTrue(repository.findByPlayer(ALEX).isEmpty());
    }

    @Test
    @DisplayName("links are permanent: a fresh service, as after a restart, still sees them")
    void linksPersist() throws Exception {
        links.redeem(STEVE, "Steve", issue(DISCORD_A, "alice"));

        AccountLinkService restarted = service();

        assertTrue(restarted.cachedLink(STEVE).isEmpty(), "nothing cached yet");
        assertEquals(DISCORD_A, restarted.linkOf(STEVE).orElseThrow().discordUserId());
        assertTrue(restarted.cachedLink(STEVE).isPresent(), "looked up once, then cached");
    }

    @Test
    @DisplayName("codes survive a restart too")
    void codesPersist() throws Exception {
        String code = issue(DISCORD_A, "alice");

        assertInstanceOf(RedeemResult.Linked.class, service().redeem(STEVE, "Steve", code));
    }

    @Test
    @DisplayName("a link stays cached after the player leaves, then expires")
    void cacheRetention() throws Exception {
        links.redeem(STEVE, "Steve", issue(DISCORD_A, "alice"));
        links.load(ALEX);

        links.expireLater(STEVE);
        assertTrue(links.cachedLink(STEVE).isPresent(), "still warm right after leaving");

        Thread.sleep(CACHE_RETENTION.toMillis() + 150);
        assertEquals(1, links.evictExpired(), "only the player who left is swept");
        assertFalse(links.cachedLink(STEVE).isPresent());
        assertEquals(DISCORD_A, links.linkOf(STEVE).orElseThrow().discordUserId(), "the database still has it");
    }

    @Test
    @DisplayName("an unlinked player is cached as unlinked, and linking updates that")
    void cachesUnlinked() throws Exception {
        assertTrue(links.load(STEVE).isEmpty());
        assertTrue(links.linkOf(STEVE).isEmpty());

        links.redeem(STEVE, "Steve", issue(DISCORD_A, "alice"));

        assertTrue(links.linkOf(STEVE).isPresent());
    }

    @Test
    @DisplayName("the documented validity and retention")
    void documentedDurations() {
        assertEquals(6L, AccountLinkService.CODE_VALIDITY.toHours());
        assertEquals(10L, AccountLinkService.CACHE_RETENTION.toMinutes());
    }
}
