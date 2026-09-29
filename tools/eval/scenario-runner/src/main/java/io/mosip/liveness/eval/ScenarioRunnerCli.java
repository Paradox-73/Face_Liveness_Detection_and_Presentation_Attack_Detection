package io.mosip.liveness.eval;

import io.mosip.liveness.core.EngineException;
import io.mosip.liveness.core.api.ModelInfo;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Command line of the scenario runner.
 *
 * <pre>
 * java -jar scenario-runner.jar run --packs DIR --out DIR [--scenarios PATH]... [--filter REGEX]
 *      [--models DIR] [--threads N] [--config KEY=VALUE]... [--engine-param KEY=VALUE]... [--run-id ID] [--no-fail]
 * java -jar scenario-runner.jar inspect --packs DIR (--pack ID... | --all) [--out FILE.csv] [--models DIR]
 * </pre>
 *
 * Exit codes: 0 every expectation met, 1 at least one mismatch (0 with {@code --no-fail}), 2 usage or setup
 * error.
 */
public final class ScenarioRunnerCli {

    /** Environment variable naming the frame-pack root when {@code --packs} is not given. */
    static final String PACKS_ENV = "LIVENESS_FRAME_PACKS";

    private final PrintStream out;

    private ScenarioRunnerCli(PrintStream out) {
        this.out = out;
    }

    /**
     * Entry point.
     *
     * @param args command and options
     */
    public static void main(String[] args) {
        System.exit(new ScenarioRunnerCli(System.out).execute(args));
    }

    int execute(String[] args) {
        try {
            Options o = Options.parse(args);
            if ("run".equals(o.command)) {
                return run(o);
            }
            if ("inspect".equals(o.command)) {
                return inspect(o);
            }
            throw new IllegalArgumentException("unknown command: " + o.command);
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            System.err.println(Options.USAGE);
            return 2;
        } catch (IOException | EngineException e) {
            System.err.println("error: " + e);
            return 2;
        }
    }

    private int run(Options o) throws IOException, EngineException {
        List<Scenario> scenarios = loadScenarios(o.scenarioPaths, o.filter);
        if (scenarios.isEmpty()) {
            throw new IllegalArgumentException("no scenario matched");
        }
        FramePackStore store = new FramePackStore(o.packsRoot());
        Path outDir = o.outDir();
        Files.createDirectories(outDir);
        String runId = o.runId != null ? o.runId
                : DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(Instant.now());
        long t0 = System.nanoTime();
        try (EngineFactory factory = EngineFactory.load(o.models, o.threads)) {
            out.printf("Loaded model bundle %s in %.0f ms (%d models, ONNX Runtime intra-op threads %d).%n",
                    factory.bundleVersion(), (System.nanoTime() - t0) / 1e6, factory.models().models().size(),
                    o.threads);
            Map<String, Object> platform = platform(o.threads);
            AttemptRecord.RunInfo info = new AttemptRecord.RunInfo(runId, factory.bundleVersion(),
                    factory.modelSetSha256(), platform);
            ScenarioRunner runner = new ScenarioRunner(
                    p -> new AttemptDriver(factory.engine(factory.parameters(p)), factory.timer(), factory.decoder()), store, o.config,
                    o.engineParams, info, out);
            SummaryTable summary = new SummaryTable();
            try (Writer jsonl = new OutputStreamWriter(Files.newOutputStream(outDir.resolve("attempts.jsonl")),
                    StandardCharsets.UTF_8)) {
                for (Scenario sc : scenarios) {
                    out.println(sc.id() + " (" + sc.kind().name().toLowerCase(java.util.Locale.ROOT) + ", " + sc.species() + ", "
                            + sc.seeds().size() + " transaction(s))");
                    runner.run(sc, jsonl, summary);
                }
            }
            writeRunInfo(outDir, o, info, factory);
            String md = "# Scenario run " + runId + "\n\n" + summary.toMarkdown();
            Files.write(outDir.resolve("summary.md"), md.getBytes(StandardCharsets.UTF_8));
            out.println();
            out.println(summary.toMarkdown());
            out.println("Wrote " + outDir.resolve("attempts.jsonl") + " and " + outDir.resolve("summary.md"));
            return summary.mismatches() == 0 || o.noFail ? 0 : 1;
        }
    }

