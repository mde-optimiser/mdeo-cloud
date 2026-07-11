FROM node:22-alpine AS service-csv-builder

WORKDIR /build

COPY app/ ./

RUN npm ci
RUN npm run build:packages
RUN npm run -w @mdeo/service-csv build:static

FROM node:22-alpine AS service-csv

WORKDIR /app

COPY --from=service-csv-builder /build/packages/service-csv/package.json ./package.json
COPY --from=service-csv-builder /build/packages/service-csv/dist ./dist
COPY --from=service-csv-builder /build/packages/service-csv/static ./static
COPY --from=service-csv-builder /build/node_modules ./node_modules

ENV PORT=3000
ENV HOST=0.0.0.0

EXPOSE 3000

CMD ["node", "dist/index.js"]
