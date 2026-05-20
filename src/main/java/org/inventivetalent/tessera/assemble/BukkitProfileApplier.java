package org.inventivetalent.tessera.assemble;

import org.inventivetalent.tessera.skin.HeadSkin;
import org.bukkit.Bukkit;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standard Bukkit-API profile applier. Decodes the SKIN texture URL out of
 * MineSkin's base64 {@code textureValue} JSON envelope and writes it to a
 * {@code PlayerProfile} via {@link PlayerTextures#setSkin(URL)}.
 *
 * <p>The MineSkin {@code signature} is dropped — the Bukkit profile API
 * doesn't expose raw property write. For player-head items this is fine:
 * clients render the URL contents without a Mojang-signature check. The
 * signature is only required for live-player skin authentication, not for
 * item-display rendering.
 */
final class BukkitProfileApplier implements ProfileApplier {

    /** Matches {@code "url" : "<value>"} inside the decoded textureValue JSON. */
    private static final Pattern SKIN_URL = Pattern.compile(
            "\"url\"\\s*:\\s*\"([^\"]+)\"");

    private final Logger logger;

    BukkitProfileApplier(Logger logger) {
        this.logger = logger;
    }

    @Override
    public boolean apply(SkullMeta meta, HeadSkin head) {
        URL url = decodeSkinUrl(head.textureValue());
        if (url == null) return false;

        PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID(), "tessera");
        PlayerTextures textures = profile.getTextures();
        textures.setSkin(url);
        profile.setTextures(textures);
        meta.setOwnerProfile(profile);
        return true;
    }

    private URL decodeSkinUrl(String textureValue) {
        if (textureValue == null || textureValue.isBlank()) return null;
        String json;
        try {
            json = new String(Base64.getDecoder().decode(textureValue), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            logger.log(Level.WARNING, "[profile] textureValue is not valid base64: " + e.getMessage());
            return null;
        }
        Matcher m = SKIN_URL.matcher(json);
        if (!m.find()) {
            logger.log(Level.WARNING, "[profile] no SKIN url found in decoded textureValue payload");
            return null;
        }
        String raw = m.group(1);
        try {
            return java.net.URI.create(raw).toURL();
        } catch (RuntimeException | java.net.MalformedURLException e) {
            logger.log(Level.WARNING, "[profile] malformed skin URL '" + raw + "': " + e.getMessage());
            return null;
        }
    }
}
