# Stage 1: Install dependencies
FROM oven/bun:1.3.14 AS deps
WORKDIR /app
COPY package.json bun.lock ./
COPY apps/bot/package.json ./apps/bot/
COPY apps/web/package.json ./apps/web/
COPY apps/minecraft/package.json ./apps/minecraft/
COPY packages/db/package.json ./packages/db/
COPY packages/shared/package.json ./packages/shared/
COPY packages/tsconfig/package.json ./packages/tsconfig/
RUN bun install --frozen-lockfile

# Stage 2: Build
FROM oven/bun:1.3.14 AS builder
WORKDIR /app

# Copy source first, then overlay installed node_modules
COPY . .
COPY --from=deps /app/node_modules ./node_modules
# Workspace node_modules may or may not exist depending on Bun version/hoisting
COPY --from=deps /app/apps/web/node_module[s] ./apps/web/node_modules
COPY --from=deps /app/packages/db/node_module[s] ./packages/db/node_modules
COPY --from=deps /app/packages/shared/node_module[s] ./packages/shared/node_modules

ARG GIT_COMMIT_SHA
ENV GIT_COMMIT_SHA=${GIT_COMMIT_SHA}

WORKDIR /app/apps/web
RUN bun run build

# Stage 3: Production runtime
FROM node:22-alpine AS runner
WORKDIR /app

ENV NODE_ENV=production
ENV NEXT_SHARP_PATH=/app/node_modules/sharp

RUN addgroup --system --gid 1001 nodejs && \
    adduser --system --uid 1001 nextjs && \
    apk add --no-cache libc6-compat curl bash && \
    curl -1sLf 'https://dl.cloudsmith.io/public/infisical/infisical-cli/setup.alpine.sh' | bash && \
    apk add --no-cache infisical

COPY --from=builder --chown=nextjs:nodejs /app/apps/web/.next/standalone ./
COPY --from=builder --chown=nextjs:nodejs /app/apps/web/public ./apps/web/public
COPY --from=builder --chown=nextjs:nodejs /app/apps/web/.next/static ./apps/web/.next/static

ARG GIT_COMMIT_SHA
ENV GIT_COMMIT_SHA=${GIT_COMMIT_SHA}

USER nextjs
EXPOSE 3000
ENV PORT=3000
ENV HOSTNAME="0.0.0.0"

CMD INFISICAL_TOKEN=$(infisical login --method=universal-auth --client-id=${INFISICAL_CLIENT_ID} --client-secret=${INFISICAL_CLIENT_SECRET} --domain https://eu.infisical.com --silent --plain) infisical run --domain https://eu.infisical.com --env=${INFISICAL_ENV:-prod} --projectId=bc702274-f9a0-49fd-9932-b63c23a6d702 -- node apps/web/server.js
