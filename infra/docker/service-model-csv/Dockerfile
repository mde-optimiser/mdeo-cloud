# Build stage
FROM node:24-alpine AS builder

WORKDIR /build
COPY app/ ./

RUN npm ci
RUN npm run build:packages
RUN npm run build:css
RUN npm run -w @mdeo/service-model-csv build:static

# Production stage
FROM node:24-alpine

# Install curl for healthchecks
RUN apk add --no-cache curl

ARG SERVICE_VERSION=""

WORKDIR /workspace

# Copy the entire packages structure with built artifacts and dependencies
COPY --from=builder /build/packages /workspace/packages
COPY --from=builder /build/node_modules /workspace/node_modules
COPY --from=builder /build/package.json /workspace/

# Set working directory to service-model-csv
WORKDIR /workspace/packages/service-model-csv

# Set environment variables
ENV NODE_ENV=production
ENV PORT=3000
ENV HOST=0.0.0.0
ENV SERVICE_VERSION=${SERVICE_VERSION}

EXPOSE 3000

CMD ["node", "dist/index.js"]
