package io.jenkins.plugins.portainer;

import hudson.AbortException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shared Docker Swarm named-resource ensure/prune for secrets and configs.
 * Strategy supplies list/create/remove and label namespace; callers keep env-key / git-sha specifics.
 */
final class SwarmNamedResource {

    private SwarmNamedResource() {
    }

    @FunctionalInterface
    interface Lister {
        List<PortainerClient.DockerConfigSummary> list() throws IOException;
    }

    @FunctionalInterface
    interface Creator {
        void create(String name, byte[] data, Map<String, String> labels) throws IOException;
    }

    @FunctionalInterface
    interface Remover {
        void remove(String id) throws IOException;
    }

    @FunctionalInterface
    interface ExtraLabels {
        void apply(Map<String, String> labels, Desired desired);
    }

    static final class Kind {
        static final Kind SECRET = new Kind(
                "Ensuring Docker secrets",
                "jenkins.portainer.secret/base",
                "jenkins.portainer.secret/hash",
                "secret");
        static final Kind CONFIG = new Kind(
                "Ensuring Docker configs",
                "jenkins.portainer.config/base",
                "jenkins.portainer.config/hash",
                "config");

        final String ensuringMessage;
        final String labelBase;
        final String labelHash;
        final String noun;

        private Kind(String ensuringMessage, String labelBase, String labelHash, String noun) {
            this.ensuringMessage = ensuringMessage;
            this.labelBase = labelBase;
            this.labelHash = labelHash;
            this.noun = noun;
        }
    }

    static final class Desired {
        final String basename;
        final String name;
        final String hash;
        final byte[] content;

        Desired(String basename, String name, String hash, byte[] content) {
            this.basename = basename;
            this.name = name;
            this.hash = hash;
            this.content = content == null ? new byte[0] : content.clone();
        }
    }

    static final class Ensured {
        final String basename;
        final String name;
        final String hash;

        Ensured(String basename, String name, String hash) {
            this.basename = basename;
            this.name = name;
            this.hash = hash;
        }
    }

    static final class Outcome {
        final List<PortainerClient.DockerConfigSummary> listed;
        final List<Ensured> ensured;
        final int created;
        final int skipped;

        Outcome(
                List<PortainerClient.DockerConfigSummary> listed,
                List<Ensured> ensured,
                int created,
                int skipped) {
            this.listed = listed;
            this.ensured = ensured;
            this.created = created;
            this.skipped = skipped;
        }
    }

    static Outcome ensure(
            Kind kind,
            Lister lister,
            Creator creator,
            List<Desired> desired,
            ExtraLabels extraLabels,
            PortainerBuildLogger log) throws AbortException {
        log.info(kind.ensuringMessage);
        final List<PortainerClient.DockerConfigSummary> existing;
        try {
            existing = lister.list();
        } catch (IOException e) {
            throw PortainerConnections.abort(
                    log,
                    "Failed to list Docker " + kind.noun + "s: "
                            + PortainerConnections.truncateMessage(e),
                    e);
        }

        Set<String> existingNames = new LinkedHashSet<>();
        for (PortainerClient.DockerConfigSummary summary : existing) {
            existingNames.add(summary.name);
        }

        int created = 0;
        int skipped = 0;
        List<Ensured> ensured = new ArrayList<>();
        for (Desired item : desired) {
            log.debug(PortainerBuildLogger.formatHashOf(item.basename, item.hash));
            if (existingNames.contains(item.name)) {
                skipped++;
                log.debug("(exists) " + item.name);
                log.info("(skipped) " + item.name);
                ensured.add(new Ensured(item.basename, item.name, item.hash));
                continue;
            }

            log.debug("(missing) " + item.name);
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put(kind.labelBase, item.basename);
            labels.put(kind.labelHash, item.hash);
            if (extraLabels != null) {
                extraLabels.apply(labels, item);
            }
            try {
                creator.create(item.name, item.content, labels);
                created++;
                existingNames.add(item.name);
                log.info("(created) " + item.name);
                ensured.add(new Ensured(item.basename, item.name, item.hash));
            } catch (IOException e) {
                throw PortainerConnections.abort(
                        log,
                        "Failed to create Docker " + kind.noun + " \"" + item.name + "\": "
                                + PortainerConnections.truncateMessage(e),
                        e);
            }
        }
        return new Outcome(existing, ensured, created, skipped);
    }

    static void pruneStaleByBaseLabel(
            Kind kind,
            Remover remover,
            List<PortainerClient.DockerConfigSummary> listed,
            List<Ensured> ensured,
            PortainerBuildLogger log) {
        Map<String, String> currentByBase = indexEnsuredByBasename(ensured);
        List<String> pruned = new ArrayList<>();
        for (PortainerClient.DockerConfigSummary summary : listed) {
            if (!isStaleRelativeToCurrent(summary, kind.labelBase, currentByBase)) {
                continue;
            }
            removeOneSoftFail(kind, remover, summary, pruned, log);
        }
        if (!pruned.isEmpty()) {
            log.info("Pruned: " + String.join(", ", pruned));
        }
    }

    private static Map<String, String> indexEnsuredByBasename(List<Ensured> ensured) {
        Map<String, String> currentByBase = new LinkedHashMap<>();
        for (Ensured item : ensured) {
            currentByBase.put(item.basename, item.name);
        }
        return currentByBase;
    }

    private static boolean isStaleRelativeToCurrent(
            PortainerClient.DockerConfigSummary summary,
            String labelBase,
            Map<String, String> currentByBase) {
        String baseLabel = summary.labels.get(labelBase);
        if (baseLabel == null || baseLabel.isBlank()) {
            return false;
        }
        String keepName = currentByBase.get(baseLabel);
        return keepName != null && !keepName.equals(summary.name);
    }

    private static void removeOneSoftFail(
            Kind kind,
            Remover remover,
            PortainerClient.DockerConfigSummary summary,
            List<String> pruned,
            PortainerBuildLogger log) {
        if (summary.id == null || summary.id.isBlank()) {
            log.warn("Prune skipped " + summary.name + " (missing " + kind.noun + " ID)");
            return;
        }
        try {
            remover.remove(summary.id);
            pruned.add(summary.name);
        } catch (IOException e) {
            logPruneSoftFail(summary.name, e, log);
        }
    }

    private static void logPruneSoftFail(String name, IOException e, PortainerBuildLogger log) {
        String msg = PortainerConnections.truncateMessage(e);
        if (msg.contains("400") || msg.toLowerCase(Locale.ROOT).contains("in use")) {
            log.warn("Prune skipped " + name + " (still referenced)");
        } else {
            log.warn("Prune failed " + name + ": " + msg);
        }
    }
}
