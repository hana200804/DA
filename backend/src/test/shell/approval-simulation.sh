#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
./gradlew --quiet compileTestJava
java -cp build/classes/java/main:build/classes/java/test vn.signa.simulation.ApprovalSimulation
