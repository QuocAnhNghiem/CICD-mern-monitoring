// ==================== PRODUCTION PIPELINE ====================
// Branch: main
// Trigger: Tự động khi merge staging → main (hoặc push tag)
// Không có nút bấm - chạy tự động hoàn toàn

// ==================== BIẾN TOÀN CỤC ====================
// Không dùng 'def' để biến có thể truy cập từ mọi hàm (Jenkins CPS requirement)
HARBOR_HOST = env.HARBOR_HOST ?: '34.21.141.11'
HARBOR_PROJECT = env.HARBOR_PROJECT ?: 'mern'
HARBOR_CREDENTIAL = env.HARBOR_CREDENTIAL ?: 'harbor-credentials'
STACK_NAME = env.PRODUCTION_STACK_NAME ?: 'mern-production'
COMPOSE_FILE = env.PRODUCTION_COMPOSE_FILE ?: 'source/docker-compose.production.yml'
WORKER_IP = env.PRODUCTION_WORKER_IP ?: '34.126.186.86'
BUILD_ENV = 'production'

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
    sh """
        docker run --rm \
            -v /var/run/docker.sock:/var/run/docker.sock \
            aquasec/trivy:latest image \
            --severity HIGH,CRITICAL --exit-code 1 --no-progress \
            ${imageName}:${tag}
    """
}

def testBackend() {
    dir('source/backend') {
        sh 'npm ci'
        sh 'npm test'
    }
}

def testFrontend() {
    dir('source/frontend') {
        sh 'npm ci'
        sh 'CI=true npm test'
    }
}

def pushImage(String imageName, String tag) {
    withCredentials([usernamePassword(
        credentialsId: HARBOR_CREDENTIAL,
        usernameVariable: 'HARBOR_USER',
        passwordVariable: 'HARBOR_PASS'
    )]) {
        sh """
            set +x
            echo \$HARBOR_PASS | docker login ${HARBOR_HOST} -u \$HARBOR_USER --password-stdin
            set -x
            docker push ${imageName}:${tag}
            docker push ${imageName}:production
            docker logout ${HARBOR_HOST}
        """
    }
}

def deployStack(String composeFile, String stackName, String imageTag) {
    echo "🚀 Deploying stack: ${stackName} with tag: ${imageTag}"
    withCredentials([usernamePassword(
        credentialsId: HARBOR_CREDENTIAL,
        usernameVariable: 'HARBOR_USER',
        passwordVariable: 'HARBOR_PASS'
    )]) {
        sh """
            set +x
            echo \$HARBOR_PASS | docker login ${HARBOR_HOST} -u \$HARBOR_USER --password-stdin
            set -x
            export HARBOR_HOST=${HARBOR_HOST}
            export IMAGE_TAG=${imageTag}
            docker stack deploy --with-registry-auth -c ${composeFile} ${stackName}
            docker logout ${HARBOR_HOST}
        """
    }
}

def healthCheck(String url, String name) {
    int maxAttempts = 10
    int waitSeconds = 6

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        def result = sh(script: "curl -sf ${url}", returnStatus: true)
        if (result == 0) {
            echo "✅ ${name} is healthy!"
            return
        }
        echo "⚠️ ${name} health check failed (${attempt}/${maxAttempts})"
        if (attempt < maxAttempts) {
            sleep(waitSeconds)
        }
    }

    error("❌ ${name} health check failed after ${maxAttempts} attempts: ${url}")
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

        stage('Test') {
            testBackend()
            testFrontend()
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
        echo 'ℹ️ Skipping automatic docker system prune. Run a dedicated cleanup job when needed.'
    }
}
