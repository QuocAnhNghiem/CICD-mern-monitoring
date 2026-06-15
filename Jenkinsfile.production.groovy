// ==================== PRODUCTION PIPELINE ====================
// Branch: production
// Trigger: Manual - Merge từ staging sang production

// ==================== BIẾN TOÀN CỤC ====================
def HARBOR_HOST = '34.21.141.11'
def HARBOR_PROJECT = 'mern'
def HARBOR_CREDENTIAL = 'harbor-credentials'
def STACK_NAME = 'mern-production'
def COMPOSE_FILE = 'source/docker-compose.production.yml'
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

def backupImage(String imageName, String envTag) {
    echo "📦 Backing up: ${imageName}:${envTag}-latest"
    sh """
        docker pull ${imageName}:${envTag}-latest || true
        docker tag ${imageName}:${envTag}-latest ${imageName}:${envTag}-rollback || true
    """
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
        return true
    } else {
        echo "⚠️ ${name} health check failed!"
        return false
    }
}

def rollback(String composeFile, String stackName) {
    echo "🔄 Rolling back stack: ${stackName}..."
    sh """
        export HARBOR_HOST=${HARBOR_HOST}
        export IMAGE_TAG=production-rollback
        docker stack deploy -c ${composeFile} ${stackName}
    """
    echo "✅ Rollback hoàn tất!"
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
            name: 'CONFIRM_DEPLOY',
            defaultValue: false,
            description: '⚠️ Xác nhận deploy lên PRODUCTION (tích vào để deploy)'
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
            echo "✅ Checked out branch: ${env.BRANCH_NAME ?: 'production'}"
        }

        // ========== BUILD ==========
        stage('Build Images') {
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'backend') {
                buildImage('source/backend', backendImage, buildTag, 'production')
            }
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'frontend') {
                sh 'cp source/frontend/.env.production source/frontend/.env.production'
                buildImage('source/frontend', frontendImage, buildTag, 'production')
            }
        }

        // ========== SCAN ==========
        stage('Security Scan') {
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'backend') {
                scanImage(backendImage, buildTag)
            }
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'frontend') {
                scanImage(frontendImage, buildTag)
            }
        }

        // ========== BACKUP ==========
        stage('Backup Current Images') {
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'backend') {
                backupImage(backendImage, 'production')
            }
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'frontend') {
                backupImage(frontendImage, 'production')
            }
        }

        // ========== PUSH ==========
        stage('Push to Harbor') {
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'backend') {
                pushImage(backendImage, buildTag, 'production')
            }
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'frontend') {
                pushImage(frontendImage, buildTag, 'production')
            }
        }

        // ========== XÁC NHẬN DEPLOY ==========
        if (!params.CONFIRM_DEPLOY) {
            stage('Waiting for Approval') {
                input message: '⚠️ Bạn có chắc muốn deploy lên PRODUCTION không?',
                      ok: '🚀 Deploy ngay!'
            }
        }

        // ========== DEPLOY ==========
        stage('Deploy to Production') {
            echo "🚀 Deploying stack: ${STACK_NAME}"
            sh """
                export HARBOR_HOST=${HARBOR_HOST}
                export IMAGE_TAG=${buildTag}
                docker stack deploy -c ${COMPOSE_FILE} ${STACK_NAME}
            """
        }

        // ========== HEALTH CHECK ==========
        stage('Health Check') {
            echo "⏳ Waiting 30s for services to start..."
            sleep(30)
            def backendOk = healthCheck("http://${WORKER_IP}:5000/health", "Backend Production")
            def frontendOk = healthCheck("http://${WORKER_IP}:80/", "Frontend Production")

            if (!backendOk || !frontendOk) {
                echo "⚠️ Health check failed! Auto rollback..."
                rollback(COMPOSE_FILE, STACK_NAME)
                error("Health check failed after deploy!")
            }
        }

        echo """
        ✅✅✅ PRODUCTION PIPELINE SUCCESS ✅✅✅
        Services: ${params.BUILD_SERVICES}
        Backend:  http://${WORKER_IP}:5000
        Frontend: http://${WORKER_IP}
        Build:    #${buildTag}
        """

    } catch (Exception e) {
        echo "❌ PRODUCTION PIPELINE FAILED: ${e.getMessage()}"
        echo "🔄 Auto rollback triggered!"
        rollback(COMPOSE_FILE, STACK_NAME)
        throw e
    } finally {
        sh 'docker system prune -f || true'
    }
}
