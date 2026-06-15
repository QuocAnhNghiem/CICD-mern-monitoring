#!/bin/sh
# Entrypoint script - Load Docker Secrets as environment variables
# Docker Secret mount file vào /run/secrets/<tên_secret>
# Script này đọc file đó và export thành biến môi trường

if [ -f /run/secrets/backend_env ]; then
    echo "📦 Loading environment from Docker Secret..."
    export $(cat /run/secrets/backend_env | grep -v '^#' | grep -v '^$' | xargs)
    echo "✅ Environment loaded successfully"
fi

# Chạy lệnh truyền vào (node server.js)
exec "$@"
