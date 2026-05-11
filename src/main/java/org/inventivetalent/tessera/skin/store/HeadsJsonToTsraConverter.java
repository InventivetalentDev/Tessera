package org.inventivetalent.tessera.skin.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Standalone CLI: turn a legacy {@code heads-{N}.json} into the new
 * {@code heads-{N}.ztsra} resource bundle. Exists so the one-time
 * conversion of the committed bundled file can run without spinning up the
 * full {@code tesseraBake} task — useful for shipping the new format
 * before the bake task gets re-run end-to-end.
 *
 * <p>Workflow: read the input JSON, materialize it into a scratch
 * {@link TsraFolderStore} under {@code build/tessera-cache/}, then zip the
 * folder into the requested output path. The scratch folder is left in
 * place so subsequent runs are cheap.
 *
 * <p>Args:
 * <pre>
 *   --in       legacy heads-{N}.json (required)
 *   --out      heads-{N}.ztsra to write (required)
 *   --scratch  scratch folder root (default: build/tessera-cache)
 *   --gridN    expected grid size when the JSON omits it (default: 4)
 *   --version  expected MC version when the JSON omits it (default: 1.21.4)
 *   --delete   if "true", delete the input JSON after a successful conversion
 * </pre>
 */
public final class HeadsJsonToTsraConverter {

    public static void main(String[] argv) throws IOException {
        Map<String, String> args = parseArgs(argv);
        Path in = requireArg(args, "in");
        Path out = requireArg(args, "out");
        Path scratchRoot = Path.of(args.getOrDefault("scratch", "build/tessera-cache"));
        int gridN = Integer.parseInt(args.getOrDefault("gridN", "4"));
        String version = args.getOrDefault("version", "1.21.4");
        boolean deleteInput = Boolean.parseBoolean(args.getOrDefault("delete", "false"));

        Logger logger = Logger.getLogger("tsra-convert");
        logger.setUseParentHandlers(false);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(Level.INFO);
        logger.addHandler(ch);
        logger.setLevel(Level.INFO);

        if (!Files.isRegularFile(in)) {
            logger.severe("Input not found: " + in.toAbsolutePath());
            System.exit(1);
        }

        Path scratch = scratchRoot.resolve("heads-" + gridN + "-convert" + TsraFormat.FOLDER_EXTENSION);
        // Always start from a clean scratch so a previous run's stale skins
        // don't leak into the zip.
        if (Files.isDirectory(scratch)) deleteRecursive(scratch);
        Files.createDirectories(scratch);

        TsraFolderStore folder = new TsraFolderStore(logger, scratch);
        int migrated = JsonMigrator.migrate(logger, in, folder, gridN, version);
        if (migrated == 0) {
            logger.warning("No blocks migrated — input " + in + " was empty or unreadable; aborting.");
            System.exit(2);
        }

        Files.createDirectories(out.toAbsolutePath().getParent());
        JsonMigrator.zipFolder(scratch, out);
        long bytes = Files.size(out);
        logger.info("Wrote " + out.toAbsolutePath() + " (" + bytes + " bytes)");

        if (deleteInput) {
            Files.delete(in);
            logger.info("Deleted source " + in);
        }
    }

    private static Path requireArg(Map<String, String> args, String key) {
        String v = args.get(key);
        if (v == null) {
            System.err.println("Missing --" + key);
            System.exit(1);
        }
        return Path.of(v);
    }

    private static Map<String, String> parseArgs(String[] argv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < argv.length - 1; i += 2) {
            String k = argv[i].startsWith("--") ? argv[i].substring(2) : argv[i];
            m.put(k, argv[i + 1]);
        }
        return m;
    }

    private static void deleteRecursive(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException io) { throw new RuntimeException(io); }
            });
        }
    }
}
