# Task List for Proof-of-Concept

## Open

- Build MP-SPDZ fake generator

- Implement sidecar with tuple upload

- Activate tuples in scheduler

- Startup behavior
  - Traverse etcd when starting up to avoid missed jobs when slave is started after master
  - If master: delete all preexisting etcd entries

- Ensure proper shutdown when deleted

- Refactorings
  - Better name for job to disambiguate with K8s job
  - Consistent (regarding level) and comprehensive logging
  - Improve error handling and logging

- Make actions idempotent?

- Convert to multi-module project (operator, sidecar)

- Transition to distributed etcd cluster

- Graceful shutdown
  - Make sure that all launched jobs are deleted when scheduler is deleted
  - Delete all metadata in etcd

- Fulfil OSS obligations

- Add tests

## Done

- Deploy single-node etcd on master and expose client port to the outside [DONE]
    - See https://bitnami.com/stack/etcd/helm w/o RBAC and exposed externally [DONE]
      ```shell
      helm repo add bitnami https://charts.bitnami.com/bitnami
      helm install test-etcd --set auth.rbac.enabled=false --set service.type=LoadBalancer bitnami/etcd
      ```

- Implement control loop to be run on master (<- defined in CRD) [DONE]
    - fetch tuple telemetry (discover Castor service dynamically, if not there skip loop cycle, fetch using castor client) [DONE]
    - compare with target state (define properties in scheduler CRD) [DONE]
    - write to etcd (create prefix with random UUID) [DONE]
    - terminate loop cycle [DONE]


- Implement etcd watching for actual job creation [DONE]
    - Put watch on common prefix (/klyshko/jobs) [DONE]
    - If child is created, create Job resource injecting job id [DONE]

- Update etcd with job state (JobStatus?) (DONE)
    - Implement Watcher on jobs with certain labels and update roster (see 2) [DONE]
    - Store status of job (created, running, finished, error) as child of job node [DONE]

- First multi-cluster test [DONE]

- Provide script to deploy and start in local apollo / starbuck setting [DONE]

- Change to apply parallelism to all tuple types + migrate ids from /jobs/<type>/id to /jobs/id and store type in
  value as record [DONE]

- Refactorings
    - Implement Closeable to clean up "active" objects (watcher, scheduled tasks, etc.) [DONE]
    - Keys as types (JobKey, PartyKey, etc.) [DONE]
