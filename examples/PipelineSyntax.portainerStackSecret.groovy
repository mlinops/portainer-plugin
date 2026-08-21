// Pipeline example: Swarm secrets from Vault, configs from Git, then stack.
//
// Order: portainerStackSecret → portainerStackConfig → portainerStack.
// Do not put PEM/file secrets into Stack Env[]; keep user/pass as Vault→Env on Stack if the image needs them.
//
// Secret and Stack may run outside node (controller HTTP). Config shallow-clones on the agent,
// so this combined flow uses node { } for Config (and convenience for env hand-off).

node {
    portainerStackSecret(
        endpointId: '1',
        vault: vaultInherit(
            vaultPath: 'applications/example/systems/rabbitmq'
        ),
        secretKeys: '''rabbitmq_signing_key
erlang_cookie''',
    )

    portainerStackConfig(
        endpointId: '1',
        repositoryUrl: 'https://gitlab.example/group/app-configs.git',
        configPath: 'configs/swarm',
        repositoryReferenceName: 'refs/heads/main',
        // gitCredentialsId: 'gitlab-http',
    )

    portainerStack(
        endpointId: '1',
        stackType: 'swarm',
        stackName: 'crm-stage',
        repositoryUrl: 'https://gitlab.example/group/stack.git',
        composeFilePath: 'docker-stack.yml',
        repositoryReferenceName: 'refs/heads/main',
        env: '''RABBITMQ_SIGNING_KEY=${RABBITMQ_SIGNING_KEY}
ERLANG_COOKIE=${ERLANG_COOKIE}
RABBITMQ_CONFIG=${RABBITMQ_CONFIG}
ENABLED_PLUGINS=${ENABLED_PLUGINS}''',
        mergeEnvWithExisting: true,
        // Vault inherit on Stack: only keys that must stay in Portainer Env[] (user/pass), not PEM files
    )
}
