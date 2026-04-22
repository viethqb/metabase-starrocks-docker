# syntax=docker/dockerfile:1.6
# -----------------------------------------------------------------------------
# Stage 1: Clone Metabase, inject StarRocks driver, build uberjar + driver jar
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS builder

# Build-time knobs
ARG METABASE_REPO=https://github.com/metabase/metabase.git
ARG METABASE_REF=master
ARG MB_EDITION=ee
ARG DRIVER_NAME=starrocks

ENV DEBIAN_FRONTEND=noninteractive

# System deps: git, curl, nodejs, yarn, bun, uv (python), clojure
RUN apt-get update && apt-get install -y --no-install-recommends \
        git curl ca-certificates rlwrap bash make gnupg unzip python3 \
    && curl -fsSL https://deb.nodesource.com/setup_20.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && npm install -g yarn \
    && curl -fsSL https://bun.sh/install | bash \
    && ln -s /root/.bun/bin/bun /usr/local/bin/bun \
    && curl -LsSf https://astral.sh/uv/install.sh | sh \
    && ln -s /root/.local/bin/uv /usr/local/bin/uv \
    && ln -s /root/.local/bin/uvx /usr/local/bin/uvx \
    && curl -L -O https://download.clojure.org/install/linux-install-1.12.0.1488.sh \
    && chmod +x linux-install-1.12.0.1488.sh \
    && ./linux-install-1.12.0.1488.sh \
    && rm -rf /var/lib/apt/lists/* linux-install-*.sh

WORKDIR /build

# Clone Metabase
RUN git clone --depth 1 --branch "${METABASE_REF}" "${METABASE_REPO}" metabase

WORKDIR /build/metabase

# Inject StarRocks driver source.
# - Expects the driver directory to be provided in the build context under `starrocks/`
#   (matching the layout in `modules/drivers/starrocks/`).
# - Also expects the patched `modules/drivers/deps.edn` registering the driver.
COPY starrocks/ modules/drivers/starrocks/
COPY drivers-deps.edn modules/drivers/deps.edn

# Apply EE license bypass so built-in impersonation / advanced-permissions
# features work without a paid token (required because `has-feature?` otherwise
# returns false for every premium feature in a fresh build).
# Pattern-based (perl) so it survives upstream line-number drift.
COPY apply-ee-bypass.sh /tmp/apply-ee-bypass.sh
RUN bash /tmp/apply-ee-bypass.sh && rm /tmp/apply-ee-bypass.sh

# Build the uberjar (frontend + backend). MB_EDITION=ee bundles enterprise code.
ENV MB_EDITION=${MB_EDITION}
RUN ./bin/build.sh

# Build the StarRocks driver plugin jar (lands in resources/modules/)
RUN ./bin/build-driver.sh "${DRIVER_NAME}"

# `./bin/build.sh` bundles a copy of `modules/starrocks.metabase-driver.jar` INSIDE
# `metabase.jar` using whatever was in `resources/modules/` at that moment (often a
# stale copy from an earlier compile iteration, because Clojure .cpcache persists).
# At runtime, Metabase's plugin init re-extracts that stale bundled jar over the
# freshly-COPY-ed file in `/plugins/`, silently reverting us to old driver code.
# Strip the bundled entry so the only surviving copy is the one we place directly
# into `/plugins/` in the runtime stage.
RUN apt-get update && apt-get install -y --no-install-recommends zip \
    && zip -d target/uberjar/metabase.jar "modules/${DRIVER_NAME}.metabase-driver.jar" \
    && apt-get purge -y zip && rm -rf /var/lib/apt/lists/*

# -----------------------------------------------------------------------------
# Stage 2: Runtime (JRE + Metabase jar + StarRocks plugin)
# -----------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

ENV FC_LANG=en-US LC_CTYPE=en_US.UTF-8

RUN apk add --no-cache bash fontconfig curl \
        font-noto font-noto-arabic font-noto-hebrew font-noto-cjk font-noto-thai \
        java-cacerts \
    && apk upgrade \
    && mkdir -p /app /plugins \
    && chmod a+rwx /plugins

# Metabase uberjar + run script
COPY --from=builder /build/metabase/target/uberjar/metabase.jar /app/metabase.jar
COPY --from=builder /build/metabase/bin/docker/run_metabase.sh /app/run_metabase.sh

# Drop the driver jar into /plugins so Metabase auto-loads it on boot
COPY --from=builder /build/metabase/resources/modules/starrocks.metabase-driver.jar /plugins/

RUN chmod +x /app/run_metabase.sh

EXPOSE 3000

ENTRYPOINT ["/app/run_metabase.sh"]
