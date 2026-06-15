// ==================== STAGING PIPELINE ====================
// Branch: staging
// Trigger: Push code hoặc Manual với nút bấm chọn

// ==================== BIẾN TOÀN CỤC ====================
def HARBOR_HOST = '34.21.141.11'
def HARBOR_PROJECT = 'mern'
def HARBOR_CREDENTIAL = 'harbor-credentials'
def STACK_NAME = 'mern-staging'
def COMPOSE_FILE = 'source/docker-compose.staging.yml'
def WORKER_IP = '34.126.186.86'

// ==================== CÁC HÀM ====================

def buildImage(String context, String imageName, String tag, String envTag) {
    echo "🔨 Building image: ${imageName}:${tag}"
    sh "docker build -t ${imageName}:${tag} ${context}"
    sh "docker tag ${imageName}:${tag} ${imageName}:${envTag}-latest"
}

def scanImage(String imageName, String tag) {
    echo "🔍 Scanning: ${imageName}:${tag}"
    sh "trivy image --severity HIGH,CRITICAL --no-progress ${imageName}:${tag} || true"
}

def pushImage(String imageName, String tag, String envTag) {
    withCredentials([usernamePassword(
        credentialsId: HARBOR_CREDENTIAL,
        usernameVariable: 'HARBOR_USER',
        passwordVariable: 'HARBOR_PASS'
    )]) {
        sh """
            echo \$HARBOR_PASS | docker login ${HARBOR_HOST} -u \$HARBOR_USER --password-stdin
            docker push ${imageName}:${tag}
            docker push ${imageName}:${envTag}-latest
            docker logout ${HARBOR_HOST}
        """
    }
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

properties([
    parameters([
        choice(
            name: 'BUILD_SERVICES',
            choices: ['all', 'backend', 'frontend'],
            description: 'Chọn service cần build và deploy'
        ),
        booleanParam(
            name: 'SKIP_SCAN',
            defaultValue: false,
            description: 'Bỏ qua bước Security Scan (để test nhanh)'
        ),
        booleanParam(
            name: 'SKIP_DEPLOY',
            defaultValue: false,
            description: 'Chỉ Build + Push, không Deploy'
        )
    ])
])

node {
    def backendImage = "${HARBOR_HOST}/${HARBOR_PROJECT}/backend"
    def frontendImage = "${HARBOR_HOST}/${HARBOR_PROJECT}/frontend"
    def buildTag = env.BUILD_NUMBER

    try {
        stage('Checkout') {
            checkout scm
            echo "✅ Checked out branch: ${env.BRANCH_NAME ?: 'staging'}"
        }

        // ========== BUILD ==========
        stage('Build Images') {
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'backend') {
                echo ">>> Building Backend..."
                buildImage('source/backend', backendImage, buildTag, 'staging')
            }
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'frontend') {
                echo ">>> Building Frontend..."
                sh 'cp source/frontend/.env.staging source/frontend/.env.production'
                buildImage('source/frontend', frontendImage, buildTag, 'staging')
            }
        }

        // ========== SCAN ==========
        if (!params.SKIP_SCAN) {
            stage('Security Scan') {
                if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'backend') {
                    scanImage(backendImage, buildTag)
                }
                if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'frontend') {
                    scanImage(frontendImage, buildTag)
                }
            }
        } else {
            echo "⏭️ Skipping Security Scan"
        }

        // ========== PUSH ==========
        stage('Push to Harbor') {
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'backend') {
                pushImage(backendImage, buildTag, 'staging')
            }
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'frontend') {
                pushImage(frontendImage, buildTag, 'staging')
            }
        }

        // ========== DEPLOY ==========
        if (!params.SKIP_DEPLOY) {
            stage('Deploy to Staging') {
                echo "🚀 Deploying stack: ${STACK_NAME}"
                sh """
                    export HARBOR_HOST=${HARBOR_HOST}
                    export IMAGE_TAG=${buildTag}
                    docker stack deploy -c ${COMPOSE_FILE} ${STACK_NAME}
                """
            }

            stage('Health Check') {
                echo "⏳ Waiting 30s for services to start..."
                sleep(30)
                healthCheck("http://${WORKER_IP}:5001/health", "Backend Staging")
                healthCheck("http://${WORKER_IP}:81/", "Frontend Staging")
            }
        } else {
            echo "⏭️ Skipping Deploy"
        }

        echo """
        ✅✅✅ STAGING PIPELINE SUCCESS ✅✅✅
        Services: ${params.BUILD_SERVICES}
        Backend:  http://${WORKER_IP}:5001
        Frontend: http://${WORKER_IP}:81
        Build:    #${buildTag}
        """

    } catch (Exception e) {
        echo "❌ STAGING PIPELINE FAILED: ${e.getMessage()}"
        throw e
    } finally {
        sh 'docker system prune -f || true'
    }
}
