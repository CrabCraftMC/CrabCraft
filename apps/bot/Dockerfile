FROM oven/bun:1.3.13-alpine

WORKDIR /app

COPY package.json bun.lock ./
COPY apps/bot/package.json ./apps/bot/
COPY packages/db/package.json ./packages/db/
COPY packages/shared/package.json ./packages/shared/
COPY packages/tsconfig/package.json ./packages/tsconfig/

RUN bun install --frozen-lockfile --production

COPY packages/ ./packages/
COPY apps/bot/src/ ./apps/bot/src/
COPY apps/bot/tsconfig.json ./apps/bot/

WORKDIR /app/apps/bot
CMD ["bun", "run", "src/index.ts"]
