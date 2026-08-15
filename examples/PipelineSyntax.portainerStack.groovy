// Pipeline example for Portainer Stack Deployment build step (symbol: portainerStack)
//
// Prerequisites:
//   1. Manage Jenkins → Credentials → Secret text with Portainer Access token
//   2. Manage Jenkins → System → Portainer (Inherit source):
//        Display name: Production Portainer
//        Portainer URL: https://portainer.example:9443
//        API key credentials: the Secret text above
//        (Save does not probe; builds run preflight)
//   3. (Optional Vault Inherit) Install HashiCorp Vault Plugin; configure System hashicorpVault
//   4. (Optional Vault Manual) Username/Password: username=AppRole role_id, password=secret_id
//
// Symbol: portainerStack
// Defaults: portainerConnectionMode=inherit, vaultConnectionMode=none (Not connected), stackSource=repository
// Vault: none (Not connected, default) | inherit | manual
// Behavior: deploy if the stack does not exist on the endpoint; otherwise redeploy/update.
// Hosts in docs/examples: portainer.example / gitlab.example / vault.example only
//
// No agent: Stack talks to Portainer/Vault on the controller (requiresWorkspace=false).
// Use agent / node only when a later stage needs the workspace (e.g. portainerStackConfig / Helm Git values).

pipeline {
    agent none
    parameters {
        string(name: 'BRANCH', defaultValue: 'main', description: 'Git branch for the stack repo')
    }
    stages {
        stage('Sync stack (Portainer Inherit)') {
            steps {
                portainerStack(
                    // portainerConnectionMode: 'inherit',  // default — System Portainer
                    // stackSource: 'repository',           // default — Git
                    // vaultConnectionMode: 'none',         // default — Vault Off
                    endpointId: '1',
                    stackType: 'compose',
                    stackName: 'myapp',
                    repositoryUrl: 'https://gitlab.example/group/stack.git',
                    composeFilePath: 'docker-compose.yml',
                    repositoryReferenceName: "refs/heads/${params.BRANCH}",
                    gitCredentialsId: 'git-clone',
                    env: '''
IMAGE_TAG=1.0.0
FEATURE_FLAG=true
'''
                )
            }
        }
        stage('Sync stack (Manual YAML)') {
            steps {
                portainerStack(
                    endpointId: '1',
                    stackType: 'compose',
                    stackName: 'myapp-inline',
                    stackSource: 'yaml',
                    stackFileContent: '''
services:
  web:
    image: nginx:alpine
    ports:
      - "8080:80"
''',
                    env: "IMAGE_TAG=${env.BUILD_NUMBER}"
                )
            }
        }
        stage('Sync stack (Portainer Manual)') {
            steps {
                portainerStack(
                    portainerConnectionMode: 'manual',
                    portainerUrl: 'https://portainer.example:9443',
                    portainerCredentialsId: 'portainer-api-key',
                    endpointId: '1',
                    stackType: 'compose',
                    stackName: 'myapp',
                    repositoryUrl: 'https://gitlab.example/group/stack.git',
                    composeFilePath: 'docker-compose.yml'
                )
            }
        }
        stage('Sync stack with Vault Off') {
            steps {
                portainerStack(
                    endpointId: '1',
                    stackType: 'compose',
                    stackName: 'myapp',
                    repositoryUrl: 'https://gitlab.example/group/stack.git',
                    composeFilePath: 'docker-compose.yml',
                    vaultConnectionMode: 'none',
                    env: "IMAGE_TAG=${env.BUILD_NUMBER}"
                )
            }
        }
        stage('Sync stack with Vault Inherit') {
            steps {
                portainerStack(
                    endpointId: '1',
                    stackType: 'compose',
                    stackName: 'myapp',
                    repositoryUrl: 'https://gitlab.example/group/stack.git',
                    composeFilePath: 'docker-compose.yml',
                    env: '''
IMAGE_TAG=from-step
FEATURE_FLAG=true
''',
                    vaultConnectionMode: 'inherit',
                    vaultPath: 'myapp/prod',
                    vaultMount: 'secret'
                    // vaultNamespace: 'team-a'   // optional Enterprise override
                )
            }
        }
        stage('Sync stack with Vault Manual AppRole') {
            steps {
                portainerStack(
                    endpointId: '1',
                    stackType: 'compose',
                    stackName: 'myapp',
                    repositoryUrl: 'https://gitlab.example/group/stack.git',
                    composeFilePath: 'docker-compose.yml',
                    env: '''
IMAGE_TAG=from-step
FEATURE_FLAG=true
''',
                    vaultConnectionMode: 'manual',
                    vaultUrl: 'https://vault.example:8200',
                    vaultAppRoleCredentialsId: 'vault-approle',
                    vaultPath: 'myapp/prod',
                    vaultMount: 'secret'
                    // vaultNamespace: 'team-a',   // Enterprise only; leave empty for OSS
                    // vaultVersion: '3'           // Manual only; empty = latest
                )
            }
        }
        stage('Sync stack with redeploy options') {
            steps {
                portainerStack(
                    endpointId: '1',
                    stackType: 'compose',
                    stackName: 'myapp',
                    repositoryUrl: 'https://gitlab.example/group/stack.git',
                    composeFilePath: 'docker-compose.yml',
                    prune: true,
                    repullImageAndRedeploy: true,
                    // mergeEnvWithExisting: true,  // opt-in — overlay step env onto Portainer stack Env
                    env: "IMAGE_TAG=${env.BUILD_NUMBER}"
                )
            }
        }
        stage('Validate only (no Portainer mutate)') {
            steps {
                portainerStack(
                    endpointId: '1',
                    stackType: 'compose',
                    stackName: 'myapp',
                    repositoryUrl: 'https://gitlab.example/group/stack.git',
                    composeFilePath: 'docker-compose.yml',
                    validateOnly: true
                )
            }
        }
    }
}
