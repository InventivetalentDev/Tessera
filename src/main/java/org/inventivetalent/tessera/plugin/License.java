package org.inventivetalent.tessera.plugin;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * License-key constants, format check, and surviving anti-piracy placeholders
 * carried over from the BuiltByBit era.
 *
 * <p><b>Where the key comes from.</b> Each server operator pastes their
 * LemonSqueezy-issued license key into {@code license.key} in
 * {@code config.yml}. The plugin lowercases it, validates the format against
 * {@link #LS_KEY_PATTERN}, and forwards it to the Tessera backend in the
 * {@code X-Tessera-License} header. The backend validates against LemonSqueezy's
 * license API and caches the result.
 *
 * <p><b>What's left of the BBB anti-piracy placeholders.</b> Two of BuiltByBit's
 * placeholder anchors are still here as opportunistic distribution metadata:
 * {@link #DISTRIBUTOR_USER_ID} and {@link #DISTRIBUTOR_RESOURCE_ID}. If the jar
 * was downloaded through a channel that supports BBB-style placeholder
 * substitution, those strings get rewritten in the compiled class file's
 * constant pool before the buyer receives the jar — see the BBB wiki:
 * <a href="https://builtbybit.com/wiki/anti-piracy-placeholders">Anti-piracy
 * placeholders</a>. We send them to the backend only when they were actually
 * substituted (not still the literal {@code %%__...__%%} form); they're useful
 * for spotting key sharing in logs but never enforced.
 */
public final class License {

    private License() {}

    /** Backend base URL — license-gated MineSkin proxy + archive endpoints. */
    public static final String BACKEND_BASE_URL = "https://tessera-backend.inventivetalent.org";

    /**
     * Downloader's distributor user ID (numeric, as a string), if the jar was
     * shipped through a channel that substitutes the BBB {@code %%__USER__%%}
     * placeholder. Stays literal otherwise — check via
     * {@link #distributorUserIdOrNull()} before sending.
     */
    public static final String DISTRIBUTOR_USER_ID = "%%__USER__%%";

    /**
     * Distributor's resource/listing ID, if the jar was shipped through a
     * channel that substitutes the BBB {@code %%__RESOURCE__%%} placeholder.
     * Stays literal otherwise — check via {@link #distributorResourceIdOrNull()}
     * before sending.
     */
    public static final String DISTRIBUTOR_RESOURCE_ID = "%%__RESOURCE__%%";

    /**
     * LemonSqueezy license key format: lowercase 8-4-4-4-12 hex UUID, e.g.
     * {@code 38b1460a-5104-4067-a91d-77b872934d51}. We canonicalise to lowercase
     * before matching so a paste from an email with uppercase letters still
     * works.
     */
    public static final Pattern LS_KEY_PATTERN =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** True iff {@code key}, lowercased, matches {@link #LS_KEY_PATTERN}. */
    public static boolean looksLikeKey(String key) {
        if (key == null) return false;
        return LS_KEY_PATTERN.matcher(key.toLowerCase(Locale.ROOT)).matches();
    }

    /**
     * Returns {@link #DISTRIBUTOR_USER_ID} if it was substituted by a
     * distributor's placeholder pipeline, otherwise {@code null}. We compare
     * against the literal pre-substitution string so an unmodified open-source
     * build never sends junk like {@code %%__USER__%%} as a header value.
     */
    public static String distributorUserIdOrNull() {
        return "%%__USER__%%".equals(DISTRIBUTOR_USER_ID) ? null : DISTRIBUTOR_USER_ID;
    }

    /** Companion to {@link #distributorUserIdOrNull()} for the resource ID. */
    public static String distributorResourceIdOrNull() {
        return "%%__RESOURCE__%%".equals(DISTRIBUTOR_RESOURCE_ID) ? null : DISTRIBUTOR_RESOURCE_ID;
    }
}
