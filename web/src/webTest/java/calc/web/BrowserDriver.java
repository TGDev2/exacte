package calc.web;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.opentest4j.TestAbortedException;

/**
 * Runs a driver page in a headless Chrome or Edge, with no module to install. The site and the driver pages are served by
 * the HTTP server of the JDK, on a free port of 127.0.0.1; the browser dumps the DOM of the driver page on its standard
 * output, and the observation is the JSON the page wrote in {@code <pre id="result">}.
 */
public final class BrowserDriver {
    /** The browser of this variable comes first; a CI or a developer can name another one. */
    public static final String BROWSER_VARIABLE = "CALC_BROWSER";
    /** {@code -PrequireBrowser=true}: a missing browser is then a failure, not a skipped test. */
    public static final String REQUIRED_PROPERTY = "calc.web.browser.required";

    private static final Path EDGE = Path.of("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe");
    private static final List<String> NAMES_ON_THE_PATH = List.of("chrome", "google-chrome", "chromium");
    private static final List<Path> SERVED_FOLDERS = List.of(Path.of("web", "src", "webTest", "resources"), Path.of("web", "build", "site"));
    private static final long BROWSER_WATCHDOG_SECONDS = 120;
    private static final long READERS_WATCHDOG_SECONDS = 10;
    private static final String RESULT_OPENING = "<pre id=\"result\">";
    private static final String RESULT_CLOSING = "</pre>";
    private static final int OUTPUT_SHOWN_ON_FAILURE = 2000;

    private BrowserDriver() {
    }

    // ---- Finding the browser -------------------------------------------------------------------------------------------

    /** {@value #BROWSER_VARIABLE}, then chrome, google-chrome and chromium on the PATH, then Edge at its fixed place. */
    public static Optional<Path> find(Map<String, String> environment, Predicate<Path> exists) {
        String named = variable(environment, BROWSER_VARIABLE);
        if (named != null && !named.isEmpty()) {
            Optional<Path> chosen = path(named).filter(exists);
            if (chosen.isPresent()) {
                return chosen;
            }
        }
        String searchPath = variable(environment, "PATH");
        if (searchPath != null) {
            for (String name : NAMES_ON_THE_PATH) {
                for (String folder : searchPath.split(File.pathSeparator)) {
                    Optional<Path> found = onThePath(folder, name, exists);
                    if (found.isPresent()) {
                        return found;
                    }
                }
            }
        }
        return exists.test(EDGE) ? Optional.of(EDGE) : Optional.empty();
    }

    /** The browser, or a failure when one is required, or an aborted test when it is not. */
    public static Path require(Optional<Path> browser, boolean required) {
        if (browser.isPresent()) {
            return browser.get();
        }
        String message = "Aucun navigateur : ni " + BROWSER_VARIABLE + ", ni " + NAMES_ON_THE_PATH + " dans le PATH, ni " + EDGE;
        if (required) {
            throw new AssertionError(message);
        }
        throw new TestAbortedException(message);
    }

    /** The browser of this machine, with the rule that {@code -PrequireBrowser=true} gives to this run. */
    public static Path requireInstalled() {
        return require(find(System.getenv(), Files::isRegularFile), Boolean.getBoolean(REQUIRED_PROPERTY));
    }

    // Under Windows, System.getenv() as a map keeps the case of each name: the variable is often written Path.
    private static String variable(Map<String, String> environment, String name) {
        String exact = environment.get(name);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Optional<Path> onThePath(String folder, String name, Predicate<Path> exists) {
        if (folder.isEmpty()) {
            return Optional.empty();
        }
        return path(folder).flatMap(directory -> Stream.of(name + ".exe", name)
                .map(directory::resolve).filter(exists).findFirst());
    }

    // A PATH may hold anything, such as a folder between quotes that Windows does not accept as a path.
    private static Optional<Path> path(String text) {
        try {
            return Optional.of(Path.of(text));
        } catch (InvalidPathException exception) {
            return Optional.empty();
        }
    }

    // ---- Running a driver page -----------------------------------------------------------------------------------------

    /**
     * Opens a driver page of {@code web/src/webTest/resources} and returns the text of its {@code <pre id="result">}.
     *
     * @param driverPage  the file name of the driver page, such as {@code page-driver.html}
     * @param queryString what follows the question mark, already encoded for a URL
     */
    public static String run(String driverPage, String queryString) throws IOException, InterruptedException {
        Path browser = requireInstalled();
        HttpServer server = serve();
        Path profile = Files.createTempDirectory("calc-browser-profile");
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/" + driverPage + "?" + queryString;
            return resultOf(launch(browser, profile, url));
        } finally {
            server.stop(0);
            deleteQuietly(profile);
        }
    }

