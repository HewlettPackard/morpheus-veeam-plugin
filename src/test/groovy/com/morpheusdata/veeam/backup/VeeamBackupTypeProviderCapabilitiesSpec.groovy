package com.morpheusdata.veeam.backup

import com.morpheusdata.veeam.backup.hyperv.VeeamHypervBackupTypeProvider
import com.morpheusdata.veeam.backup.scvmm.VeeamScvmmBackupTypeProvider
import com.morpheusdata.veeam.backup.vcd.VeeamVcdBackupTypeProvider
import com.morpheusdata.veeam.backup.vmware.VeeamVMwareBackupTypeProvider
import spock.lang.Specification
import spock.lang.Unroll

class VeeamBackupTypeProviderCapabilitiesSpec extends Specification {

	@Unroll
	void "#providerType.simpleName supports only existing workload restores"() {
		given:
		def provider = providerType.newInstance(null, null, null)

		expect:
		provider.restoreExistingEnabled
		!provider.restoreNewEnabled
		provider.restoreNewMode == 'DEFAULT'

		where:
		providerType << [
			VeeamVMwareBackupTypeProvider,
			VeeamHypervBackupTypeProvider,
			VeeamScvmmBackupTypeProvider,
			VeeamVcdBackupTypeProvider
		]
	}
}
