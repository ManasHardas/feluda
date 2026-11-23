# Neighborhood Watchdog: common commands. Run `make help` for the list.
SHELL := /bin/bash
.DEFAULT_GOAL := help

BACKEND := backend
ANDROID := apps/android
IOS := apps/ios
DEV_DB := postgresql://watchdog:watchdog_local_dev_only@localhost:54329/watchdog
DEV_ADMIN_TOKEN := local-development-admin-token-not-for-production
DEV_ENV := ENVIRONMENT=development DATABASE_URL=$(DEV_DB) ADMIN_API_TOKEN=$(DEV_ADMIN_TOKEN)
# macOS with Homebrew: JDK 17 and the Android command-line tools. Override in your environment if different.
export JAVA_HOME ?= /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME ?= /opt/homebrew/share/android-commandlinetools
IOS_DESTINATION ?= platform=iOS Simulator,name=iPhone 17

.PHONY: help setup docker-up docker-down migrate seed run simulate backend-lint backend-unit backend-test \
	backend-test-docker live-backend live-ios live-android ios-core-test openapi openapi-check android-build android-test android-release ios-generate ios-build \
	ios-test test sbom clean

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*## ' $(MAKEFILE_LIST) | awk -F':.*## ' '{printf "  %-22s %s\n", $$1, $$2}'

setup: ## Install backend dependencies (needs uv: https://docs.astral.sh/uv/)
	cd $(BACKEND) && uv sync --frozen

docker-up: ## Start the local PostgreSQL container
	docker compose up -d --wait db

docker-down: ## Stop local containers (data is kept in a volume)
	docker compose down

migrate: ## Apply database migrations to the local database
	cd $(BACKEND) && DATABASE_URL=$(DEV_DB) uv run python -m watchdog.migrate

seed: ## Seed the simulated 24-household neighborhood (use `make seed ARGS=--reset` to recreate)
	cd $(BACKEND) && $(DEV_ENV) uv run python -m watchdog.seed $(ARGS)

run: ## Start the backend locally on http://localhost:8000
	cd $(BACKEND) && $(DEV_ENV) uv run uvicorn watchdog.asgi:app --host 0.0.0.0 --port 8000

simulate: ## Play a scenario against the local backend, e.g. `make simulate SCENARIO=C`
	cd $(BACKEND) && $(DEV_ENV) uv run python -m watchdog.simulation $(if $(SCENARIO),--scenario $(SCENARIO),--list)

backend-lint: ## Lint and format-check the backend
	cd $(BACKEND) && uv run ruff check . && uv run ruff format --check .

backend-unit: ## Backend unit tests that need no database
	cd $(BACKEND) && uv run pytest -m "not db"

backend-test: docker-up ## All backend tests (unit, integration, security, scenarios A to J)
	cd $(BACKEND) && REQUIRE_DATABASE=1 uv run pytest

backend-test-docker: ## All backend tests inside containers (no local Python needed)
	docker compose --profile test run --rm --build test

live-backend: ## Backend for the apps' live contract tests (run in its own terminal)
	cd $(BACKEND) && $(DEV_ENV) uv run uvicorn watchdog.asgi:app --host 127.0.0.1 --port 8000

live-ios: ## iOS networking code against the live local backend (needs `make live-backend` running and `make seed`)
	scripts/live_contract.sh ios

live-android: ## Android networking code against the live local backend (same prerequisites)
	scripts/live_contract.sh android

ios-core-test: ## iOS core logic tests run natively on the Mac (no simulator needed)
	cd $(IOS) && swift test

openapi: ## Regenerate shared/api-contracts/openapi.json from the backend
	cd $(BACKEND) && uv run python -m watchdog.export_openapi

openapi-check: ## Fail if the committed API contract is out of date
	cd $(BACKEND) && uv run python -m watchdog.export_openapi --check

android-build: ## Build the Android debug APK
	cd $(ANDROID) && ./gradlew --no-daemon assembleDebug

android-test: ## Android unit tests and lint
	cd $(ANDROID) && ./gradlew --no-daemon testDebugUnitTest lintDebug

android-release: ## Build the Android release APK and bundle (signed only if signing variables are set)
	cd $(ANDROID) && ./gradlew --no-daemon assembleRelease bundleRelease

ios-generate: ## Regenerate the Xcode project from project.yml (needs xcodegen)
	cd $(IOS) && xcodegen generate

ios-build: ## Build the iOS app for the simulator (no signing needed)
	cd $(IOS) && xcodebuild -project NeighborhoodWatchdog.xcodeproj -scheme NeighborhoodWatchdog \
		-destination 'generic/platform=iOS Simulator' -configuration Debug -derivedDataPath build/DerivedData \
		CODE_SIGNING_ALLOWED=NO build

ios-test: ## iOS unit tests on a simulator (override IOS_DESTINATION if needed)
	cd $(IOS) && xcodebuild -project NeighborhoodWatchdog.xcodeproj -scheme NeighborhoodWatchdog \
		-destination '$(IOS_DESTINATION)' -derivedDataPath build/DerivedData CODE_SIGNING_ALLOWED=NO test

test: backend-lint openapi-check backend-test android-test ios-test ## Everything

sbom: ## Write a CycloneDX software bill of materials for the backend to sbom-backend.json
	cd $(BACKEND) && uv export --frozen --no-dev --format requirements-txt -o /tmp/nw-requirements.txt >/dev/null \
		&& uvx --from cyclonedx-bom==5.1.1 cyclonedx-py requirements /tmp/nw-requirements.txt -o ../sbom-backend.json

clean: ## Remove build outputs
	rm -rf $(BACKEND)/.pytest_cache $(BACKEND)/.ruff_cache $(ANDROID)/app/build $(ANDROID)/build $(IOS)/build
