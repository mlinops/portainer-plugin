package io.jenkins.plugins.portainer;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.model.EnvironmentContributingAction;
import hudson.model.InvisibleAction;
import hudson.model.Run;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes Swarm Docker config and secret names as build environment variables for the next
 * {@code portainerStack} step.
 */
final class PortainerSwarmConfigEnvAction extends InvisibleAction implements EnvironmentContributingAction {

    private final Map<String, String> values;

    PortainerSwarmConfigEnvAction(Map<String, String> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    Map<String, String> values() {
        return values;
    }

    @Override
    public void buildEnvironment(@NonNull Run<?, ?> run, @NonNull EnvVars env) {
        env.putAll(values);
    }
}
