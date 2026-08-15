package io.jenkins.plugins.portainer;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.util.Map;

/**
 * Lightweight Compose / Swarm stack YAML checks before calling Portainer.
 * Syntax + presence of a non-empty {@code services} mapping (not full Compose schema).
 */
final class ComposeYamlValidator {

    private ComposeYamlValidator() {
    }

    /**
     * @throws IllegalArgumentException when content is not usable Compose YAML
     */
    static void requireValid(String content) {
        String err = validate(content);
        if (err != null) {
            throw new IllegalArgumentException(err);
        }
    }

    /**
     * @return error message, or {@code null} if OK
     */
    static String validate(String content) {
        if (content == null || content.isBlank()) {
            return "Compose YAML is empty.";
        }
        final Object loaded;
        try {
            LoaderOptions options = new LoaderOptions();
            Yaml yaml = new Yaml(new SafeConstructor(options));
            loaded = yaml.load(content);
        } catch (YAMLException e) {
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) {
                msg = e.getClass().getSimpleName();
            }
            msg = msg.replaceAll("\\s+", " ").trim();
            if (msg.length() > 160) {
                msg = msg.substring(0, 160) + "…";
            }
            return "Invalid Compose YAML: " + msg;
        }
        if (loaded == null) {
            return "Compose YAML is empty.";
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            return "Compose YAML must be a mapping with a 'services' key.";
        }
        if (!map.containsKey("services")) {
            return "Compose YAML must define a 'services' key.";
        }
        Object services = map.get("services");
        if (!(services instanceof Map<?, ?> servicesMap) || servicesMap.isEmpty()) {
            return "Compose YAML 'services' must be a non-empty mapping.";
        }
        return null;
    }
}
