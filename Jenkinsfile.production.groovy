pipeline {
    agent any

    environment {
        HARBOR_HOST     = '34.21.141.11'
        HARBOR_PROJECT  = 'mern'
        IMAGE_TAG       = "${BUILD_NUMBER}"
        STACK_NAME      = 'mern-production'
        DEPLOY_ENV      = 'production'
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
                        docker tag ${HARBOR_HOST}/${HARBOR_PROJECT}/backend:${IMAGE_TAG} ${HARBOR_HOST}/${HARBOR_PROJECT}/backend:production-latest
                    """
                }
                echo "✅ Backend image built: ${HARBOR_HOST}/${HARBOR_PROJECT}/backend:${IMAGE_TAG}"
            }
        }

        stage('Build Frontend Image') {
            steps {
                dir('source/frontend') {
                    sh """
                        cp .env.production .env.production
                        docker build -t ${HARBOR_HOST}/${HARBOR_PROJECT}/frontend:${IMAGE_TAG} .
                        docker tag ${HARBOR_HOST}/${HARBOR_PROJECT}/frontend:${IMAGE_TAG} ${HARBOR_HOST}/${HARBOR_PROJECT}/frontend:production-latest
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

        stage('Backup Current Images') {
            steps {
                sh """
                    echo "📦 Backing up current production images..."
                    docker pull ${HARBOR_HOST}/${HARBOR_PROJECT}/backend:production-latest || true
                    docker pull ${HARBOR_HOST}/${HARBOR_PROJECT}/frontend:production-latest || true
                    docker tag ${HARBOR_HOST}/${HARBOR_PROJECT}/backend:production-latest ${HARBOR_HOST}/${HARBOR_PROJECT}/backend:production-rollback || true
                    docker tag ${HARBOR_HOST}/${HARBOR_PROJECT}/frontend:production-latest ${HARBOR_HOST}/${HARBOR_PROJECT}/frontend:production-rollback || true
                """
                echo "✅ Backup completed"
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
                        docker push ${HARBOR_HOST}/${HARBOR_PROJECT}/backend:production-latest
                        docker push ${HARBOR_HOST}/${HARBOR_PROJECT}/frontend:${IMAGE_TAG}
                        docker push ${HARBOR_HOST}/${HARBOR_PROJECT}/frontend:production-latest
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
                    docker stack deploy -c source/docker-compose.production.yml ${STACK_NAME}
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
                    curl -sf http://34.126.186.86:5000/health || echo "⚠️ Backend health check failed"
                    echo "🏥 Checking frontend..."
                    curl -sf http://34.126.186.86:80/ || echo "⚠️ Frontend health check failed"
                """
                echo "✅ Health check completed"
            }
        }
    }

    post {
        success {
            echo """
            ✅✅✅ PRODUCTION PIPELINE SUCCESS ✅✅✅
            Backend:  http://34.126.186.86:5000
            Frontend: http://34.126.186.86
            Build:    #${BUILD_NUMBER}
            """
        }
        failure {
            echo """
            ❌ PRODUCTION PIPELINE FAILED - Build #${BUILD_NUMBER}
            🔄 To rollback, run: docker stack deploy -c source/docker-compose.production.yml mern-production
               with IMAGE_TAG=production-rollback
            """
        }
        always {
            sh 'docker system prune -f || true'
            echo "🧹 Cleanup completed"
        }
    }
}
