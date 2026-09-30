package me.ganamaga.katharsis_updater;

/**
 * One downloadable jar.
 *
 * @param url    the release system's link; it redirects to the CDN
 * @param sha256 lowercase hex, or empty if the release system has none
 */
public record ReleaseFile(String filename, long size, String sha256, String url) {
}
