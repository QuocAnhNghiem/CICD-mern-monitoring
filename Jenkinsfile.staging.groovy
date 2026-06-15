// ==================== STAGING PIPELINE ====================
// Branch: staging
// Trigger: Manual với nút bấm chọn Action (Active Choices)
// Actions: start | stop | up_code | rollback

// ==================== BIẾN TOÀN CỤC ====================
// Không dùng 'def' để biến có thể truy cập từ mọi hàm (Jenkins CPS requirement)
HARBOR_HOST = env.HARBOR_HOST ?: '34.21.141.11'
HARBOR_PROJECT = env.HARBOR_PROJECT ?: 'mern'
HARBOR_CREDENTIAL = env.HARBOR_CREDENTIAL ?: 'harbor-credentials'
STACK_NAME = env.STAGING_STACK_NAME ?: 'mern-staging'
COMPOSE_FILE = env.STAGING_COMPOSE_FILE ?: 'source/docker-compose.staging.yml'
WORKER_IP = env.STAGING_WORKER_IP ?: '34.126.186.86'
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
    sh """
        docker run --rm \
            -v /var/run/docker.sock:/var/run/docker.sock \
            aquasec/trivy:latest image \
            --severity HIGH,CRITICAL --exit-code 0 --no-progress \
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

def testSelectedServices(String selectedServices) {
    if (selectedServices == 'all' || selectedServices == 'backend') {
        testBackend()
    }
    if (selectedServices == 'all' || selectedServices == 'frontend') {
        testFrontend()
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
            docker push ${imageName}:staging
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

def updateService(String stackName, String serviceName, String imageName, String imageTag) {
    echo "🚀 Updating service: ${stackName}_${serviceName} -> ${imageName}:${imageTag}"
    withCredentials([usernamePassword(
        credentialsId: HARBOR_CREDENTIAL,
        usernameVariable: 'HARBOR_USER',
        passwordVariable: 'HARBOR_PASS'
    )]) {
        sh """
            set +x
            echo \$HARBOR_PASS | docker login ${HARBOR_HOST} -u \$HARBOR_USER --password-stdin
            set -x
            docker service update --with-registry-auth \
                --image ${imageName}:${imageTag} \
                ${stackName}_${serviceName}
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

def healthCheckUpdatedServices(String selectedServices) {
    if (selectedServices == 'all' || selectedServices == 'backend') {
        healthCheck("http://${WORKER_IP}:5001/health", "Backend Staging")
    }
    if (selectedServices == 'all' || selectedServices == 'frontend') {
        healthCheck("http://${WORKER_IP}:81/", "Frontend Staging")
    }
}

// ==================== PARAMETERS (Active Choices) ====================

properties([
    parameters([
        // Nút chọn Action chính
        [$class: 'ChoiceParameter',
            name: 'ACTION',
            choiceType: 'PT_SINGLE_SELECT',
            description: 'Chọn hành động:\n- up_code: Build + Push + Deploy code mới\n- start: Khởi động stack\n- stop: Dừng stack\n- rollback: Quay lại phiên bản cũ\n- cleanup: Dọn Docker cache an toàn',
            script: [$class: 'GroovyScript',
                script: [
                    classpath: [],
                    sandbox: true,
                    script: "return ['up_code', 'start', 'stop', 'rollback', 'cleanup']"
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
                                def proc = ['docker', 'images', '--format', '{{.Tag}}', '${HARBOR_HOST}/${HARBOR_PROJECT}/backend'].execute()
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

        // ==================== ACTION: CLEANUP ====================
        if (params.ACTION == 'cleanup') {
            stage('Cleanup Docker Cache') {
                echo "🧹 Cleaning Docker build cache and dangling images older than 24h"
                sh 'docker builder prune -f --filter until=24h || true'
                sh 'docker image prune -f --filter until=24h || true'
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

        stage('Test') {
            testSelectedServices(params.BUILD_SERVICES)
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
            if (params.BUILD_SERVICES == 'all') {
                deployStack(COMPOSE_FILE, STACK_NAME, buildTag)
            } else if (params.BUILD_SERVICES == 'backend') {
                updateService(STACK_NAME, 'backend', backendImage, buildTag)
            } else if (params.BUILD_SERVICES == 'frontend') {
                updateService(STACK_NAME, 'frontend', frontendImage, buildTag)
            } else {
                error("❌ BUILD_SERVICES không hợp lệ: ${params.BUILD_SERVICES}")
            }
        }

        stage('Health Check') {
            echo "⏳ Waiting 30s for services to start..."
            sleep(30)
            healthCheckUpdatedServices(params.BUILD_SERVICES)
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
        echo 'ℹ️ Skipping automatic docker system prune. Run a dedicated cleanup job when needed.'
    }
}
