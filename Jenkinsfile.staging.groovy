// ==================== STAGING PIPELINE ====================
// Branch: staging
// Trigger: Manual với nút bấm chọn Action (Active Choices)
// Actions: start | stop | up_code | rollback

// ==================== BIẾN TOÀN CỤC ====================
// Không dùng 'def' để biến có thể truy cập từ mọi hàm (Jenkins CPS requirement)
HARBOR_HOST = '34.21.141.11'
HARBOR_PROJECT = 'mern'
HARBOR_CREDENTIAL = 'harbor-credentials'
STACK_NAME = 'mern-staging'
COMPOSE_FILE = 'source/docker-compose.staging.yml'
WORKER_IP = '34.126.186.86'
BUILD_ENV = 'staging'

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
    echo "🚀 Deploying stack: ${stackName} with tag: ${imageTag}"
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

// ==================== PARAMETERS (Active Choices) ====================

properties([
    parameters([
        // Nút chọn Action chính
        [$class: 'ChoiceParameter',
            name: 'ACTION',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Chọn hành động:\n- up_code: Build + Push + Deploy code mới\n- start: Khởi động stack\n- stop: Dừng stack\n- rollback: Quay lại phiên bản cũ',
            script: [$class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: "return ['up_code', 'start', 'stop', 'rollback']"
                ],
                fallbackScript: [
                    classpath: [],
                    sandbox: true,
                    script: "return ['up_code']"
                ]
            ]
        ],

        // Nút chọn Service (chỉ hiện khi ACTION = up_code)
        [$class: 'CascadeChoiceParameter',
            name: 'BUILD_SERVICES',
            choiceType: 'PT_SINGLE_SELECT',
            referencedParameters: 'ACTION',
            description: 'Chọn service cần build (chỉ dùng khi ACTION = up_code)',
            script: [$class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: """
                        if (ACTION == 'up_code') {
                            return ['all', 'backend', 'frontend']
                        }
                        return ['N/A - Không cần chọn']
                    """
                ],
                fallbackScript: [
                    classpath: [],
                    sandbox: true,
                    script: "return ['all']"
                ]
            ]
        ],

        // Dropdown danh sách phiên bản để Rollback (chỉ hiện khi ACTION = rollback)
        [$class: 'CascadeChoiceParameter',
            name: 'ROLLBACK_VERSION',
            choiceType: 'PT_SINGLE_SELECT',
            referencedParameters: 'ACTION',
            description: 'Chọn phiên bản để rollback (chỉ dùng khi ACTION = rollback)',
            script: [$class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: """
                        if (ACTION == 'rollback') {
                            def tags = []
                            try {
                                def proc = ['docker', 'images', '--format', '{{.Tag}}', '34.21.141.11/mern/backend'].execute()
                                proc.waitFor()
                                proc.text.trim().split('\\n').each { tag ->
                                    if (tag && tag != 'staging' && tag != '<none>' && tag != '') {
                                        tags.add(tag)
                                    }
                                }
                            } catch (Exception e) {
                                tags.add('Lỗi: ' + e.getMessage())
                            }
                            if (tags.isEmpty()) {
                                return ['Chưa có phiên bản nào']
                            }
                            return tags.sort { a, b -> b.toInteger() <=> a.toInteger() }
                        }
                        return ['N/A - Không cần chọn']
                    """
                ],
                fallbackScript: [
                    classpath: [],
                    sandbox: true,
                    script: "return ['Không thể tải danh sách phiên bản']"
                ]
            ]
        ]
    ])
])

// ==================== PIPELINE CHÍNH ====================

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
                if (rollbackTag == 'N/A - Không cần chọn' || rollbackTag == 'Chưa có phiên bản nào') {
                    error("❌ Không có phiên bản hợp lệ để rollback!")
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