    private int inspect(Options o) throws IOException, EngineException {
        FramePackStore store = new FramePackStore(o.packsRoot());
        List<String> ids = new ArrayList<>(o.packIds);
        if (o.all) {
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(store.root(), Files::isDirectory)) {
                for (Path d : dirs) {
                    if (!d.getFileName().toString().startsWith("_")) {
                        ids.add(d.getFileName().toString());
                    }
                }
            }
            Collections.sort(ids);
        }
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("inspect needs --pack ID or --all");
        }
        try (EngineFactory factory = EngineFactory.load(o.models, o.threads);
             PrintWriter csv = o.out == null ? new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))
                     : new PrintWriter(Files.newBufferedWriter(Paths.get(o.out), StandardCharsets.UTF_8))) {
            PackInspector inspector = new PackInspector(factory.decoder(), factory.models().faceAnalyzer(),
                    factory.models().passivePad());
            csv.println(PackInspector.HEADER);
            for (String id : ids) {
                String line = inspector.inspect(store.pack(id), csv);
                System.err.println(line);
            }
        }
        return 0;
    }

    private static List<Scenario> loadScenarios(List<String> paths, Pattern filter) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String p : paths) {
            Path path = Paths.get(p);
            if (Files.isDirectory(path)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(path, "*.yaml")) {
                    for (Path f : ds) {
                        files.add(f);
                    }
                }
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            } else {
                throw new IllegalArgumentException("no such scenario file or folder: " + p);
            }
        }
        Collections.sort(files);
        List<Scenario> out = new ArrayList<>();
        for (Path f : files) {
            Scenario sc = ScenarioLoader.load(f);
            if (filter == null || filter.matcher(sc.id()).find()) {
                out.add(sc);
            }
        }
        return out;
    }

    private static Map<String, Object> platform(int threads) {
        Properties versions = new Properties();
        try (InputStream in = ScenarioRunnerCli.class.getResourceAsStream("runner.properties")) {
            if (in != null) {
                versions.load(in);
            }
        } catch (IOException e) {
            // versions stay unknown
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        m.put("arch", System.getProperty("os.arch"));
        m.put("java", System.getProperty("java.vendor") + " " + System.getProperty("java.version"));
        m.put("cpus", Runtime.getRuntime().availableProcessors());
        m.put("onnxruntime", versions.getProperty("onnxruntime.version", "unknown"));
        m.put("ort_intra_op_threads", threads);
        m.put("liveness", versions.getProperty("liveness.version", "unknown"));
        m.put("runner", versions.getProperty("runner.version", "unknown"));
        return m;
    }

    private static void writeRunInfo(Path outDir, Options o, AttemptRecord.RunInfo info, EngineFactory factory)
            throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("run_id", info.runId);
        m.put("model_bundle", info.modelBundle);
        m.put("model_set_sha256", info.modelSetSha256);
        List<Object> models = new ArrayList<>();
        for (ModelInfo mi : factory.models().models()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", mi.id());
            e.put("version", mi.version());
            e.put("sha256", mi.sha256());
            models.add(e);
        }
        m.put("models", models);
        m.put("platform", info.platform);
        m.put("global_config", o.config);
        m.put("signed_engine_params", factory.signedParams());
        m.put("global_engine_params", o.engineParams);
        m.put("packs_root", o.packsRoot().toString());
        m.put("scenarios", o.scenarioPaths);
        m.put("filter", o.filter == null ? null : o.filter.pattern());
        Files.write(outDir.resolve("run.json"), (Json.write(m) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Parsed command-line options. */
    static final class Options {
        static final String USAGE = "usage:\n"
                + "  scenario-runner run --packs DIR --out DIR [--scenarios PATH]... [--filter REGEX] [--models DIR]\n"
                + "                      [--threads N] [--config KEY=VALUE]... [--engine-param KEY=VALUE]...\n"
                + "                      [--run-id ID] [--no-fail]\n"
                + "  scenario-runner inspect --packs DIR (--pack ID... | --all) [--out FILE.csv] [--models DIR]\n"
                + "  --packs defaults to $" + PACKS_ENV + "; --scenarios defaults to eval/scenarios";

        String command;
        final List<String> scenarioPaths = new ArrayList<>();
        final List<String> packIds = new ArrayList<>();
        final Map<String, String> config = new LinkedHashMap<>();
        final Map<String, String> engineParams = new LinkedHashMap<>();
        String packs;
        String out;
        File models;
        int threads = 2;
        Pattern filter;
        String runId;
        boolean noFail;
        boolean all;

        static Options parse(String[] args) {
            if (args.length == 0) {
                throw new IllegalArgumentException("no command");
            }
            Options o = new Options();
            o.command = args[0];
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "--scenarios":
                        o.scenarioPaths.add(value(args, ++i, a));
                        break;
                    case "--packs":
                        o.packs = value(args, ++i, a);
                        break;
                    case "--pack":
                        o.packIds.add(value(args, ++i, a));
                        break;
                    case "--all":
                        o.all = true;
                        break;
                    case "--out":
                        o.out = value(args, ++i, a);
                        break;
                    case "--models":
                        o.models = new File(value(args, ++i, a));
                        break;
                    case "--threads":
                        o.threads = Integer.parseInt(value(args, ++i, a));
                        break;
                    case "--filter":
                        o.filter = Pattern.compile(value(args, ++i, a));
                        break;
                    case "--config":
                        putPair(o.config, value(args, ++i, a), true);
                        break;
                    case "--engine-param":
                        putPair(o.engineParams, value(args, ++i, a), false);
                        break;
                    case "--run-id":
                        o.runId = value(args, ++i, a);
                        break;
                    case "--no-fail":
                        o.noFail = true;
                        break;
                    default:
                        throw new IllegalArgumentException("unknown option: " + a);
                }
            }
            if (o.scenarioPaths.isEmpty()) {
                o.scenarioPaths.add("eval/scenarios");
            }
            if (o.threads < 1 || o.threads > 64) {
                throw new IllegalArgumentException("--threads must be 1-64");
            }
            if ("run".equals(o.command) && o.out == null) {
                throw new IllegalArgumentException("run needs --out DIR");
            }
            return o;
        }

        Path packsRoot() {
            String p = packs != null ? packs : System.getenv(PACKS_ENV);
            if (p == null || p.isEmpty()) {
                throw new IllegalArgumentException("--packs DIR or $" + PACKS_ENV + " is required");
            }
            return Paths.get(p);
        }

        Path outDir() {
            return Paths.get(out);
        }

        private static String value(String[] args, int i, String option) {
            if (i >= args.length) {
                throw new IllegalArgumentException(option + " needs a value");
            }
            return args[i];
        }

        private static void putPair(Map<String, String> target, String pair, boolean configKey) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("expected KEY=VALUE: " + pair);
            }
            String key = pair.substring(0, eq).trim();
            target.put(configKey ? ScenarioLoader.fullKey(key) : key, pair.substring(eq + 1).trim());
        }
    }
}
