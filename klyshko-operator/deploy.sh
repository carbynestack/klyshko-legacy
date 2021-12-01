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
  MAC_KEY_SHARE_P=$([ "$c" == "apollo" ] && echo "-88222337191559387830816715872691188861" | base64 || echo "1113507028231509545156335486838233835" | base64)
  MAC_KEY_SHARE_2=$([ "$c" == "apollo" ] && echo "f0cf6099e629fd0bda2de3f9515ab72b" | base64 || echo "c347ce3d9e165e4e85221f9da7591d98" | base64)
  sed -e "s/MAC_KEY_SHARE_P/${MAC_KEY_SHARE_P}/" -e "s/MAC_KEY_SHARE_2/${MAC_KEY_SHARE_2}/" src/main/kubernetes/engine-params-secret.yaml.template > "target/kubernetes/$c-engine-params-secret.yaml"
  EXTRA_MAC_KEY_SHARE_P=$([ "$c" == "starbuck" ] && echo "-88222337191559387830816715872691188861" || echo "1113507028231509545156335486838233835")
  EXTRA_MAC_KEY_SHARE_2=$([ "$c" == "starbuck" ] && echo "f0cf6099e629fd0bda2de3f9515ab72b" || echo "c347ce3d9e165e4e85221f9da7591d98")
  sed -e "s/MAC_KEY_SHARE_P/${EXTRA_MAC_KEY_SHARE_P}/" -e "s/MAC_KEY_SHARE_2/${EXTRA_MAC_KEY_SHARE_2}/" src/main/kubernetes/engine-params-extra.yaml.template > "target/kubernetes/$c-engine-params-extra.yaml"
  MASTER=$([ "$c" == "apollo" ] && echo "true" || echo "false")
  sed -e "s/ROLE/${MASTER}/" src/main/kubernetes/sample-scheduler.yaml.template > "target/kubernetes/$c-scheduler.yaml"
  kubectl apply -f src/main/kubernetes/cluster-rolebinding.yaml
  kubectl apply -f target/kubernetes/$c-engine-params-secret.yaml
  kubectl apply -f target/kubernetes/$c-engine-params-extra.yaml
  kubectl apply -f src/main/kubernetes/engine-params.yaml
  kubectl apply -f target/kubernetes/kubernetes.yml
  kubectl apply -f "target/kubernetes/$c-scheduler.yaml"
  sleep 10
done