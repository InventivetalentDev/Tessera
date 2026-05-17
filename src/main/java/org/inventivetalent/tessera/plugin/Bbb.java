package org.inventivetalent.tessera.plugin;

/**
 * BuiltByBit anti-piracy placeholder anchors.
 *
 * <p>BBB's download pipeline rewrites these literal strings inside the
 * downloaded jar before it reaches the buyer. On a free / open-source
 * download (or anywhere the jar wasn't acquired through BBB), the strings
 * stay as their literal {@code %%__...__%%} forms — that's how
 * {@link #PAID} stays {@code false}.
 *
 * <p>Replacement happens against {@code String} <em>constants</em> in the
 * compiled class file's constant pool. The compiler may inline a {@code
 * static final String} reference at every use site, but BBB walks the
 * class file's UTF-8 constants directly, so each placeholder gets
 * rewritten exactly once per .class regardless of how many call sites
 * reference it. We still keep them as separate fields rather than inlining
 * the literals at every read site, both for readability and so an
 * obfuscator instructed to preserve {@code %%__...__%%} patterns has a
 * single coherent surface to skip.
 *
 * <p>See the wiki:
 * <a href="https://builtbybit.com/wiki/anti-piracy-placeholders">Anti-piracy
 * placeholders</a>. {@link #BBB_LICENSE} corresponds to the resource's
 * <em>External License Key</em> placeholder — name {@code TESSERA_LICENSE},
 * webhook {@code https://tessera-backend.inventivetalent.org/bbb/issue-license}.
 */
public final class Bbb {

    private Bbb() {}

    /** Always literal {@code "true"} on a BBB download, the raw placeholder otherwise. */
    public static final String BBB_FLAG     = "%%__BUILTBYBIT__%%";

    /** Downloader's BBB user ID (numeric, as a string). */
    public static final String BBB_USER     = "%%__USER__%%";

    /** BBB resource ID for the Tessera listing. */
    public static final String BBB_RESOURCE = "%%__RESOURCE__%%";

    /**
     * Per-download nonce. Per BBB guidance the nonce is the only
     * cryptographically interesting anti-piracy field — the rest are
     * already known to the downloader. We forward it on every backend
     * request so a leaked jar's nonce gets correlated to the buyer in
     * server-side logs.
     */
    public static final String BBB_NONCE    = "%%__NONCE__%%";

    /**
     * The external license key our backend issued at download time. BBB's
     * placeholder framework calls our {@code /bbb/issue-license} webhook,
     * receives a {@code TSR-...} key in the response body, and substitutes
     * it here.
     */
    public static final String BBB_LICENSE  = "%%__TESSERA_LICENSE__%%";

    /** Backend base URL — paid mode routes MineSkin + archives traffic here. */
    public static final String BACKEND_BASE_URL = "https://tessera-backend.inventivetalent.org";

    /**
     * {@code true} iff this jar was downloaded through BuiltByBit (the
     * {@link #BBB_FLAG} placeholder was rewritten to literal {@code "true"}).
     * Every paid-mode code path branches on this; the free build sees
     * {@code false} and behaves exactly as it does today.
     */
    public static final boolean PAID = "true".equals(BBB_FLAG);
}
