# Task List for Proof-of-Concept

## Open

- Provide integration code for MP-SPDZ fake offline generator

- Implement sidecar with tuple upload

- Activate tuples in scheduler

- Fulfill OSS obligations

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

- Add tests

## Done

- Deploy single-node etcd on master and expose client port to the outside
    - See https://bitnami.com/stack/etcd/helm w/o RBAC and exposed externally
      ```shell
      helm repo add bitnami https://charts.bitnami.com/bitnami
      helm install test-etcd --set auth.rbac.enabled=false --set service.type=LoadBalancer bitnami/etcd
      ```

- Implement control loop to be run on master (<- defined in CRD)
    - fetch tuple telemetry (discover Castor service dynamically, if not there skip loop cycle, fetch using castor client)
    - compare with target state (define properties in scheduler CRD)
    - write to etcd (create prefix with random UUID)
    - terminate loop cycle


- Implement etcd watching for actual job creation
    - Put watch on common prefix (/klyshko/jobs)
    - If child is created, create Job resource injecting job id

- Update etcd with job state (JobStatus?)
    - Implement Watcher on jobs with certain labels and update roster 
    - Store status of job (created, running, finished, error) as child of job node

- First multi-cluster test

- Provide script to deploy and start in local apollo / starbuck setting

- Change to apply parallelism to all tuple types + migrate ids from /jobs/<type>/id to /jobs/id and store type in
  value as record

- Refactorings
    - Implement Closeable to clean up "active" objects (watcher, scheduled tasks, etc.)
    - Keys as types (JobKey, PartyKey, etc.)

- Build MP-SPDZ fake generator