    private static HttpServer serve() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", BrowserDriver::answer);
        server.start();
        return server;
    }

    private static void answer(HttpExchange exchange) throws IOException {
        try (exchange) {
            Optional<Path> file = servedFile(exchange.getRequestURI().getPath());
            if (file.isEmpty()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] body = Files.readAllBytes(file.get());
            exchange.getResponseHeaders().set("Content-Type", contentType(file.get().getFileName().toString()));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
        }
    }

    private static Optional<Path> servedFile(String requested) {
        String relative = requested.replaceFirst("^/+", "");
        for (Path folder : SERVED_FOLDERS) {
            Path root = folder.toAbsolutePath().normalize();
            Optional<Path> file = path(relative).map(root::resolve).map(Path::normalize)
                    .filter(candidate -> candidate.startsWith(root)).filter(Files::isRegularFile);
            if (file.isPresent()) {
                return file;
            }
        }
        return Optional.empty();
    }

    private static String contentType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (lower.endsWith(".md")) {
            return "text/markdown; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private record BrowserRun(int exitCode, String stdout, String stderr) {
    }

    private static BrowserRun launch(Path browser, Path profile, String url) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(browser.toString(), "--headless=new", "--disable-gpu", "--no-first-run",
                "--user-data-dir=" + profile, "--virtual-time-budget=20000", "--dump-dom"));
        if (System.getenv("CI") != null) {
            command.add("--no-sandbox");
        }
        command.add(url);
        Process process = new ProcessBuilder(command).start();
        process.getOutputStream().close();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        // Both at once: a browser that fills the pipe nobody reads would never end.
        Thread stdoutReader = reader(process.getInputStream(), stdout);
        Thread stderrReader = reader(process.getErrorStream(), stderr);
        boolean ended = process.waitFor(BROWSER_WATCHDOG_SECONDS, TimeUnit.SECONDS);
        if (ended) {
            // A child of the browser may keep the pipes open after the end of the main process.
            stdoutReader.join(TimeUnit.SECONDS.toMillis(READERS_WATCHDOG_SECONDS));
            stderrReader.join(TimeUnit.SECONDS.toMillis(READERS_WATCHDOG_SECONDS));
        }
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        if (!ended) {
            process.waitFor(READERS_WATCHDOG_SECONDS, TimeUnit.SECONDS);
            throw new AssertionError("Le navigateur n'a pas fini en " + BROWSER_WATCHDOG_SECONDS + " s : " + url
                    + "\nstderr : " + head(stderr.toString(UTF_8)));
        }
        return new BrowserRun(process.exitValue(), stdout.toString(UTF_8), stderr.toString(UTF_8));
    }

    private static Thread reader(InputStream stream, ByteArrayOutputStream sink) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try {
                int read = stream.read(buffer);
                while (read >= 0) {
                    sink.write(buffer, 0, read);
                    read = stream.read(buffer);
                }
            } catch (IOException exception) {
                // The stream closes when the process is killed: what was read so far is kept.
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static String resultOf(BrowserRun run) {
        int opening = run.stdout().indexOf(RESULT_OPENING);
        int start = opening + RESULT_OPENING.length();
        int closing = opening < 0 ? -1 : run.stdout().indexOf(RESULT_CLOSING, start);
        String result = closing < 0 ? "" : run.stdout().substring(start, closing);
        if (result.isEmpty()) {
            throw new AssertionError("La page pilote n'a écrit aucun constat, code de sortie " + run.exitCode()
                    + "\nstdout : " + head(run.stdout()) + "\nstderr : " + head(run.stderr()));
        }
        return result;
    }

    private static String head(String text) {
        return text.length() <= OUTPUT_SHOWN_ON_FAILURE ? text : text.substring(0, OUTPUT_SHOWN_ON_FAILURE) + "…";
    }

    // The browser may keep a lock on its profile for a while: a folder left under the build folder is no failure.
    private static void deleteQuietly(Path folder) {
        try (Stream<Path> files = Files.walk(folder)) {
            files.sorted(Comparator.reverseOrder()).forEach(BrowserDriver::deleteQuietlyOne);
        } catch (IOException | UncheckedIOException exception) {
            folder.toFile().deleteOnExit();
        }
    }

    private static void deleteQuietlyOne(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            file.toFile().deleteOnExit();
        }
    }

    // ---- Reading the observation ---------------------------------------------------------------------------------------

    /** A value of the JSON a driver page writes: the page writes it, so a wrong shape is a failure of the test. */
    public record Json(Object value) {
        public Json get(String key) {
            if (value instanceof Map<?, ?> map && map.containsKey(key)) {
                return new Json(map.get(key));
            }
            throw new AssertionError("Pas de clé " + key + " dans " + value);
        }

        public List<Json> items() {
            if (value instanceof List<?> list) {
                return list.stream().<Json>map(Json::new).toList();
            }
            throw new AssertionError("Pas une liste : " + value);
        }

        public String text() {
            if (value instanceof String string) {
                return string;
            }
            throw new AssertionError("Pas une chaîne : " + value);
        }

        public double number() {
            if (value instanceof BigDecimal number) {
                return number.doubleValue();
            }
            throw new AssertionError("Pas un nombre : " + value);
        }

        public boolean bool() {
            if (value instanceof Boolean flag) {
                return flag;
            }
            throw new AssertionError("Pas un booléen : " + value);
        }

        public boolean isNull() {
            return value == null;
        }
    }

    /** Reads a JSON text; the JDK has no parser, and the module adds no library for that. */
    public static Json parseJson(String text) {
        JsonReader reader = new JsonReader(text);
        Object value = reader.value();
        reader.spaces();
        if (reader.position != text.length()) {
            throw reader.failure("texte après la valeur");
        }
        return new Json(value);
    }

    private static final class JsonReader {
        private final String text;
        private int position;

        JsonReader(String text) {
            this.text = text;
        }

        Object value() {
            spaces();
            char first = peek();
            return switch (first) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            Map<String, Object> entries = new LinkedHashMap<>();
            position++;
            spaces();
            if (peek() == '}') {
                position++;
                return entries;
            }
            while (true) {
                spaces();
                String key = string();
                spaces();
                expect(':');
                entries.put(key, value());
                spaces();
                if (peek() == '}') {
                    position++;
                    return entries;
                }
                expect(',');
            }
        }

        private List<Object> array() {
            List<Object> items = new ArrayList<>();
            position++;
            spaces();
            if (peek() == ']') {
                position++;
                return items;
            }
            while (true) {
                items.add(value());
                spaces();
                if (peek() == ']') {
                    position++;
                    return items;
                }
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder content = new StringBuilder();
            while (peek() != '"') {
                char unit = text.charAt(position++);
                content.append(unit == '\\' ? escaped() : unit);
            }
            position++;
            return content.toString();
        }

        private char escaped() {
            char kind = peek();
            position++;
            return switch (kind) {
                case 'u' -> {
                    if (position + 4 > text.length()) {
                        throw failure("échappement coupé");
                    }
                    char unit = (char) Integer.parseInt(text.substring(position, position + 4), 16);
                    position += 4;
                    yield unit;
                }
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                case 'b' -> '\b';
                case 'f' -> '\f';
                case '"', '\\', '/' -> kind;
                default -> throw failure("échappement inconnu " + kind);
            };
        }

        private Object literal(String word, Object meaning) {
            if (!text.startsWith(word, position)) {
                throw failure("littéral inconnu");
            }
            position += word.length();
            return meaning;
        }

        private BigDecimal number() {
            int start = position;
            while (position < text.length() && "+-.eE0123456789".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
            if (start == position) {
                throw failure("valeur inconnue");
            }
            return new BigDecimal(text.substring(start, position));
        }

        void spaces() {
            while (position < text.length() && " \t\r\n".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
        }

        private void expect(char wanted) {
            if (peek() != wanted) {
                throw failure("attendu " + wanted);
            }
            position++;
        }

        private char peek() {
            if (position >= text.length()) {
                throw failure("fin du texte");
            }
            return text.charAt(position);
        }

        AssertionError failure(String reason) {
            return new AssertionError("JSON illisible à la position " + position + " : " + reason + "\n" + head(text));
        }
    }
}
