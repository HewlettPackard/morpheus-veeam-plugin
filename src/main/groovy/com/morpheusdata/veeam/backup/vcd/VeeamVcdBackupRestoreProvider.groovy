package com.morpheusdata.veeam.backup.vcd

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.Backup
import com.morpheusdata.model.BackupResult
import com.morpheusdata.model.Cloud
import com.morpheusdata.model.ComputeServer
import com.morpheusdata.veeam.backup.VeeamBackupRestoreProviderInterface
import com.morpheusdata.veeam.backup.VeeamBackupTypeProvider
import com.morpheusdata.veeam.services.ApiService
import com.morpheusdata.veeam.utils.VeeamUtils
import groovy.util.logging.Slf4j

@Slf4j
class VeeamVcdBackupRestoreProvider implements VeeamBackupRestoreProviderInterface {

	Plugin plugin
	MorpheusContext morpheus
	ApiService apiService
	VeeamBackupTypeProvider backupTypeProvider

	VeeamVcdBackupRestoreProvider(Plugin plugin, MorpheusContext morpheus, VeeamBackupTypeProvider backupTypeProvider, ApiService apiService) {
		this.plugin = plugin
		this.morpheus = morpheus
		this.apiService = apiService
		this.backupTypeProvider = backupTypeProvider
	}

	String getRestoreObjectRef(String objectRef) {
		return objectRef?.replace("vapp-", "")?.replace("vm-", "")
	}

	Map buildApiRestoreOpts(Map authConfig, BackupResult backupResult, Backup backup, ComputeServer server, Cloud cloud) {
		def restoreOpts = super.buildApiRestoreOpts(authConfig, backupResult, backup, server, cloud)
		restoreOpts.vdcId = cloud.getConfigProperty("vdcId")
		restoreOpts.vAppId = server.internalId?.toLowerCase()?.replace("vapp-", "")
		restoreOpts.vCenterVmId = server.uniqueId

		return restoreOpts
	}

	Map buildRestoreSpec(String restorePath, String hierarchyRoot, String backupType, Map opts) {
		Map rtn
		if(backupType != "veeamzip") {
			def restorePointUid = VeeamUtils.extractVeeamUuid(restorePath)
			def hierarchyRootUid = VeeamUtils.extractVeeamUuid(opts.hierarchyRoot)
			// request bodies remain PascalCase, only the response representation changed casing in Veeam 13
			rtn = [
				vCloudVmRestoreSpec: [
					PowerOnAfterRestore: true,
					HierarchyRootUid: hierarchyRootUid,
					vAppRef: "urn:${backupTypeProvider.cloudType}:Vapp:${hierarchyRootUid}.urn:vcloud:vapp:${opts.vAppId}".toString(),
					VmRestoreParameters: [
						VmRestorePointUid: "urn:veeam:VmRestorePoint:${restorePointUid}".toString()
					]
				]
			]
		} else {
			rtn = VeeamBackupRestoreProviderInterface.super.buildRestoreSpec(restorePath, hierarchyRoot, backupType, opts)
		}

		return rtn
	}
}