# JDBC capture agent — build / test / capture entry points.
#
# Notes for this machine:
#  - Maven is driven with Temurin 25 (JAVA_HOME below); the agent itself is compiled to
#    Java 8 bytecode (release 8) so the SINGLE jar runs on both JVM 8 and JVM 25.
#  - Docker (Rancher Desktop) lives in ~/.rd/bin, added to PATH here.
#  - The parent pom is intentionally minimal; each module is built via `mvn -f <module>/pom.xml`
#    so builds do not depend on the reactor listing every module.

JAVA_HOME ?= /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
export JAVA_HOME
export PATH := $(HOME)/.rd/bin:$(PATH)

MVN := mvn -q
PKG := $(MVN) -DskipTests package

.PHONY: all build build-agent build-spike build-legacy build-modern build-it \
        test capture-legacy capture-modern overhead pg-up pg-down clean

all: build

## Build everything (agent first, then samples, then tests).
build: build-agent build-spike build-legacy build-modern build-it
	@echo "[make] build complete"

build-agent:
	$(PKG) -f jdbc-capture-agent/pom.xml

build-spike:
	$(PKG) -f spike-app/pom.xml

build-legacy:
	$(PKG) -f sample-legacy/pom.xml

build-modern:
	$(PKG) -f sample-modern/pom.xml

build-it:
	$(MVN) -f integration-tests/pom.xml test-compile

## Run the end-to-end integration tests (needs Docker + built jars).
test: build-agent build-legacy build-modern
	$(MVN) -f integration-tests/pom.xml test

## Capture each sample into ./capture/<legacy|modern>/ (scenario/opts overridable).
##   make capture-legacy SCENARIO=empty OPTS="clock=2020-01-01T00:00:00Z"
SCENARIO ?= normal
OPTS ?=
capture-legacy:
	scripts/capture-legacy.sh "$(SCENARIO)" "$(OPTS)"

capture-modern:
	scripts/capture-modern.sh "$(SCENARIO)" "$(OPTS)"

## Measure agent overhead (with vs without) and print the delta.
overhead:
	scripts/measure-overhead.sh

pg-up:
	docker compose -f docker/docker-compose.yml up -d

pg-down:
	docker compose -f docker/docker-compose.yml down -v

clean:
	-$(MVN) -f jdbc-capture-agent/pom.xml clean
	-$(MVN) -f spike-app/pom.xml clean
	-$(MVN) -f sample-legacy/pom.xml clean
	-$(MVN) -f sample-modern/pom.xml clean
	-$(MVN) -f integration-tests/pom.xml clean
	-rm -rf capture
