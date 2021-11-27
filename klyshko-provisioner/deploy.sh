#!/usr/bin/env bash
#
# Copyright (c) 2021 - for information on the respective copyright owner
# see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
#
# SPDX-License-Identifier: Apache-2.0
#
GREEN='\033[0;32m'
NC='\033[0m' # No Color

declare -a CLUSTERS=("starbuck" "apollo")

echo -e "${GREEN}Building code and image${NC}"
./mvnw package -Dquarkus.container-image.build=true

echo -e "${GREEN}Loading docker images into cluster registries${NC}"
for c in "${CLUSTERS[@]}"
do
   echo -e "${GREEN}Loading docker image into $c${NC}"
   kind load docker-image carbynestack/klyshko-provisioner:1.0.0-SNAPSHOT --name "$c"
done

for c in "${CLUSTERS[@]}"
do
  echo -e "${GREEN}Deploying resource definitions in $c${NC}"
  kubectl config use-context "kind-$c"
  kubectl apply -f src/main/kubernetes/cluster-rolebinding.yaml
done