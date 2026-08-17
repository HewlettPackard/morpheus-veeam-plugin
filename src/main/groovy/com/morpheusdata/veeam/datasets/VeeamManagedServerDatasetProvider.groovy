package com.morpheusdata.veeam.datasets

import com.morpheusdata.veeam.utils.JsonUtils
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataAndFilter
import com.morpheusdata.core.data.DataFilter
import com.morpheusdata.core.data.DataOrFilter
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.data.DatasetInfo
import com.morpheusdata.core.data.DatasetQuery
import com.morpheusdata.core.providers.AbstractDatasetProvider
import com.morpheusdata.model.ReferenceData
import com.morpheusdata.model.ResourcePermission
import com.morpheusdata.veeam.backup.VeeamBackupProvider
import com.morpheusdata.veeam.utils.VeeamUtils
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

/**
 * Dataset provider that lists the Veeam managed servers available for a backup provider, ported from the
 * embedded {@code VeeamOptionSourceService#veeamManagedServer}.
 */
@Slf4j
class VeeamManagedServerDatasetProvider extends AbstractDatasetProvider<ReferenceData, String> {

    public static final providerName = 'Veeam Managed Server Dataset Provider'
    public static final providerNamespace = 'veeam'
    public static final providerKey = 'veeamManagedServer'
    public static final providerDescription = 'A set of managed servers from Veeam'

    VeeamManagedServerDatasetProvider(Plugin plugin, MorpheusContext morpheus) {
        this.plugin = plugin
        this.morpheusContext = morpheus
    }

    @Override
    DatasetInfo getInfo() {
        new DatasetInfo(
                name: providerName,
                namespace: providerNamespace,
                key: providerKey,
                description: providerDescription
        )
    }

    /**
     * the class type of the data for this provider
     * @return the class this provider operates on
     */
    @Override
    Class<ReferenceData> getItemType() {
        return ReferenceData.class
    }

    /**
     * list the managed server reference data records accessible to the current account
     * @param query the user and map of query params or options to apply to the list
     * @return a list of objects
     */
    @Override
    Observable<ReferenceData> list(DatasetQuery query) {
        log.debug("managed server list: ${query.parameters}")
        def account = query.user?.account
        if (!account) {
            return Observable.empty()
        }
        def cloud = resolveCloud(query, account)
        def managedServerType = resolveManagedServerType(cloud)
        def backupProvider = resolveVeeamBackupProvider(cloud, account)
        if (backupProvider) {
            def accessibleResourceIds = morpheus.services.resourcePermission.listAccessibleResources(account.id, ResourcePermission.ResourceType.ManagedServer, null, null)
            def dataQuery = new DataQuery().withFilters([
                    new DataFilter("account", backupProvider.account),
                    new DataFilter("category", "veeam.backup.managedServer.${backupProvider.id}"),
                    new DataFilter("typeValue", managedServerType),
                    new DataFilter("enabled", true)
            ])
            def dataOrFilter = new DataOrFilter(
                    new DataFilter("account.id", account.id),
                    new DataAndFilter(
                            new DataFilter("account.masterAccount", true),
                            new DataFilter("visibility", "public")
                    )
            )
            if (accessibleResourceIds) {
                dataOrFilter.withFilter(new DataFilter("id", "in", accessibleResourceIds))
            }
            dataQuery.withFilter(dataOrFilter)
            return Observable.fromIterable(morpheus.services.referenceData.list(dataQuery))
        }
        return Observable.empty()
    }

    /**
     * list the managed servers as name/value pairs, mirroring the embedded option source output.
     * @param query a DatasetQuery containing the user and map of query params or options to apply to the list
     * @return a list of maps that have name value pairs of the items
     */
    @Override
    Observable<Map> listOptions(DatasetQuery query) {
        log.debug("managed servers: ${query.parameters}")
        List servers = []
        def account = query.user?.account
        def cloud = account ? resolveCloud(query, account) : null
        def managedServerType = resolveManagedServerType(cloud)
        def repoBackupServerId = resolveRepoBackupServerId(query)
        def backupProvider = cloud ? resolveVeeamBackupProvider(cloud, account) : null
        if (backupProvider) {
            def existingManagedServers = list(query).toList().blockingGet()
            if (existingManagedServers.size() > 0) {
                existingManagedServers.each { managedServer ->
                    def managedServerConfig = managedServer.getConfigMap()
                    if (managedServerConfig?.managedServerType == managedServerType) {
                        def backupServerName = ""
                        def id = null
                        def backupServerId
                        if (managedServerConfig.managedServerId && managedServerConfig.backupServerId) {
                            id = managedServerConfig.managedServerId + ":" + managedServerConfig.backupServerId
                            backupServerName = " (Backup Server: " + managedServerConfig.backupServerName + ")"
                            backupServerId = managedServerConfig.backupServerId
                        } else {
                            def managedServerLink = JsonUtils.findLink(managedServerConfig, "ManagedServerReference")
                            def backupServerLink = JsonUtils.findLink(managedServerConfig, "BackupServer")
                            def managedServerId = VeeamUtils.extractVeeamUuid(managedServerLink.href)
                            backupServerId = VeeamUtils.extractVeeamUuid(backupServerLink.href)
                            backupServerName = " (Backup Server: " + (backupServerLink?.name ?: "N/A") + ")"
                            id = "${managedServerId}:${backupServerId}"
                        }
                        if (!backupServerId || !repoBackupServerId || (repoBackupServerId == backupServerId)) {
                            def value = managedServer.name + backupServerName
                            servers << [name: value, id: id, value: id]
                        }
                    }
                }
            } else {
                servers << [name: "No managed servers setup in Veeam", id: '']
            }
        } else {
            servers << [name: "No Veeam backup provider found.", id: '']
        }
        return Observable.fromIterable(servers)
    }

