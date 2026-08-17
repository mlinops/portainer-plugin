package io.jenkins.plugins.portainer;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.model.Item;
import hudson.security.ACL;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

import java.util.Collections;
import java.util.List;

/**
 * Resolves Portainer Access token, optional Git clone credentials, and Vault AppRole credentials.
 * Never logs secret values, AppRole {@code role_id}, {@code secret_id}, or tokens.
 */
final class PortainerCredentials {

    private PortainerCredentials() {
    }

    static String resolveApiKey(String credentialsId, Item item) {
        if (credentialsId == null || credentialsId.isBlank()) {
            throw new IllegalStateException(
                    "Credentials ID is required — create a Secret text credential with the Portainer Access token.");
        }
        return resolveSecretText(credentialsId, item, "Portainer API key");
    }

    /**
     * Resolve a Secret text credential. Never logs the secret value.
     */
    static String resolveSecretText(String credentialsId, Item item, String purpose) {
        if (credentialsId == null || credentialsId.isBlank()) {
            throw new IllegalStateException(
                    "Credentials ID is required — create a Secret text credential for " + purpose + ".");
        }
        Credentials creds = findCredential(credentialsId, item, StringCredentials.class);
        if (!(creds instanceof StringCredentials sc)) {
            throw new IllegalStateException(
                    "Credentials '" + credentialsId + "' type is not supported (use Secret text for " + purpose + ").");
        }
        String secret = sc.getSecret().getPlainText();
        if (secret.isBlank()) {
            throw new IllegalStateException("Credentials '" + credentialsId + "' has an empty secret.");
        }
        return secret;
    }

    /**
     * Resolve Vault AppRole {@code role_id} + {@code secret_id} from a Username/Password credential.
     * Username = {@code role_id} (treat as secret), password = {@code secret_id}.
     * Never logs role_id, secret_id, or credential values.
     */
    static AppRoleIds resolveAppRole(String credentialsId, Item item) {
        if (credentialsId == null || credentialsId.isBlank()) {
            throw new IllegalStateException(
                    "Vault AppRole credentials ID is required — use Username/Password "
                            + "(username=role_id, password=secret_id).");
        }
        Credentials creds = findAppRoleCredential(credentialsId, item);
        if (!(creds instanceof StandardUsernamePasswordCredentials up)) {
            throw new IllegalStateException(
                    "Vault AppRole credentials '" + credentialsId
                            + "' type is not supported (use Username/Password).");
        }
        String roleId = up.getUsername();
        String secretId = up.getPassword().getPlainText();
        if (roleId.isBlank()) {
            throw new IllegalStateException(
                    "Vault AppRole credentials '" + credentialsId
                            + "' has an empty username (expected role_id).");
        }
        if (secretId.isBlank()) {
            throw new IllegalStateException(
                    "Vault AppRole credentials '" + credentialsId
                            + "' has an empty password (expected secret_id).");
        }
        return new AppRoleIds(roleId, secretId);
    }

