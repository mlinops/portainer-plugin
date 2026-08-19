// Pipeline example for Portainer Manifest Deployment (symbol: portainerManifest)
//
// Namespace is defined in the YAML / Git file, not on this step.
// Hosts: portainer.example / gitlab.example only
// No agent: requiresWorkspace=false.

pipeline {
    agent none
    stages {
        stage('Apply manifest (Manual YAML)') {
            steps {
                portainerManifest(
                    endpointId: '2',
                    stackName: 'demo-web',
                    stackSource: 'yaml',
                    stackFileContent: '''
apiVersion: apps/v1
kind: Deployment
metadata:
  name: demo-web
  namespace: apps
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
                    endpointId: '2',
                    stackName: 'demo-web',
                    repositoryUrl: 'https://gitlab.example/group/manifests.git',
                    manifestFilePath: 'examples/stacks/kubernetes-manifest.yaml',
                    repositoryReferenceName: 'refs/heads/main',
                    gitCredentialsId: 'git-clone'
                )
            }
        }
        stage('Validate only') {
            steps {
                portainerManifest(
                    endpointId: '2',
                    stackName: 'demo-web',
                    stackSource: 'yaml',
                    stackFileContent: '''
apiVersion: v1
kind: ConfigMap
metadata:
  name: demo-web
  namespace: apps
''',
                    validateOnly: true
                )
            }
        }
    }
}
