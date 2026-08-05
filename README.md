# Morpheus Veeam Plugin

This plugin provides backup integration between [Veeam Backup & Replication](https://www.veeam.com/products/veeam-backup-replication.html) and [Morpheus](https://morpheusdata.com). It enables backup server and repository discovery, backup job sync, VM backup protection across VMware, Hyper-V, SCVMM, and VMware Cloud Director workloads, and restore workflows from within the Morpheus platform.

## Requirements

| Component | Minimum Version |
|-----------|----------------|
| Morpheus | 9.1.0 |
| Veeam Backup & Replication | Enterprise Manager REST API v1.3 or later |

The Veeam Backup Enterprise Manager REST API must be reachable from the Morpheus appliance.

## Installation

1. Download the latest `.jar` from the [Releases](https://github.com/HewlettPackard/morpheus-veeam-plugin/releases) page, or [build it yourself](#building).
2. In Morpheus, navigate to **Administration → Integrations → Plugins**.
3. Click **Browse** and upload the `.jar` file.
4. The **Veeam** backup integration will appear after the plugin loads.

## Configuration

When adding a Veeam backup integration in Morpheus (**Backups → Integrations → Add Backup Integration**), provide the following:

| Field | Description |
|-------|-------------|
| **Host** | Veeam Backup Enterprise Manager API URL, e.g. `https://veeam.example.com` |
| **Port** | Enterprise Manager REST API port, typically `9398` |
| **Credentials** | Username and password used to authenticate against the Enterprise Manager API |

Credentials can also be stored as a Morpheus [Credential](https://docs.morpheusdata.com/en/latest/administration/credentials/credentials.html) of type `username-password` and selected at integration setup time.

When configuring an individual backup, the following options are required:

| Field | Description |
|-------|-------------|
| **Repository** | Veeam backup repository used to store the backup |
| **Managed Server** | Veeam managed server that hosts the workload being backed up |

## Features

### Backup Integration
The plugin registers a Veeam `BackupProvider` that connects Morpheus backup workflows to Veeam Backup & Replication. Supported integration behavior includes:

- Validate connectivity and credentials against the Enterprise Manager API
- Negotiate the highest supported Veeam REST API version at runtime
- Track provider health during refresh
- Clean up managed servers and backup servers when the integration is removed

### Veeam Sync
The following Veeam resources are discovered and kept in sync:

- **Backup Servers** — Veeam backup servers registered with Enterprise Manager
- **Managed Servers** — vCenter, Hyper-V, SCVMM, and VMware Cloud Director servers managed by Veeam
- **Backup Repositories** — repositories available as backup storage targets
- **Backup Jobs** — Veeam backup jobs represented as Morpheus backup jobs

Additions, updates, and removals in Veeam are automatically reflected in Morpheus on the next sync cycle.

### Workload Backup Types
Backups are scoped to the cloud hosting the workload. The plugin ships four backup types:

| Backup Type | Code | Cloud Scope |
|-------------|------|-------------|
| Veeam VMware VM Backup | `veeamVMWareBackup` | `vmware` |
| Veeam Hyper-V VM Backup | `veeamHypervBackup` | `hyperv` |
| Veeam SCVMM VM Backup | `veeamScvmmBackup` | `scvmm` |
| Veeam VCD VM Backup | `veeamVcdBackup` | `vcd` |

### Backup Job Management
Veeam backup jobs are managed through the Morpheus backup framework. Supported operations include:

- Clone an existing Veeam backup job and apply a Morpheus execute schedule
- Add workloads to an existing Veeam backup job
- Execute backup jobs on demand
- Delete backup jobs and remove workload includes when backups are removed

### Backup and Restore Operations
VM protection and restore workflows are available directly from Morpheus. Supported operations include:

- Run VeeamZIP backups for workloads that are not yet a member of a backup job
- Run quick backups for workloads already protected by a backup job
- Cancel in-flight backup sessions
- Poll backup sessions and task sessions to update Morpheus backup results
- Restore a backup to the original VM location
- Restore a deleted workload back into the environment when the original VM no longer exists
- Poll Veeam restore sessions and update Morpheus restore status

### Option Sources and Datasets
The plugin registers option sources and dataset providers so that Veeam-specific selections are populated from live inventory:

- **Backup Repository** — repositories available on the selected integration
- **Managed Server** — managed servers filtered by the workload's cloud type (`VC`, `HvServer`, `Scvmm`, `VcdSystem`)

## Repository structure

- `src/main/groovy/com/morpheusdata/veeam`
  - `VeeamPlugin.groovy` — plugin entry point that registers all providers
  - `VeeamOptionSourceProvider.groovy` — option sources for repository and managed server selection
  - `backup/` — backup provider, job provider, and shared execution/restore interfaces
    - `vmware/`, `hyperv/`, `scvmm/`, `vcd/` — cloud-specific backup type, execution, and restore providers
  - `datasets/` — dataset providers for backup repositories and managed servers
  - `services/ApiService.groovy` — Veeam Enterprise Manager REST API client
  - `sync/` — sync tasks for backup servers, managed servers, repositories, and jobs
  - `utils/` — schedule, XML, and Veeam helper utilities
- `src/assets` — plugin icon assets
- `src/main/resources/i18n` — localization message bundles
- `build.gradle` and `gradle.properties` — build configuration and dependency versions

## Building

```bash
./gradlew shadowJar
```

The plugin JAR will be written to `build/libs/`.

To run the tests:

```bash
./gradlew test
```

## API Endpoints

The plugin communicates with the Veeam Backup Enterprise Manager REST API. Key endpoints used include:

| Endpoint | Methods | Purpose |
|----------|---------|---------|
| `/api/sessionMngr` | `POST` | Create a logon session and retrieve an API token |
| `/api/logonSessions/{sessionId}` | `DELETE` | Terminate a logon session |
| `/api/` | `GET` | List supported API versions |
| `/api/backupServers` | `GET` | List Veeam backup servers |
| `/api/managedServers` | `GET` | List managed servers |
| `/api/repositories` | `GET` | List backup repositories |
| `/api/jobs`, `/api/jobs/{jobId}` | `GET`, `POST`, `PUT` | List, clone, update, and execute backup jobs |
| `/api/jobs/{jobId}/includes` | `GET`, `POST`, `DELETE` | Manage workload membership in a backup job |
| `/api/query` | `GET` | Query Veeam objects such as VMs and restore points |
| `/api/lookup` | `GET` | Look up hierarchy references for managed objects |
| `/api/backupSessions/{sessionId}` | `GET` | Retrieve backup session status |
| `/api/backupSessions/{sessionId}/taskSessions` | `GET` | Retrieve per-task backup session details |
| `/api/restorePoints/{restorePointId}/vmRestorePoints` | `GET` | List VM restore points for a restore point |
| `/api/vmRestorePoints/{restorePointId}` | `GET`, `POST` | Retrieve a VM restore point and start a restore |
| `/api/restoreSessions/{restoreSessionId}` | `GET` | Retrieve restore session status |
| `/api/tasks/{taskId}` | `GET` | Poll asynchronous Veeam task status |

## License

Copyright 2022 Morpheus Data, LLC. Licensed under the [Apache License, Version 2.0](LICENSE).