    /**
     * Username/password for Portainer {@code RepositoryAuthentication}, or secret-text as password
     * with username {@code oauth2} (common for PATs).
     *
     * @return {@code null} when credentialsId is blank
     */
    static GitAuth resolveGitAuth(String credentialsId, Item item) {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }
        Credentials creds = findGitCredential(credentialsId, item);
        if (creds instanceof StandardUsernamePasswordCredentials up) {
            String user = up.getUsername();
            String pass = up.getPassword().getPlainText();
            if (pass.isBlank()) {
                throw new IllegalStateException("Git credentials '" + credentialsId + "' has an empty password.");
            }
            return new GitAuth(user, pass);
        }
        if (creds instanceof StringCredentials sc) {
            String secret = sc.getSecret().getPlainText();
            if (secret.isBlank()) {
                throw new IllegalStateException("Git credentials '" + credentialsId + "' has an empty secret.");
            }
            return new GitAuth("oauth2", secret);
        }
        throw new IllegalStateException(
                "Git credentials '" + credentialsId
                        + "' type is not supported (use Username/Password or Secret text).");
    }

    /**
     * Dropdown for Portainer API key / Secret text credentials.
     * Job: {@link Item#CONFIGURE}; System ({@code item == null}): {@link Jenkins#MANAGE}.
     */
    static ListBoxModel fillSecretText(Item item, String currentValue) {
        return fillMatching(item, currentValue, CredentialsMatchers.instanceOf(StringCredentials.class));
    }

    /**
     * Dropdown for Git credentials (Username/Password or Secret text).
     */
    static ListBoxModel fillSecretOrUsernamePassword(Item item, String currentValue) {
        return fillMatching(
                item,
                currentValue,
                CredentialsMatchers.anyOf(
                        CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                        CredentialsMatchers.instanceOf(StringCredentials.class)));
    }

    /**
     * Dropdown for Vault AppRole Username/Password credentials.
     */
    static ListBoxModel fillVaultAppRoleCredentials(Item item, String currentValue) {
        return fillMatching(
                item,
                currentValue,
                CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class));
    }

    private static ListBoxModel fillMatching(Item item, String currentValue, CredentialsMatcher matcher) {
        StandardListBoxModel model = new StandardListBoxModel();
        model.includeEmptyValue();
        if (item == null) {
            if (!Jenkins.get().hasPermission(Jenkins.MANAGE)) {
                model.includeCurrentValue(currentValue);
                return model;
            }
            model.includeMatchingAs(
                    ACL.SYSTEM2,
                    Jenkins.get(),
                    StandardCredentials.class,
                    Collections.emptyList(),
                    matcher);
        } else if (!item.hasPermission(Item.CONFIGURE)) {
            model.includeCurrentValue(currentValue);
            return model;
        } else {
            model.includeMatchingAs(
                    ACL.SYSTEM2,
                    item,
                    StandardCredentials.class,
                    Collections.emptyList(),
                    matcher);
        }
        model.includeCurrentValue(currentValue);
        return model;
    }

    static Credentials findCredential(String credentialsId, Item item) {
        return findCredential(credentialsId, item, StringCredentials.class);
    }

    private static Credentials findCredential(
            String credentialsId, Item item, Class<? extends Credentials> type) {
        List<StandardCredentials> candidates = lookup(item);
        Credentials creds = CredentialsMatchers.firstOrNull(
                candidates,
                CredentialsMatchers.allOf(
                        CredentialsMatchers.withId(credentialsId),
                        CredentialsMatchers.instanceOf(type)));
        if (creds == null) {
            throw new IllegalStateException(
                    "Credentials '" + credentialsId + "' not found, not accessible, "
                            + "or not of the expected type.");
        }
        return creds;
    }

    private static Credentials findAppRoleCredential(String credentialsId, Item item) {
        List<StandardCredentials> candidates = lookup(item);
        Credentials creds = CredentialsMatchers.firstOrNull(
                candidates,
                CredentialsMatchers.allOf(
                        CredentialsMatchers.withId(credentialsId),
                        CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class)));
        if (creds == null) {
            throw new IllegalStateException(
                    "Vault AppRole credentials '" + credentialsId
                            + "' not found, not accessible, or not Username/Password.");
        }
        return creds;
    }

    private static Credentials findGitCredential(String credentialsId, Item item) {
        List<StandardCredentials> candidates = lookup(item);
        Credentials creds = CredentialsMatchers.firstOrNull(
                candidates,
                CredentialsMatchers.allOf(
                        CredentialsMatchers.withId(credentialsId),
                        CredentialsMatchers.anyOf(
                                CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                                CredentialsMatchers.instanceOf(StringCredentials.class))));
        if (creds == null) {
            throw new IllegalStateException(
                    "Git credentials '" + credentialsId + "' not found, not accessible, "
                            + "or not Username/Password / Secret text.");
        }
        return creds;
    }

    private static List<StandardCredentials> lookup(Item item) {
        if (item != null) {
            return CredentialsProvider.lookupCredentialsInItem(
                    StandardCredentials.class,
                    item,
                    ACL.SYSTEM2,
                    Collections.emptyList());
        }
        return CredentialsProvider.lookupCredentialsInItemGroup(
                StandardCredentials.class,
                Jenkins.get(),
                ACL.SYSTEM2,
                Collections.emptyList());
    }

    /**
     * Ephemeral Vault AppRole ids resolved from Credentials at runtime.
     * Not a Stapler/DataBound type — job XML stores only {@code vaultAppRoleCredentialsId}.
     */
    static final class AppRoleIds {
        final String roleId;
        /** Resolved secret_id; never persisted as a config field. */
        @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
        final String secretId;

        AppRoleIds(String roleId, String secretId) {
            this.roleId = roleId;
            this.secretId = secretId;
        }
    }

    /**
     * Ephemeral Git username/password (or oauth2 + token) resolved from Credentials at runtime.
     * Not a Stapler/DataBound type — job XML stores only {@code gitCredentialsId}.
     */
    static final class GitAuth {
        final String username;
        /** Resolved credential secret; never persisted as a config field. */
        @SuppressWarnings("lgtm[jenkins/plaintext-storage]")
        final String password;

        GitAuth(String username, String password) {
            this.username = username;
            this.password = password;
        }
    }
}
