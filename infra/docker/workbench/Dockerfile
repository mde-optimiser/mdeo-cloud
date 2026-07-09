# Workbench builder
FROM node:22-alpine AS workbench-builder

WORKDIR /build
COPY app/ ./

RUN npm ci
RUN npm run build

# Workbench runtime (nginx)
FROM nginx:alpine AS workbench

# Copy built assets
COPY --from=workbench-builder /build/packages/workbench/dist /usr/share/nginx/html

# Copy custom nginx configuration
RUN rm /etc/nginx/conf.d/default.conf
COPY infra/docker/workbench/nginx.conf /etc/nginx/templates/default.conf.template

EXPOSE 80
