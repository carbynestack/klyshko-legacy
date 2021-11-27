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

echo -e "${GREEN}Cleaning up old artifacts${NC}"
for c in "${CLUSTERS[@]}"
do
   kubectl config use-context "kind-$c"
   echo -e "${GREEN}Deleting operator in $c${NC}"
   kubectl delete deployment klyshko-operator
   echo -e "${GREEN}Deleting jobs in $c${NC}"
   kubectl delete jobs --all
done

echo -e "${GREEN}Purging klyshko namespace in etcd${NC}"
./etcdctl --endpoints 172.18.1.129:2379 del "klyshko" --prefix

echo -e "${GREEN}Building code and image${NC}"
./mvnw package -Dquarkus.container-image.build=true

echo -e "${GREEN}Loading docker images into cluster registries${NC}"
for c in "${CLUSTERS[@]}"
do
   echo -e "${GREEN}Loading docker image into $c${NC}"
   kind load docker-image carbynestack/klyshko-operator:1.0.0-SNAPSHOT --name "$c"
done

for c in "${CLUSTERS[@]}"
do
  echo -e "${GREEN}Deploying resource definitions in $c${NC}"
  kubectl config use-context "kind-$c"
  kubectl apply -f target/kubernetes/schedulers.klyshko.carbynestack.io-v1.yml
  MASTER=$([ "$c" == "apollo" ] && echo "true" || echo "false")
  sed "s/ROLE/${MASTER}/" src/main/kubernetes/sample-scheduler.yaml.template > "target/kubernetes/$c-scheduler.yaml"
  kubectl apply -f src/main/kubernetes/cluster-rolebinding.yaml
  kubectl apply -f "target/kubernetes/$c-scheduler.yaml"
  kubectl apply -f target/kubernetes/kubernetes.yml
  sleep 10
done