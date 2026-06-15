FROM node:18-alpine

# Tạo non-root user
RUN addgroup -g 1001 -S appgroup && adduser -u 1001 -S appuser -G appgroup

WORKDIR /app

# Copy package files trước để tận dụng Docker cache
COPY package*.json ./
RUN npm ci --production

# Copy source code and set ownership in a single layer to optimize image size
COPY --chown=appuser:appgroup . .

USER appuser

EXPOSE 5000

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD wget -qO- http://localhost:5000/health || exit 1

CMD ["node", "server.js"]
