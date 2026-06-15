# Build stage
FROM node:18-alpine AS build-stage
ARG BUILD_ENV=production
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build:${BUILD_ENV}

# Production stage
FROM nginx:alpine AS production-stage

RUN addgroup -g 1001 -S appgroup && adduser -u 1001 -S appuser -G appgroup
RUN mkdir /app

COPY --from=build-stage /app/build /app
COPY --chown=appuser:appgroup nginx.conf /etc/nginx/conf.d/default.conf

# Fix non-root nginx: PID file + remove user directive + set permissions
RUN sed -i 's|/run/nginx.pid|/tmp/nginx.pid|g' /etc/nginx/nginx.conf && \
    sed -i '/^user /d' /etc/nginx/nginx.conf && \
    chown -R appuser:appgroup /app /var/cache/nginx /var/log/nginx /etc/nginx/conf.d /tmp

USER appuser

EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]
