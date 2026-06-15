package org.inventivetalent.tessera.api;

/**
 * The MineSkin texture data for a single baked chunk — everything a consumer
 * needs to render a player head showing that chunk's six faces.
 *
 * <p>{@code textureValue} / {@code textureSignature} are the base64 property
 * pair you attach to a {@code GameProfile} (property name {@code "textures"}) to
 * make a {@code PLAYER_HEAD} or head {@code ItemDisplay} show the skin. These
 * are the same values the client already receives for any entity wearing the
 * skin, so exposing them carries no secret. {@code hash} is Tessera's
 * content-address (SHA-256 of the post-paint PNG); identical chunks across
 * blocks share a payload, so it doubles as a stable dedup key.
 *
 * @param hash             content hash of the baked skin (stable dedup key)
 * @param textureValue     base64 MineSkin texture value
 * @param textureSignature base64 MineSkin signature (may be null on legacy data)
 * @param mineskinUuid     the MineSkin skin UUID, or null if unknown
 */
public record SkinPayload(String hash, String textureValue, String textureSignature,
                          String mineskinUuid) {
}
