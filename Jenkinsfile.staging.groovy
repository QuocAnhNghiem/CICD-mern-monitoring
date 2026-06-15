// ==================== STAGING PIPELINE ====================
// Branch: staging
// Trigger: Manual với nút bấm chọn Action (giống video)
// Actions: start | stop | up_code | rollback

// ==================== BIẾN TOÀN CỤC ====================
def HARBOR_HOST = '34.21.141.11'
def HARBOR_PROJECT = 'mern'
def HARBOR_CREDENTIAL = 'harbor-credentials'
def STACK_NAME = 'mern-staging'
def COMPOSE_FILE = 'source/docker-compose.staging.yml'
def WORKER_IP = '34.126.186.86'
def BUILD_ENV = 'staging'

// ==================== CÁC HÀM ====================

def buildImage(String context, String imageName, String tag, String buildEnv) {
    echo "🔨 Building image: ${imageName}:${tag}"
    if (buildEnv != null) {
        sh "docker build --build-arg BUILD_ENV=${buildEnv} -t ${imageName}:${tag} ${context}"
    } else {
        sh "docker build -t ${imageName}:${tag} ${context}"
    }
    sh "docker tag ${imageName}:${tag} ${imageName}:staging"
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
            docker push ${imageName}:staging
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

properties([
    parameters([
        choice(
            name: 'ACTION',
            choices: ['up_code', 'start', 'stop', 'rollback'],
            description: 'Chọn hành động:\n- up_code: Build + Push + Deploy code mới\n- start: Khởi động stack staging\n- stop: Dừng stack staging\n- rollback: Quay lại phiên bản trước'
        ),
        choice(
            name: 'BUILD_SERVICES',
            choices: ['all', 'backend', 'frontend'],
            description: 'Chọn service cần build (chỉ áp dụng cho action up_code)'
        ),
        string(
            name: 'ROLLBACK_VERSION',
            defaultValue: '',
            description: 'Nhập số Build để rollback (VD: 5). Chỉ dùng khi ACTION = rollback'
        )
    ])
])

node {
    def backendImage = "${HARBOR_HOST}/${HARBOR_PROJECT}/backend"
    def frontendImage = "${HARBOR_HOST}/${HARBOR_PROJECT}/frontend"
    def buildTag = env.BUILD_NUMBER

    try {
        // ==================== ACTION: STOP ====================
        if (params.ACTION == 'stop') {
            stage('Stop Staging') {
                echo "🛑 Stopping stack: ${STACK_NAME}"
                sh "docker stack rm ${STACK_NAME} || true"
                echo "✅ Stack ${STACK_NAME} stopped"
            }
            return
        }

        // ==================== ACTION: START ====================
        if (params.ACTION == 'start') {
            stage('Checkout') {
                checkout scm
            }
            stage('Start Staging') {
                deployStack(COMPOSE_FILE, STACK_NAME, 'staging')
            }
            stage('Health Check') {
                sleep(30)
                healthCheck("http://${WORKER_IP}:5001/health", "Backend")
                healthCheck("http://${WORKER_IP}:81/", "Frontend")
            }
            return
        }

        // ==================== ACTION: ROLLBACK ====================
        if (params.ACTION == 'rollback') {
            stage('Checkout') {
                checkout scm
            }
            stage('Rollback') {
                def rollbackTag = params.ROLLBACK_VERSION
                if (rollbackTag == '') {
                    error("❌ Bạn chưa nhập số Build để rollback!")
                }
                echo "🔄 Rolling back to build #${rollbackTag}"
                deployStack(COMPOSE_FILE, STACK_NAME, rollbackTag)
            }
            stage('Health Check') {
                sleep(30)
                healthCheck("http://${WORKER_IP}:5001/health", "Backend")
                healthCheck("http://${WORKER_IP}:81/", "Frontend")
            }
            return
        }

        // ==================== ACTION: UP_CODE ====================
        stage('Checkout') {
            checkout scm
            echo "✅ Checked out branch: staging"
        }

        stage('Build Images') {
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'backend') {
                buildImage('source/backend', backendImage, buildTag, null)
            }
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'frontend') {
                buildImage('source/frontend', frontendImage, buildTag, BUILD_ENV)
            }
        }

        stage('Security Scan') {
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'backend') {
                scanImage(backendImage, buildTag)
            }
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'frontend') {
                scanImage(frontendImage, buildTag)
            }
        }

        stage('Push to Harbor') {
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'backend') {
                pushImage(backendImage, buildTag)
            }
            if (params.BUILD_SERVICES == 'all' || params.BUILD_SERVICES == 'frontend') {
                pushImage(frontendImage, buildTag)
            }
        }

        stage('Deploy to Staging') {
            deployStack(COMPOSE_FILE, STACK_NAME, buildTag)
        }

        stage('Health Check') {
            echo "⏳ Waiting 30s for services to start..."
            sleep(30)
            healthCheck("http://${WORKER_IP}:5001/health", "Backend Staging")
            healthCheck("http://${WORKER_IP}:81/", "Frontend Staging")
        }

        echo """
        ✅✅✅ STAGING PIPELINE SUCCESS ✅✅✅
        Action:   ${params.ACTION}
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
