# Build stage
FROM node:18-alpine AS build-stage
ARG BUILD_ENV=production
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build:${BUILD_ENV}

# Production stage - dùng nginx-unprivileged (đã hỗ trợ non-root sẵn)
FROM nginxinc/nginx-unprivileged:alpine AS production-stage

COPY --from=build-stage /app/build /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080

CMD ["nginx", "-g", "daemon off;"]