    /**
     * Resolve the cloud from either the container (workload) or zone id supplied in the query params. The backup
     * wizard always posts a bare {@code zoneId}, so the resolved cloud is the reliable source of context rather than
     * the domain-scoped form fields (which vary between the backup modal and the provisioning wizard).
     */
    private resolveCloud(DatasetQuery query, account) {
        def cloud
        Long containerId = query.get("containerId")?.toLong()
        Long cloudId = query.get("zoneId")?.toLong()
        if (containerId && !cloudId) {
            def workload = morpheus.services.workload.find(new DataQuery().withFilter("account", account).withFilter("containerId", containerId))
            cloud = workload?.server?.cloud
        }
        if (!cloud && cloudId) {
            cloud = morpheus.async.cloud.get(cloudId).blockingGet()
        }
        return cloud
    }

    /**
     * Resolve the veeam backup provider for the given cloud, mirroring the embedded {@code VeeamOptionSourceService}:
     * prefer the cloud's integrated backup provider when it is veeam-typed, otherwise fall back to an enabled
     * veeam provider owned by the account, then to a master/public veeam provider. The type filter keeps this
     * self-contained, so the plugin never claims another backup provider plugin's integration.
     */
    private resolveVeeamBackupProvider(cloud, account) {
        def backupProvider
        if (cloud?.backupProvider) {
            def integrated = morpheus.services.backupProvider.get(cloud.backupProvider.id)
            if (integrated?.type?.code == VeeamBackupProvider.PROVIDER_CODE) {
                backupProvider = integrated
            }
        }
        if (!backupProvider && account) {
            backupProvider = morpheus.services.backupProvider.find(new DataQuery().withFilters([
                    new DataFilter('enabled', true),
                    new DataFilter('type.code', VeeamBackupProvider.PROVIDER_CODE),
                    new DataFilter('account.id', account.id)
            ]))
        }
        if (!backupProvider) {
            backupProvider = morpheus.services.backupProvider.find(new DataQuery().withFilters([
                    new DataFilter('enabled', true),
                    new DataFilter('type.code', VeeamBackupProvider.PROVIDER_CODE),
                    new DataFilter('account.masterAccount', true),
                    new DataFilter('visibility', 'public')
            ]))
        }
        return backupProvider
    }

    /**
     * Derive the veeam managed server type from the cloud's type. The cloud is resolved from the bare {@code zoneId}
     * the wizard always supplies, so this avoids depending on domain-scoped form fields (e.g. {@code backup.backupType})
     * whose prefix differs between the backup modal and the provisioning wizard.
     */
    private String resolveManagedServerType(cloud) {
        switch (cloud?.cloudType?.code) {
            case 'vmware':
                return "VC"
            case 'hyperv':
                return "HvServer"
            case 'scvmm':
                return 'Scvmm'
            case 'vcd':
                return 'VcdSystem'
            default:
                return ""
        }
    }

    /**
     * Resolve the backup server id associated with the selected repository, used to filter managed servers.
     */
    private resolveRepoBackupServerId(DatasetQuery query) {
        def repoBackupServerId
        def repositoryId = query.get("repositoryId")
        if (repositoryId && repositoryId.toString().isLong()) {
            def repo = morpheus.services.backupRepository.get(repositoryId.toLong())
            def repoConfig = repo?.getConfigMap()
            def backupServerHref = JsonUtils.findLink(repoConfig, "BackupServerReference")?.href
            repoBackupServerId = VeeamUtils.extractVeeamUuid(backupServerHref)
        }
        return repoBackupServerId
    }

    @Override
    ReferenceData fetchItem(Object value) {
        return null
    }

    @Override
    ReferenceData item(String value) {
        return null
    }

    /**
     * gets the name for an item
     * @param item an item
     * @return the corresponding name for the name/value pair list
     */
    @Override
    String itemName(ReferenceData item) {
        return item.name
    }

    /**
     * gets the value for an item
     * @param item an item
     * @return the corresponding value for the name/value pair list
     */
    @Override
    String itemValue(ReferenceData item) {
        return item.id?.toString()
    }
}
