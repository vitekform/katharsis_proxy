package me.ganamaga.katharsis_proxy.auth;

import com.password4j.Argon2Function;
import com.password4j.Password;
import com.password4j.types.Argon2;
import me.ganamaga.katharsis_proxy.config.AuthConfig;

/**
 * Hashes passwords with Argon2id, stored in the PHC string format
 * ({@code $argon2id$v=19$m=19456,t=2,p=1$salt$hash}).
 *
 * <p>Each hash carries its own parameters, so hashes made under older settings
 * still verify; {@link #needsRehash} tells when one should be redone under the
 * current ones. Both directions are deliberately slow and memory-hungry: call
 * them off the network and event threads.</p>
 */
public final class PasswordHasher {

    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    private final Argon2Function function;

    public PasswordHasher(AuthConfig.Argon2Config config) {
        this.function = Argon2Function.getInstance(config.memoryKib(), config.iterations(), config.parallelism(),
                HASH_BYTES, Argon2.ID, Argon2Function.ARGON2_VERSION_13);
    }

    /** A new hash of {@code password} with a fresh random salt. */
    public String hash(String password) {
        return Password.hash(password).addRandomSalt(SALT_BYTES).with(function).getResult();
    }

    /**
     * Whether {@code password} matches {@code hash}, compared in constant time.
     * A hash that cannot be read is a mismatch, never an error the caller could
     * mistake for a match.
     */
    public boolean verify(String password, String hash) {
        if (hash == null || !hash.startsWith("$argon2id$")) {
            return false;
        }
        try {
            return Password.check(password, hash).with(Argon2Function.getInstanceFromHash(hash));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Whether {@code hash} was made with other parameters than new hashes get. */
    public boolean needsRehash(String hash) {
        try {
            return !function.equals(Argon2Function.getInstanceFromHash(hash));
        } catch (RuntimeException e) {
            return true;
        }
    }
}
