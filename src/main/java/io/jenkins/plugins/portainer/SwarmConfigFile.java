package io.jenkins.plugins.portainer;

/**
 * Config file read from a Git repository directory (path relative to config root, raw bytes).
 */
final class SwarmConfigFile {

    final String relativePath;
    final byte[] content;

    SwarmConfigFile(String relativePath, byte[] content) {
        this.relativePath = relativePath == null ? "" : relativePath;
        this.content = content == null ? new byte[0] : content;
    }
}
