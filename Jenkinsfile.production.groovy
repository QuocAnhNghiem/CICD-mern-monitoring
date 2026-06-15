// ==================== PRODUCTION PIPELINE ====================
// Branch: main
// Trigger: Tự động khi merge staging → main (hoặc push tag)
// Không có nút bấm - chạy tự động hoàn toàn

// ==================== BIẾN TOÀN CỤC ====================
def HARBOR_HOST = '34.21.141.11'
def HARBOR_PROJECT = 'mern'
def HARBOR_CREDENTIAL = 'harbor-credentials'
def STACK_NAME = 'mern-production'
def COMPOSE_FILE = 'source/docker-compose.production.yml'
def WORKER_IP = '34.126.186.86'
def BUILD_ENV = 'production'

// ==================== CÁC HÀM ====================

def buildImage(String context, String imageName, String tag, String buildEnv) {
    echo "🔨 Building image: ${imageName}:${tag}"
    if (buildEnv != null) {
        sh "docker build --build-arg BUILD_ENV=${buildEnv} -t ${imageName}:${tag} ${context}"
    } else {
        sh "docker build -t ${imageName}:${tag} ${context}"
    }
    sh "docker tag ${imageName}:${tag} ${imageName}:production"
}

def scanImage(String imageName, String tag) {
    echo "🔍 Scanning: ${imageName}:${tag}"
    sh "trivy image --severity HIGH,CRITICAL --no-progress ${imageName}:${tag} || true"
}

def pushImage(String imageName, String tag) {
    withCredentials([usernamePassword(
        credentialsId: HARBOR_CREDENTIAL,
        usernameVariable: 'HARBOR_USER',
        passwordVariable: 'HARBOR_PASS'
    )]) {
        sh """
            echo \$HARBOR_PASS | docker login ${HARBOR_HOST} -u \$HARBOR_USER --password-stdin
            docker push ${imageName}:${tag}
            docker push ${imageName}:production
            docker logout ${HARBOR_HOST}
        """
    }
}

def deployStack(String composeFile, String stackName, String imageTag) {
    echo "🚀 Deploying stack: ${stackName}"
    sh """
        export HARBOR_HOST=${HARBOR_HOST}
        export IMAGE_TAG=${imageTag}
        docker stack deploy -c ${composeFile} ${stackName}
    """
}

def healthCheck(String url, String name) {
    def result = sh(script: "curl -sf ${url}", returnStatus: true)
    if (result == 0) {
        echo "✅ ${name} is healthy!"
    } else {
        echo "⚠️ ${name} health check failed!"
    }
}

// ==================== PIPELINE ====================

node {
    def backendImage = "${HARBOR_HOST}/${HARBOR_PROJECT}/backend"
    def frontendImage = "${HARBOR_HOST}/${HARBOR_PROJECT}/frontend"
    def buildTag = env.BUILD_NUMBER

    try {
        stage('Checkout') {
            checkout scm
            echo "✅ Checked out branch: main (production)"
        }

        stage('Build Backend') {
            buildImage('source/backend', backendImage, buildTag, null)
        }

        stage('Build Frontend') {
            buildImage('source/frontend', frontendImage, buildTag, BUILD_ENV)
        }

        stage('Security Scan') {
            scanImage(backendImage, buildTag)
            scanImage(frontendImage, buildTag)
        }

        stage('Push to Harbor') {
            pushImage(backendImage, buildTag)
            pushImage(frontendImage, buildTag)
        }

        stage('Deploy to Production') {
            deployStack(COMPOSE_FILE, STACK_NAME, buildTag)
        }

        stage('Health Check') {
            echo "⏳ Waiting 30s for services to start..."
            sleep(30)
            healthCheck("http://${WORKER_IP}:5000/health", "Backend Production")
            healthCheck("http://${WORKER_IP}:80/", "Frontend Production")
        }

        echo """
        ✅✅✅ PRODUCTION PIPELINE SUCCESS ✅✅✅
        Backend:  http://${WORKER_IP}:5000
        Frontend: http://${WORKER_IP}
        Build:    #${buildTag}
        """

    } catch (Exception e) {
        echo "❌ PRODUCTION PIPELINE FAILED: ${e.getMessage()}"
        throw e
    } finally {
        sh 'docker system prune -f || true'
    }
}
