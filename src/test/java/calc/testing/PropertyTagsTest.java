package calc.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import net.jqwik.api.Example;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Guards the tags of the jqwik tests. jqwik only reads {@code net.jqwik.api.Tag}: a class tagged with the JUnit
 * annotation of the same name is silently left out of {@code -PincludeTags=property}, and nothing fails. This test
 * fails instead.
 */
class PropertyTagsTest {
    private static final Set<String> ALLOWED_TAGS = Set.of("property", "robustness");

    @Test
    void everyJqwikTestClassCarriesAJqwikPropertyOrRobustnessTag() throws Exception {
        List<Class<?>> jqwikClasses = jqwikClasses();

        assertTrue(jqwikClasses.size() >= 6, "The scan must find the property classes, found " + jqwikClasses);
        for (Class<?> testClass : jqwikClasses) {
            List<String> tags = new ArrayList<>();
            for (Tag tag : testClass.getAnnotationsByType(Tag.class)) {
                tags.add(tag.value());
            }
            assertTrue(tags.stream().anyMatch(ALLOWED_TAGS::contains),
                    testClass.getName() + " must carry net.jqwik.api.Tag(\"property\") or (\"robustness\"), has " + tags);
            assertEquals(0, testClass.getAnnotationsByType(org.junit.jupiter.api.Tag.class).length,
                    testClass.getName() + " uses org.junit.jupiter.api.Tag, which jqwik ignores: use net.jqwik.api.Tag.");
        }
    }

    private static List<Class<?>> jqwikClasses() throws IOException, URISyntaxException, ClassNotFoundException {
        Path root = Path.of(PropertyTagsTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String relative = root.relativize(file).toString();
                String name = relative.substring(0, relative.length() - ".class".length()).replace('/', '.').replace('\\', '.');
                if (name.contains("$")) {
                    continue;
                }
                Class<?> candidate = Class.forName(name, false, PropertyTagsTest.class.getClassLoader());
                if (hasJqwikTests(candidate)) {
                    classes.add(candidate);
                }
            }
        }
        return classes;
    }

    private static boolean hasJqwikTests(Class<?> candidate) {
        for (var method : candidate.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Property.class) || method.isAnnotationPresent(Example.class)) {
                return true;
            }
        }
        return false;
    }
}
