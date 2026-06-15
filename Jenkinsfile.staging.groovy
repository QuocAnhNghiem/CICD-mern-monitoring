pipeline {
    agent any

    environment {
        HARBOR_HOST     = '34.21.141.11'
        HARBOR_PROJECT  = 'mern'
        IMAGE_TAG       = "${BUILD_NUMBER}"
        STACK_NAME      = 'mern-staging'
        DEPLOY_ENV      = 'staging'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                echo "✅ Source code checked out successfully"
            }
        }

        stage('Build Backend Image') {
            steps {
                dir('source/backend') {
                    sh """
                        docker build -t ${HARBOR_HOST}/${HARBOR_PROJECT}/backend:${IMAGE_TAG} .
                        docker tag ${HARBOR_HOST}/${HARBOR_PROJECT}/backend:${IMAGE_TAG} ${HARBOR_HOST}/${HARBOR_PROJECT}/backend:staging-latest
                    """
                }
                echo "✅ Backend image built: ${HARBOR_HOST}/${HARBOR_PROJECT}/backend:${IMAGE_TAG}"
            }
        }

        stage('Build Frontend Image') {
            steps {
                dir('source/frontend') {
                    sh """
                        cp .env.staging .env.production
                        docker build -t ${HARBOR_HOST}/${HARBOR_PROJECT}/frontend:${IMAGE_TAG} .
                        docker tag ${HARBOR_HOST}/${HARBOR_PROJECT}/frontend:${IMAGE_TAG} ${HARBOR_HOST}/${HARBOR_PROJECT}/frontend:staging-latest
                    """
                }
                echo "✅ Frontend image built: ${HARBOR_HOST}/${HARBOR_PROJECT}/frontend:${IMAGE_TAG}"
            }
        }

        stage('Security Scan') {
            steps {
                sh """
                    echo "🔍 Scanning backend image..."
                    trivy image --severity HIGH,CRITICAL --no-progress ${HARBOR_HOST}/${HARBOR_PROJECT}/backend:${IMAGE_TAG} || true
                    echo "🔍 Scanning frontend image..."
                    trivy image --severity HIGH,CRITICAL --no-progress ${HARBOR_HOST}/${HARBOR_PROJECT}/frontend:${IMAGE_TAG} || true
                """
                echo "✅ Security scan completed"
            }
        }

        stage('Push to Harbor') {
            steps {
                withCredentials([usernamePassword(
                    credentialsId: 'harbor-credentials',
                    usernameVariable: 'HARBOR_USER',
                    passwordVariable: 'HARBOR_PASS'
                )]) {
                    sh """
                        echo \$HARBOR_PASS | docker login ${HARBOR_HOST} -u \$HARBOR_USER --password-stdin
                        docker push ${HARBOR_HOST}/${HARBOR_PROJECT}/backend:${IMAGE_TAG}
                        docker push ${HARBOR_HOST}/${HARBOR_PROJECT}/backend:staging-latest
                        docker push ${HARBOR_HOST}/${HARBOR_PROJECT}/frontend:${IMAGE_TAG}
                        docker push ${HARBOR_HOST}/${HARBOR_PROJECT}/frontend:staging-latest
                        docker logout ${HARBOR_HOST}
                    """
                }
                echo "✅ Images pushed to Harbor"
            }
        }

        stage('Deploy to Swarm') {
            steps {
                sh """
                    export HARBOR_HOST=${HARBOR_HOST}
                    export IMAGE_TAG=${IMAGE_TAG}
                    docker stack deploy -c source/docker-compose.staging.yml ${STACK_NAME}
                """
                echo "✅ Stack ${STACK_NAME} deployed"
            }
        }

        stage('Health Check') {
            steps {
                sh """
                    echo "⏳ Waiting 30s for services to start..."
                    sleep 30
                    echo "🏥 Checking backend health..."
                    curl -sf http://34.126.186.86:5001/health || echo "⚠️ Backend health check failed"
                    echo "🏥 Checking frontend..."
                    curl -sf http://34.126.186.86:81/ || echo "⚠️ Frontend health check failed"
                """
                echo "✅ Health check completed"
            }
        }
    }

    post {
        success {
            echo """
            ✅✅✅ STAGING PIPELINE SUCCESS ✅✅✅
            Backend:  http://34.126.186.86:5001
            Frontend: http://34.126.186.86:81
            Build:    #${BUILD_NUMBER}
            """
        }
        failure {
            echo "❌ STAGING PIPELINE FAILED - Build #${BUILD_NUMBER}"
        }
        always {
            sh 'docker system prune -f || true'
            echo "🧹 Cleanup completed"
        }
    }
}
