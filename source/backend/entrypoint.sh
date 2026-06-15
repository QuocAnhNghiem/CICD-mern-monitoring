#!/bin/sh
# Entrypoint script - Load Docker Secrets as environment variables
# Docker Secret mount file vào /run/secrets/<tên_secret>
# Script này đọc file đó và export thành biến môi trường

if [ -f /run/secrets/backend_env ]; then
    echo "📦 Loading environment from Docker Secret..."
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            ''|\#*) continue ;;
            *=*) export "$line" ;;
            *) echo "⚠️ Ignoring invalid environment line in backend_env secret" ;;
        esac
    done < /run/secrets/backend_env
    echo "✅ Environment loaded successfully"
fi

# Chạy lệnh truyền vào (node server.js)
exec "$@"
