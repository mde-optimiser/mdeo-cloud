FROM node:24-alpine AS builder

WORKDIR /build
COPY app/ ./

RUN npm ci
RUN npm run build:packages
RUN npm run -w @mdeo/service-csv build:static

FROM node:24-alpine

RUN apk add --no-cache curl

WORKDIR /workspace

COPY --from=builder /build/packages /workspace/packages
COPY --from=builder /build/node_modules /workspace/node_modules
COPY --from=builder /build/package.json /workspace/

WORKDIR /workspace/packages/service-csv

ENV NODE_ENV=production
ENV PORT=3000
ENV HOST=0.0.0.0

EXPOSE 3000

CMD ["node", "dist/index.js"]
