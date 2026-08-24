# Morpheus Veeam Plugin

The Morpheus Veeam Plugin integrates [Morpheus](https://morpheusdata.com) with [Veeam Backup & Replication](https://www.veeam.com/products/veeam-backup-replication.html) through the Veeam Backup Enterprise Manager REST API. It discovers Veeam infrastructure and backup jobs, protects virtual workloads across supported cloud types, and provides backup and restore workflows from the Morpheus platform.

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Repository structure](#repository-structure)
- [Building the plugin](#building-the-plugin)
- [License](#license)
- [Installing](#installing)
- [Detailed Usage Steps](#detailed-usage-steps)
- [API Endpoints](#api-endpoints)

---

## Features

### Backup Integration

The plugin registers a Veeam backup provider that supports:

- Connectivity and credential validation against Veeam Backup Enterprise Manager
- Session-based authentication and logout
- Runtime negotiation of the highest supported Veeam REST API version
- Provider health monitoring during refresh
- Cleanup of synchronized Veeam reference data when the integration is removed

### Veeam Inventory Sync

The following Veeam resources are discovered and kept in sync:

- Backup servers registered with Enterprise Manager
- Managed vCenter, Hyper-V, SCVMM, and VMware Cloud Director servers
- Backup repositories
- Backup jobs represented as Morpheus backup jobs

Additions, updates, and removals in Veeam are reflected in Morpheus on the next integration refresh.

### Supported Workloads

The plugin provides these cloud-scoped backup types:

| Backup Type | Code | Cloud Scope | Restore Mode |
|-------------|------|-------------|--------------|
| Veeam VMware VM Backup | `veeamVMWareBackup` | `vmware` | Offline VM restore |
| Veeam Hyper-V VM Backup | `veeamHypervBackup` | `hyperv` | Offline VM restore |
| Veeam SCVMM VM Backup | `veeamScvmmBackup` | `scvmm` | Offline VM restore |
| Veeam VCD VM Backup | `veeamVcdBackup` | `vcd` | Offline VM restore |

Each backup type supports restoring an existing workload or restoring to a new virtual machine.

### Backup Job Management

Veeam backup jobs are managed through the Morpheus backup framework. Supported operations include:

- Clone an existing Veeam backup job
- Apply a Morpheus execution schedule when cloning a job
- Add a workload to a backup job
- Execute a backup job on demand
- Remove a workload from a job
- Delete a Morpheus-managed Veeam job

### Backup and Restore Operations

Supported workload operations include:

- Run VeeamZIP for a workload that does not yet have a successful backup
- Run quick backup after an initial successful backup
- Resolve workloads across account-accessible and shared Veeam managed servers
- Cancel an in-progress backup
- Poll Veeam tasks, backup sessions, and task sessions
- Track backup sizes and results in Morpheus
- Restore a backup over the existing workload
- Restore a deleted workload or restore to a new virtual machine
- Poll restore tasks and sessions until completion

### Option Sources and Datasets

The plugin registers option sources and Morpheus Plugin API 1.5 dataset providers in the `veeam` namespace:

| Dataset Key | Purpose |
|-------------|---------|
| `veeamBackupRepository` | Lists accessible repositories for the Veeam integration associated with the selected cloud |
| `veeamManagedServer` | Lists accessible managed servers filtered by cloud type and, when applicable, backup server |

The datasets support account-owned resources and accessible public resources from the master account.

---

## Requirements

| Requirement | Version or Details |
|-------------|--------------------|
| Morpheus | 9.1.0 or later |
| Morpheus Plugin API | 1.5.x |
| Java | 25 |
| Gradle | Use the included Gradle wrapper (`./gradlew`) |
| Veeam Backup & Replication | Backup Enterprise Manager REST API v1.3 or later |

Additional prerequisites:

- A Veeam Backup Enterprise Manager host reachable from the Morpheus appliance
- The Enterprise Manager REST API port, commonly `9398`, allowed through intervening firewalls
- A Veeam account with permission to view infrastructure and manage backup and restore operations
- Managed VMware, Hyper-V, SCVMM, or VMware Cloud Director workloads represented in both Morpheus and Veeam

---

## Repository structure

```text
src/main/groovy/com/morpheusdata/veeam/
├── VeeamPlugin.groovy                         - Plugin entry point and provider registration
├── VeeamOptionSourceProvider.groovy           - Dynamic option sources for Veeam selections
├── backup/                                    - Backup provider, job provider, and shared execution and restore logic
│   ├── vmware/                                - VMware backup, execution, and restore providers
│   ├── hyperv/                                - Hyper-V backup, execution, and restore providers
│   ├── scvmm/                                 - SCVMM backup, execution, and restore providers
│   └── vcd/                                   - VMware Cloud Director backup, execution, and restore providers
├── datasets/
│   ├── VeeamBackupRepositoryDatasetProvider.groovy - Backup repository dataset provider
│   └── VeeamManagedServerDatasetProvider.groovy    - Managed server dataset provider
├── services/ApiService.groovy                 - Veeam Enterprise Manager REST API client
├── sync/                                      - Backup server, managed server, repository, and job sync tasks
└── utils/                                     - JSON, schedule, and Veeam reference helpers
src/assets/                                    - Plugin icon assets
src/main/resources/i18n/                       - Localization bundles
build.gradle, gradle.properties                - Build configuration, versions, and plugin metadata
```

---

## Building the plugin

Run the following command to compile and package the plugin jar:

```bash
./gradlew clean shadowJar
```

The packaged `morpheus-veeam-plugin-<version>-all.jar` will be written to `build/libs/`.

To execute tests, use the following command:

```bash
./gradlew test
```

---

## License

This project is licensed under the Apache License 2.0.

See the [LICENSE](LICENSE) file for details.

---

## Installing

1. Build the plugin as described in [Building the plugin](#building-the-plugin), or download a released jar from the repository's [Releases](https://github.com/HewlettPackard/morpheus-veeam-plugin/releases) page.
2. In Morpheus, navigate to **Administration > Integrations > Plugins**.
3. Click **Add** and upload the `morpheus-veeam-plugin-<version>-all.jar`.
4. Wait for the plugin to load. **Veeam** will then be available as a backup integration.

---

## Detailed Usage Steps

### Adding a Veeam Backup Integration

1. In Morpheus, navigate to **Backups > Integrations**.
2. Click **Add Backup Integration** and select **Veeam**.
3. Enter the Veeam Backup Enterprise Manager **Host** and REST API **Port**.
4. Select a stored username/password credential or enter a Veeam **Username** and **Password**.
5. Save the integration. Morpheus validates the connection, negotiates the REST API version, and synchronizes backup servers, managed servers, repositories, and jobs.

### Configuring a Workload Backup

1. Open a supported VMware, Hyper-V, SCVMM, or VMware Cloud Director workload in Morpheus.
2. Add a backup and select the Veeam integration.
3. Select a **Repository**. The list is filtered to repositories accessible through the selected cloud's Veeam integration.
4. Select a **Managed Server**. The list is filtered by workload cloud type and compatible backup server.
5. Select or clone a Veeam backup job and configure its schedule when prompted.
6. Save the backup configuration.

### Running and Monitoring a Backup

Run the configured backup from the workload's **Backups** tab or execute its backup job. Morpheus runs VeeamZIP for the initial backup and quick backup for subsequent backups when the workload can be resolved in Veeam. Morpheus monitors the returned task and backup session until the operation succeeds, fails, or is canceled.

### Restoring a Workload

Select a successful backup result and choose **Restore**. Restore the protected workload in place or restore it to a new virtual machine. Morpheus resolves the Veeam restore point, follows the restore action link returned by the API, and monitors the restore task and session to completion.

---

## API Endpoints

The plugin communicates with the Veeam Backup Enterprise Manager REST API under the `/api` base path. Authentication uses HTTP Basic credentials to create a session; subsequent requests use the returned `X-RestSvcSessionId` token.

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/sessionMngr` | `POST` | Create an authenticated API session |
| `/api/logonSessions/{sessionId}` | `DELETE` | End an API session |
| `/api/` | `GET` | Discover supported REST API versions |
| `/api/backupServers` | `GET` | List backup servers |
| `/api/backupServers/{backupServerId}?action=quickbackup` | `POST` | Start a quick backup |
| `/api/backupServers/{backupServerId}?action=veeamzip` | `POST` | Start a VeeamZIP backup |
| `/api/managedServers` | `GET` | List managed virtualization servers |
| `/api/repositories` | `GET` | List backup repositories |
| `/api/jobs` | `GET` | List backup jobs |
| `/api/jobs/{jobId}` | `GET`, `PUT`, `DELETE` | Retrieve, update, or delete a backup job |
| `/api/jobs/{jobId}?action=clone` | `POST` | Clone a backup job |
| `/api/jobs/{jobId}?action=start` | `POST` | Start a backup job |
| `/api/jobs/{jobId}?action=stop` | `POST` | Stop a backup job |
| `/api/jobs/{jobId}/includes` | `GET`, `POST` | List workloads in a job or add a workload |
| `/api/jobs/{jobId}/includes/{objectId}` | `DELETE` | Remove a workload from a job |
| `/api/query` | `GET` | Query hierarchy roots, backup sessions, restore points, and other Veeam entities |
| `/api/lookup` | `GET` | Resolve a workload to its Veeam hierarchy object |
| `/api/backupSessions/{sessionId}` | `GET` | Retrieve backup session status |
| `/api/backupSessions/{sessionId}/taskSessions` | `GET` | Retrieve workload task sessions and backup statistics |
| `/api/restorePoints/{restorePointId}/vmRestorePoints` | `GET` | List VM restore points associated with a restore point |
| `/api/vmRestorePoints/{restorePointId}` | `GET` | Retrieve a VM restore point and its restore links |
| Restore action link returned by Veeam | `POST` | Start an existing-VM or new-VM restore |
| `/api/restoreSessions/{sessionId}` | `GET` | Retrieve restore status and the restored VM reference |
| `/api/tasks/{taskId}` | `GET` | Poll an asynchronous Veeam task |
