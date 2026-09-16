package me.ganamaga.katharsis_proxy.motd.generator;

import java.io.IOException;

/**
 * Turns a skin image into the value of a {@code textures} profile property,
 * which is what a player head in a chat component is drawn from.
 */
@FunctionalInterface
public interface TextureUploader {

    /**
     * @param skinPng a 64x64 skin encoded as PNG
     * @return the base64 {@code textures} property value
     * @throws IOException          if no value could be obtained
     * @throws InterruptedException if interrupted while waiting between attempts
     */
    String upload(byte[] skinPng) throws IOException, InterruptedException;
}
