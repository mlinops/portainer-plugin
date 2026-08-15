package io.jenkins.plugins.portainer;

/** Shared deploy result for Stack and Manifest steps. */
final class DeployOutcome {
    final String outcome;
    final int stackId;

    DeployOutcome(String outcome, int stackId) {
        this.outcome = outcome;
        this.stackId = stackId;
    }
}
