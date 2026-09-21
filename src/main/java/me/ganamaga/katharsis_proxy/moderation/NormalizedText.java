package me.ganamaga.katharsis_proxy.moderation;

/**
 * One chat message in the three forms the filters read it in.
 *
 * @param original   the text exactly as the player wrote it
 * @param normalized lower case, stripped of accents and look-alikes, with every
 *                   run of non-alphanumerics reduced to a single space, so word
 *                   boundaries still exist for patterns that rely on them, and
 *                   digits and punctuation still read as themselves
 * @param squeezed   the same, but with leetspeak read as the letters it stands
 *                   for and every separator gone, which is what defeats
 *                   {@code r a c i s t}, {@code r.a.c.i.s.t} and {@code r4c1st}
 */
public record NormalizedText(String original, String normalized, String squeezed) {

    /**
     * Whether the message held no words at all: punctuation or emoji only.
     * Judged on the normalized form, since the squeezed one reads a row of
     * exclamation marks as letters.
     */
    public boolean isBlank() {
        return normalized.isEmpty();
    }
}
