# Stage 1: Build React app
FROM node:18-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# Stage 2: Serve with Nginx
FROM nginx:alpine

# Tạo non-root user
RUN addgroup -g 1001 -S appgroup && adduser -u 1001 -S appuser -G appgroup

# Copy build output and set ownership
COPY --from=build --chown=appuser:appgroup /app/build /usr/share/nginx/html

# Copy nginx config and set ownership
COPY --chown=appuser:appgroup nginx.conf /etc/nginx/conf.d/default.conf

# Chuyển ownership cho các thư mục cache và log của Nginx
RUN chown -R appuser:appgroup /var/cache/nginx /var/log/nginx

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:8080/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
