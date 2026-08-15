// Pipeline example: ensure Swarm Docker configs from Git, then deploy stack with external config names.
//
// Order: portainerStackSecret (if PEM/files from Vault) → portainerStackConfig → portainerStack.
// Config step publishes uppercase env keys (rabbitmq-config.json → RABBITMQ_CONFIG).
// Stack env expands ${RABBITMQ_CONFIG} from the current build.

node {
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
        env: '''RABBITMQ_CONFIG=${RABBITMQ_CONFIG}
ENABLED_PLUGINS=${ENABLED_PLUGINS}''',
        mergeEnvWithExisting: true,
    )
}
