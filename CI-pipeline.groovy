pipeline {
    agent {
        kubernetes {
            label 'user-service-pod'
            defaultContainer 'docker'
            yaml """
apiVersion: v1
kind: Pod
metadata:
  labels:
    app: docker-ecr
spec:
  serviceAccountName: jenkins-sa
  nodeSelector:
    kubernetes.io/hostname: worker
  containers:
  - name: docker
    image: docker:24-dind
    securityContext:
      privileged: true
    command:
    - dockerd-entrypoint.sh
    args:
    - --host=tcp://0.0.0.0:2375
    - --host=unix:///var/run/docker.sock
    tty: true
"""
        }
    }

    environment {
        AWS_REGION = 'us-east-1'
        ECR_REGISTRY = '059597010299.dkr.ecr.us-east-1.amazonaws.com'
        IMAGE_NAME = 'dev-app'
    }

    stages {
        stage('Clone Repo') {
            steps {
                container('docker') {
                    git branch: 'main', url: 'https://github.com/laurisseau/user-service'
                }
            }
        }
        
        stage('Set Image Tag') {
            steps {
                container('docker') {
                    script {
                        // Mark workspace as safe for Git
                        sh 'git config --global --add safe.directory /home/jenkins/agent/workspace/user-service-pipeline'
        
                        // Get short Git commit SHA
                        def commitSHA = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
        
                        // Export as environment variable
                        env.IMAGE_TAG = commitSHA
        
                        echo "Using image tag: ${env.IMAGE_TAG}"
                    }
                }
            }
        }
        
        stage('Build Docker Image') {
            steps {
                container('docker') {
                    sh """
# Wait for Docker daemon to start
sleep 5

# Build Docker image
docker build -f Dockerfile -t user-service-image:${IMAGE_TAG} .
docker images
"""
                }
            }
        }

        stage('Push to ECR') {
            steps {
                container('docker') {
                    withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', 
                                      credentialsId: 'pipeline-aws-cred']]) {
                        sh """
# Install AWS CLI, Bash, Helm, and Python
apk add --no-cache curl python3 py3-pip aws-cli bash gnupg ca-certificates tar

# Install kubectl
curl -LO https://storage.googleapis.com/kubernetes-release/release/\$(curl -s https://storage.googleapis.com/kubernetes-release/release/stable.txt)/bin/linux/amd64/kubectl
chmod +x ./kubectl
mv ./kubectl /usr/local/bin

# Install Helm
curl -fsSL https://get.helm.sh/helm-v3.16.2-linux-amd64.tar.gz -o helm.tar.gz
tar -zxvf helm.tar.gz
mv linux-amd64/helm /usr/local/bin

# Verify installs
helm version
kubectl version --client
aws --version

# Create/update Kubernetes secret for ECR
kubectl create secret docker-registry user-service-secret \
  --docker-server=${ECR_REGISTRY} \
  --docker-username=AWS \
  --docker-password="\$(aws ecr get-login-password --region ${AWS_REGION})" \
  --namespace sportsify-ns \
  --dry-run=client -o yaml | kubectl apply -f -

# Authenticate Docker with ECR
aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin ${ECR_REGISTRY}

# Tag Docker image for ECR
docker tag user-service-image:${IMAGE_TAG} ${ECR_REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}

# Push Docker image to ECR
docker push ${ECR_REGISTRY}/${IMAGE_NAME}:${IMAGE_TAG}
"""
                    }
                }
            }
        }
        
        stage('Save Image Tag') {
            steps {
                container('docker') {
                    script {
                        currentBuild.description = "Image tag: ${env.IMAGE_TAG}"
                        writeFile file: 'image-tag.txt', text: "${env.IMAGE_TAG}"
                        archiveArtifacts artifacts: 'image-tag.txt', fingerprint: true
                    }
                }
            }
        }
        
        stage('Trigger CD Pipeline') {
            steps {
                script {
                    build job: 'sportsify CD', parameters: [
                        string(name: 'IMAGE_TAG', value: env.IMAGE_TAG)
                    ]
                }
            }
        }
        
        
    }
}
