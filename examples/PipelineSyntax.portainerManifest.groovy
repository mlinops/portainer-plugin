// Pipeline example for Portainer Manifest Deployment build step (symbol: portainerManifest)
//
// Prerequisites:
//   1. Manage Jenkins → Credentials → Secret text with Portainer Access token
//   2. Manage Jenkins → System → Portainer (Inherit):
//        Portainer URL: https://portainer.example:9443
//        API key credentials: the Secret text above
//   3. endpointId must be a Kubernetes environment (Type 5/6/7)
//
// Upsert: create Kubernetes stack if missing; update file content or Git ref if present.
// Hosts: portainer.example / gitlab.example only
//
// No agent: Manifest talks to Portainer on the controller (requiresWorkspace=false).

pipeline {
    agent none
    stages {
        stage('Apply manifest (Manual YAML)') {
            steps {
                portainerManifest(
                    endpointId: '2',
                    stackName: 'demo-web',
                    namespace: 'default',
                    stackSource: 'yaml',
                    stackFileContent: '''
apiVersion: apps/v1
kind: Deployment
metadata:
  name: demo-web
spec:
  replicas: 1
  selector:
    matchLabels:
      app: demo-web
  template:
    metadata:
      labels:
        app: demo-web
    spec:
      containers:
        - name: web
          image: nginx:alpine
'''
                )
            }
        }
        stage('Apply manifest (Repository)') {
            steps {
                portainerManifest(
                    // portainerConnectionMode: 'inherit',
                    endpointId: '2',
                    stackName: 'demo-web',
                    namespace: 'apps',
                    // ensureNamespace: true,  // default; set false to skip
                    repositoryUrl: 'https://gitlab.example/group/manifests.git',
                    manifestFilePath: 'examples/stacks/kubernetes-manifest.yaml',
                    repositoryReferenceName: 'refs/heads/main',
                    gitCredentialsId: 'git-clone'
                )
            }
        }
        stage('Validate only (no Portainer mutate)') {
            steps {
                portainerManifest(
                    endpointId: '2',
                    stackName: 'demo-web',
                    namespace: 'default',
                    stackSource: 'yaml',
                    stackFileContent: '''
apiVersion: v1
kind: ConfigMap
metadata:
  name: demo-web
''',
                    validateOnly: true
                )
            }
        }
    }
}
