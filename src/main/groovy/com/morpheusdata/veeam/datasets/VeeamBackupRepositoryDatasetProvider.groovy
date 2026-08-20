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
import com.morpheusdata.model.BackupProvider
import com.morpheusdata.model.BackupRepository
import com.morpheusdata.model.ResourcePermission
import com.morpheusdata.veeam.backup.VeeamBackupProvider
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

/**
 * Dataset provider that lists the Veeam backup repositories available for a backup provider, ported from the
 * embedded {@code VeeamOptionSourceService#veeamBackupRepository}.
 */
@Slf4j
class VeeamBackupRepositoryDatasetProvider extends AbstractDatasetProvider<BackupRepository, Long> {

    public static final providerName = 'Veeam Backup Repository Dataset Provider'
    public static final providerNamespace = 'veeam'
    public static final providerKey = 'veeamBackupRepository'
    public static final providerDescription = 'A set of backup repositories from Veeam'

    VeeamBackupRepositoryDatasetProvider(Plugin plugin, MorpheusContext morpheus) {
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
    Class<BackupRepository> getItemType() {
        return BackupRepository.class
    }

    /**
     * list the backup repository records accessible to the current account
     * @param query the user and map of query params or options to apply to the list
     * @return a list of objects
     */
    @Override
    Observable<BackupRepository> list(DatasetQuery query) {
        log.debug("backup repository list: ${query.parameters}")
        def account = query.user?.account
        Long cloudId = query.get("zoneId")?.toLong()
        if (!account || !cloudId) {
            return Observable.empty()
        }
        def backupProvider = resolveBackupProvider(cloudId, account)
        println "\u001B[33mAC Log - VeeamBackupRepositoryDatasetProvider:list- ${backupProvider.dump()}\u001B[0m"
        if (backupProvider) {
            def accessibleResourceIds = morpheus.services.resourcePermission.listAccessibleResources(account.id, ResourcePermission.ResourceType.BackupRepository, null, null)
            def dataQuery = new DataQuery().withFilters([
                    new DataFilter("backupProvider.id", backupProvider.id),
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
            return Observable.fromIterable(morpheus.services.backupRepository.list(dataQuery))
        }
        return Observable.empty()
    }

    /**
     * list the backup repositories as name/value pairs, mirroring the embedded option source output.
     * @param query a DatasetQuery containing the user and map of query params or options to apply to the list
     * @return a list of maps that have name value pairs of the items
     */
    @Override
    Observable<Map> listOptions(DatasetQuery query) {
        log.debug("backup repositories: ${query.parameters}")
        List repos = []
        def existingRepositories = list(query).toList().blockingGet()
        if (existingRepositories?.size() > 0) {
            existingRepositories.each { repo ->
                def repoConfig = repo.getConfigMap()
                def backupServer = JsonUtils.findLink(repoConfig, "BackupServerReference")?.name
                def repoName = backupServer ? "${repo.name} (Backup Server: ${backupServer})" : repo.name
                repos << [id: repo.id, name: repoName, code: repo.code, internalId: repo.internalId, value: repo.id]
            }
        } else {
            repos << [name: "No repositories setup in Veeam", id: '']
        }
        return Observable.fromIterable(repos)
    }

    /**
     * Resolve the veeam backup provider associated with the given cloud.
     */
    private resolveBackupProvider(Long cloudId, account) {
        def cloud = morpheus.async.cloud.get(cloudId).blockingGet()
        return resolveVeeamBackupProvider(cloud, account)
    }

    /**
     * Resolve the veeam backup provider for the given cloud: prefer the veeam-typed provider explicitly bound to
     * the cloud's backupProviders list (as core's BackupProviderService#getBackupProvider does; the legacy singular
     * cloud.backupProvider field is deprecated and intentionally not consulted here), otherwise fall back to an
     * enabled veeam provider owned by the account, then to a master/public veeam provider. The type filter keeps
     * this self-contained, so the plugin never claims another backup provider plugin's integration.
     *
     * Note: cloud.backupProviders entries are only partially marshalled (e.g. type is null), so candidate ids are
     * collected and re-fetched via listById to get a fully populated BackupProvider with its type.
     */
    private resolveVeeamBackupProvider(cloud, account) {
        BackupProvider backupProvider = null
        if (cloud?.backupProviders) {
            def cloudProviderIds = cloud.backupProviders.collect { it.id }
            if (cloudProviderIds) {
                backupProvider = morpheus.services.backupProvider.listById(cloudProviderIds)
                        .find { it.type?.code == VeeamBackupProvider.PROVIDER_CODE }
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

    @Override
    BackupRepository fetchItem(Object value) {
        def rtn = null
        if (value instanceof Long) {
            rtn = item((Long) value)
        } else if (value instanceof CharSequence) {
            def longValue = value.isNumber() ? value.toLong() : null
            if (longValue) {
                rtn = item(longValue)
            }
        }
        return rtn
    }

    @Override
    BackupRepository item(Long value) {
        return morpheus.services.backupRepository.get(value)
    }

    /**
     * gets the name for an item
     * @param item an item
     * @return the corresponding name for the name/value pair list
     */
    @Override
    String itemName(BackupRepository item) {
        return item.name
    }

    /**
     * gets the value for an item
     * @param item an item
     * @return the corresponding value for the name/value pair list
     */
    @Override
    Long itemValue(BackupRepository item) {
        return item.id
    }
}
