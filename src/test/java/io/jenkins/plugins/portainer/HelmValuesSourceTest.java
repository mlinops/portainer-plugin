package io.jenkins.plugins.portainer;

import net.sf.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HelmValuesSourceTest {

    @Test
    public void resolve_defaultsToNone() {
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.resolve(null, null));
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.resolve("", "  "));
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.resolve("none", "replicaCount: 1"));
    }

    @Test
    public void resolve_migratesBareValuesToYaml() {
        assertEquals(HelmValuesSource.YAML, HelmValuesSource.resolve(null, "replicaCount: 1\n"));
        assertEquals(HelmValuesSource.YAML, HelmValuesSource.resolve("  ", "a: 1"));
    }

    @Test
    public void normalize_aliases() {
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.normalize("off"));
        assertEquals(HelmValuesSource.REPOSITORY, HelmValuesSource.normalize("git"));
        assertEquals(HelmValuesSource.YAML, HelmValuesSource.normalize("manual"));
        assertEquals(HelmValuesSource.NONE, HelmValuesSource.normalize("{\"value\":\"none\"}"));
    }

    @Test
    public void flattenRadioBlock_hoistsNestedFields() {
        JSONObject form = new JSONObject();
        JSONObject block = new JSONObject();
        block.put("value", "repository");
        block.put("valuesRepositoryUrl", "https://gitlab.example/group/values.git");
        block.put("valuesFilePath", "prod/values.yaml");
        block.put("valuesGitCredentialsId", "git");
        block.put("valuesRepositoryReferenceName", "refs/heads/main");
        form.put("valuesSource", block);

        HelmValuesSource.flattenRadioBlock(form);

        assertEquals(HelmValuesSource.REPOSITORY, form.getString("valuesSource"));
        assertEquals("https://gitlab.example/group/values.git", form.getString("valuesRepositoryUrl"));
        assertEquals("prod/values.yaml", form.getString("valuesFilePath"));
        assertEquals("git", form.getString("valuesGitCredentialsId"));
        assertEquals("refs/heads/main", form.getString("valuesRepositoryReferenceName"));
    }

    @Test
    public void predicates() {
        assertTrue(HelmValuesSource.isNone("none"));
        assertTrue(HelmValuesSource.isRepository("repository"));
        assertTrue(HelmValuesSource.isYaml("yaml"));
        assertFalse(HelmValuesSource.isYaml("none"));
    }

    @Test
    public void releaseName_andValuesYamlValidation() {
        assertEquals("Helm release name is required.", PortainerHelmBuilder.validateReleaseName(null));
        assertTrue(PortainerHelmBuilder.validateReleaseName("BAD_NAME").contains("DNS-1123"));
        assertNull(PortainerHelmBuilder.validateReleaseName("nginx"));
        PortainerHelmBuilder.requireValidReleaseName("my-release");
        assertThrows(
                IllegalArgumentException.class,
                () -> PortainerHelmBuilder.requireValidReleaseName(""));

        PortainerHelmBuilder.requireLooksLikeYaml("replicaCount: 1\n");
        assertThrows(
                IllegalArgumentException.class,
                () -> PortainerHelmBuilder.requireLooksLikeYaml("plain text"));
    }
}
