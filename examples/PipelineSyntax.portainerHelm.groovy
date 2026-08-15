// Pipeline example for Portainer Helm Deployment build step (symbol: portainerHelm)
//
// Prerequisites:
//   1. Manage Jenkins → Credentials → Secret text with Portainer Access token
//   2. Manage Jenkins → System → Portainer (Inherit):
//        Portainer URL: https://portainer.example:9443
//        API key credentials: the Secret text above
//   3. endpointId must be a Kubernetes environment (Type 5/6/7)
//
// Semantics (default): install when missing; if the release exists, re-POST install
// (Portainer libhelm install-or-upgrade). Optional forceReinstall: true → uninstall then install.
// Values source: none (default) | repository | yaml
// Workspace: none / yaml may run outside node (agent none). repository needs an agent with git.
// Hosts: portainer.example / charts.example / gitlab.example only

pipeline {
    agent any
    stages {
        stage('Install / upgrade (no values)') {
            steps {
                portainerHelm(
                    // portainerConnectionMode: 'inherit',
                    endpointId: '2',
                    releaseName: 'demo-nginx',
                    chart: 'nginx',
                    repo: 'https://charts.example/helm',
                    namespace: 'default',
                    // valuesSource: 'none',  // default — chart defaults only
                    // ensureNamespace: true,  // default; set false to skip
                    atomic: true
                    // forceReinstall: true  // destructive: uninstall then install
                )
            }
        }
        stage('Install with Manual YAML values') {
            steps {
                portainerHelm(
                    endpointId: '2',
                    releaseName: 'demo-nginx',
                    chart: 'nginx',
                    repo: 'https://charts.example/helm',
                    namespace: 'default',
                    valuesSource: 'yaml',
                    values: '''
replicaCount: 1
image:
  repository: nginx
  tag: alpine
service:
  type: ClusterIP
''',
                    atomic: true
                )
            }
        }
        stage('Install with values from Git') {
            steps {
                // requires agent workspace + git on PATH
                portainerHelm(
                    endpointId: '2',
                    releaseName: 'demo-nginx',
                    chart: 'nginx',
                    repo: 'https://charts.example/helm',
                    namespace: 'default',
                    valuesSource: 'repository',
                    valuesRepositoryUrl: 'https://gitlab.example/group/helm-values.git',
                    valuesFilePath: 'values.yaml',
                    // valuesGitCredentialsId: 'git-clone',
                    valuesRepositoryReferenceName: 'refs/heads/main',
                    atomic: true
                )
            }
        }
        stage('Validate only (no Portainer mutate)') {
            steps {
                portainerHelm(
                    endpointId: '2',
                    releaseName: 'demo-nginx',
                    chart: 'nginx',
                    repo: 'https://charts.example/helm',
                    namespace: 'default',
                    validateOnly: true
                )
            }
        }
    }
}
