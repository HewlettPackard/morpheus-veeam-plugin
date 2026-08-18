package com.morpheusdata.veeam.services

import com.morpheusdata.core.Plugin
import com.morpheusdata.core.util.DateUtility
import com.morpheusdata.core.util.HttpApiClient
import com.morpheusdata.model.BackupProvider
import com.morpheusdata.veeam.utils.JsonUtils
import com.morpheusdata.veeam.utils.VeeamUtils
import com.morpheusdata.veeam.utils.VeeamScheduleUtils
import groovy.util.logging.Slf4j

@Slf4j
class ApiService {

	Plugin plugin

	static taskSleepInterval = 5l * 1000l //5 seconds
	static maxTaskAttempts = 36

	ApiService(Plugin plugin) {
		this.plugin = plugin
	}

	def getApiUrl(BackupProvider backupProvider) {
		backupProvider.serviceUrl
		def scheme = backupProvider.host.contains('http') ? '' : 'http://'
		def apiUrl = "${scheme}${backupProvider.host}:${backupProvider.port}"
		return apiUrl.toString()
	}

	Map getAuthConfig(BackupProvider backupProviderModel) {
		if(!backupProviderModel.account) {
			backupProviderModel = this.plugin.morpheusContext.services.backupProvider.get(backupProviderModel.id)
		}
		def newBackupProvider = this.plugin.loadCredentials(backupProviderModel)
		log.debug("newBackupProvider, credsLoaded: ${newBackupProvider.credentialLoaded}, credentialData: ${newBackupProvider.credentialData}")

		def rtn = [
			apiUrl: getApiUrl(backupProviderModel),
			basePath: '/api',
			username: backupProviderModel.credentialData?.username ?: backupProviderModel.username,
			password: backupProviderModel.credentialData?.password ?: backupProviderModel.password
		]
		log.debug("getAuthConfig: ${rtn}")
		return rtn
	}


	def loginSession(BackupProvider backupProviderModel) {
		def authConfig = getAuthConfig(backupProviderModel)
		return loginSession(authConfig)
	}

	def loginSession(Map authConfig) {
		log.debug("loginSession: {}, {}", authConfig.apiUrl, authConfig.username)
		def rtn = [success:false]
		def response = getToken(authConfig)
		if(response.success) {
			rtn.success = true
			rtn.token = response.token
			rtn.sessionId = response.sessionId
		} else {
			rtn.errorCode = response.errorCode
			rtn.msg = response.msg
			rtn.content = response.content
			rtn.error = true
		}
		return rtn
	}

	def logoutSession(apiUrl, token, sessionId) {
		if(token && sessionId) {
			logout(apiUrl, token, sessionId)
		}
	}

	static getToken(Map authConfig) {
		log.debug("authConfig: ${authConfig}")
		def rtn = [success:false]
		def requestToken = true
		if(authConfig.token) {
			rtn.success = true
			rtn.token = authConfig.token
			rtn.sessionId = authConfig.sessionId
			requestToken = false
		}
		//if need a new one
		if(requestToken == true) {
			def apiPath = authConfig.basePath + '/sessionMngr'
			def headers = buildHeaders([:], null)
			log.debug("tokenHeaders: ${headers}, authConfig: ${authConfig}")
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers)
			HttpApiClient httpApiClient = new HttpApiClient()
			def results = httpApiClient.callJsonApi(authConfig.apiUrl, apiPath, authConfig.username, authConfig.password, requestOpts, 'POST')
			if(results?.success && results?.error != true) {
				def token = findSessionToken(results.headers)
				if(token) {
					rtn.success = true
					rtn.token = token
					rtn.sessionId = JsonUtils.normalizeMap(results.data).sessionId?.toString()
					authConfig.token = rtn.token
					authConfig.sessionId = rtn.sessionId
				} else {
					// the request was answered by something other than the Enterprise Manager REST API, which
					// happens when the integration points at the Veeam web console port instead of the API port
					rtn.msg = "Veeam did not return a session token from ${authConfig.apiUrl}${apiPath}, verify the Enterprise Manager REST API url and port".toString()
					rtn.content = results.content ?: results.data?.toString()
					rtn.data = results.data
					rtn.headers = results.headers
				}
			} else {
				rtn.msg = "Veeam logon to ${authConfig.apiUrl}${apiPath} failed${results?.errorCode ? ' with HTTP ' + results.errorCode : ''}".toString()
				rtn.content = results.content ?: results.data?.toString()
				rtn.data = results.data
				rtn.errorCode = results.errorCode
				rtn.headers = results.headers
			}
		}
		return rtn
	}

	/**
	 * Locate the Veeam session token in the logon response headers. The header name is matched without regard to
	 * case because proxies and HTTP/2 may normalize header names.
	 *
	 * @param headers the response headers
	 * @return the session token or null
	 */
	static String findSessionToken(Map headers) {
		def entry = headers?.find { it.key?.toString()?.equalsIgnoreCase('X-RestSvcSessionId') }
		return entry?.value?.toString()
	}

	static logout(url, token, sessionId){
		def rtn = [success:false]
		def headers = buildHeaders([:], token)
		HttpApiClient httpApiClient = new HttpApiClient()
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers)
		def results = httpApiClient.callJsonApi(url.toString(), "/api/logonSessions/${sessionId}".toString(), null, null, requestOpts, 'DELETE')
		log.debug("got: ${results}")
		rtn.success = results?.success
		return rtn
	}

	static listSupportedApiVersions(Map authConfig, Map opts) {
		def rtn = [success:false, data:[]]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def apiPath = authConfig.basePath + '/'
			def headers = buildHeaders([:], tokenResults.token)
			HttpApiClient httpApiClient = new HttpApiClient()
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers)
			def results = httpApiClient.callJsonApi(authConfig.apiUrl, apiPath, requestOpts, 'GET')
			log.debug("Supported API Versions results: ${results}")
			if(results.success == true) {
				def response = JsonUtils.normalizeMap(results.data)
				JsonUtils.getList(response, 'supportedVersions', 'supportedVersion').each { supportedVersion ->
					log.debug("Veeam support API versions: ${supportedVersion}")
					def row = (supportedVersion instanceof Map) ? new LinkedHashMap(supportedVersion) : [:]
					row.version = row.name?.replace('v', '')?.replace('_', '.')?.toFloat()
					rtn.data << row
				}
				rtn.success = true
			}
		} else {
			//return token errors?
		}
		return rtn
	}

	/**
	 * Determine the next page to request from the paging info of a normalized list response.
	 *
	 * @param response a normalized list response that may contain a {@code pagingInfo} entry
	 * @return the next page number, or null when the last page has been reached
	 */
	static Integer getNextPage(Object response) {
		def pagingInfo = JsonUtils.get(response, 'pagingInfo')
		if(!(pagingInfo instanceof Map)) {
			return null
		}
		def pageNum = pagingInfo.pageNum?.toString()
		def pagesCount = pagingInfo.pagesCount?.toString()
		if(pageNum?.isInteger() && pagesCount?.isInteger() && pageNum.toInteger() < pagesCount.toInteger()) {
			return pageNum.toInteger() + 1
		}
		return null
	}

	static getLatestApiVersion(Map authConfig, Map opts=[:]) {		def rtn = [success:false, apiVersion: null]
		try {
			def supportedVersions = listSupportedApiVersions(authConfig, opts)
			if(supportedVersions.success) {
				rtn.apiVersion = supportedVersions.data.collect{ it.version }.sort { a, b -> b<=>a }.getAt(0)
				rtn.success = true
			} else {
				rtn.msg = supportedVersions.msg
			}
		} catch (e) {
			log.error("Error getting latest API version: ${e}", e)
		}

		return rtn
	}

	static listBackupJobs(Map authConfig, Map opts=[:]) {
		log.debug "listBackupJobs: ${authConfig}"
		def rtn = [success:false, jobs:[]]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def apiPath = authConfig.basePath + '/jobs'
			def headers = buildHeaders([:], tokenResults.token)
			def page = '1'
			def perPage = '50'
			def query = [format:'Entity', pageSize:perPage, page:page]
			if(opts.backupType)
				query.filter = 'Platform==' + opts.backupType
			def keepGoing = true
			while(keepGoing) {
				HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams:query)
				HttpApiClient httpApiClient = new HttpApiClient()
				def results = httpApiClient.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'GET')
				// log.debug("List Backup Jobs result: ${results}")
				if(results.success == true) {
					//iterate results
					def response = JsonUtils.normalize(results.data)
					JsonUtils.getEntityList(response, 'jobs', 'job').each { job ->
						def row = new LinkedHashMap((Map) job)
						row.externalId = row.uid
						row.scheduleCron = VeeamScheduleUtils.decodeScheduling(job)

						rtn.jobs << row
					}
					//paging
					def nextPage = getNextPage(response)
					if(nextPage) {
						query.page = nextPage.toString()
						keepGoing = true
					} else {
						keepGoing = false
						// we've iterated all pages successfully
						rtn.success = true
					}
				} else {
					keepGoing = false
				}
			}
		} else {
			//return token errors?

		}
		return rtn
	}

	static listManagedServers(Map authConfig, Map opts=[:]) {
		def rtn = [success:false, managedServers:[]]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def apiPath = authConfig.basePath + '/managedServers'
			def headers = buildHeaders([:], tokenResults.token)
			def page = '1'
			def perPage = '50'
			def query = [format:'Entity', pageSize:perPage, page:page]
			if(opts.managedServerType)
				query.filter = 'managedServerType==' + opts.managedServerType
			def keepGoing = true
			while(keepGoing) {
				HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
				HttpApiClient httpApiClient = new HttpApiClient()
				def results = httpApiClient.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'GET')
				if(results.success == true) {
					def response = JsonUtils.normalize(results.data)
					JsonUtils.getEntityList(response, 'managedServers', 'managedServer').each { managedServer ->
						def row = new LinkedHashMap((Map) managedServer)
						row.externalId = row.uid
						rtn.managedServers << row
					}
					//paging
					def nextPage = getNextPage(response)
					if(nextPage) {
						query.page = nextPage.toString()
						keepGoing = true
					} else {
						keepGoing = false
						// we've iterated all pages successfully
						rtn.success = true
					}
				} else {
					keepGoing = false
				}
			}
		} else {
			//return token errors?
		}
		return rtn
	}
//
	static getManagedServerRoot(Map authConfig, String name) {
		def rtn = [success:false]
		try {
			def tokenResults = getToken(authConfig)
			def headers = buildHeaders([:], tokenResults.token)
			def query = [type: 'HierarchyRoot', filter: "Name==\"${name}\"", format:"Entities", pageSize:'1']
			HttpApiClient httpApiClient = new HttpApiClient()
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
			def results = httpApiClient.callJsonApi(authConfig.apiUrl, "/api/query", null, null, requestOpts, 'GET')
			rtn.success = results.success
			if(rtn.success) {
				def response = JsonUtils.normalizeMap(results.data)
				def hRoot = JsonUtils.getList(JsonUtils.get(response, 'entities'), 'hierarchyRoots', 'hierarchyRoot').getAt(0)
				if(hRoot) {
					rtn.data = [
							id: hRoot.hierarchyRootId?.toString(),
							uid: hRoot.uid?.toString(),
							uniqueId: hRoot.uniqueId?.toString(),
							name: hRoot.name?.toString(),
							hostType: hRoot.hostType?.toString(),
							links: []
					]
					JsonUtils.getLinks(hRoot).each {
						rtn.data.links << [
								name:it.name?.toString(),
								type:it.type?.toString(),
								rel:it.rel?.toString(),
								href: it.href?.toString()
						]
					}
				}
			}
			log.debug("managed server root query results: ${rtn}")
		} catch(Exception e) {
			log.error("getManagedServerRoot error: ${e}", e)
		}

		return rtn
	}

	static listBackupServers(Map authConfig, Map opts=[:] ) {
		def rtn = [success:false, backupServers:[]]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def apiPath = authConfig.basePath + '/backupServers'
			def headers = buildHeaders([:], tokenResults.token)
			def page = '1'
			def perPage = '50'
			def query = [format:'Entity', pageSize:perPage, page:page]
			if(opts.managedServerType)
				query.filter = 'managedServerType==' + opts.managedServerType
			def keepGoing = true
			while(keepGoing) {
				HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
				HttpApiClient httpApiClient = new HttpApiClient()
				def results = httpApiClient.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'GET')
				if(results.success == true) {
					//iterate results
					def response = JsonUtils.normalize(results.data)
					JsonUtils.getEntityList(response, 'backupServers', 'backupServer').each { backupServer ->
						def row = new LinkedHashMap((Map) backupServer)
						row.externalId = row.uid
						rtn.backupServers << row
					}
					//paging
					def nextPage = getNextPage(response)
					if(nextPage) {
						query.page = nextPage.toString()
						keepGoing = true
					} else {
						keepGoing = false
						// we've iterated all pages successfully
						rtn.success = true
					}
				} else {
					keepGoing = false
				}
			}
		} else {
			//return token errors?
		}

		return rtn
	}

	static loadBackupJob(Map authConfig, String jobId, Map opts) {
		def rtn = [success:false, job:null, data:null]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def apiPath = authConfig.basePath + '/jobs/' + jobId
			def headers = buildHeaders([:], tokenResults.token)
			def query = [format:'Entity']
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
			HttpApiClient httpApiClient = new HttpApiClient()
			def results = httpApiClient.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'GET')
			if(results.success == true) {
				rtn.data = results.data
				rtn.job = JsonUtils.normalizeMap(results.data)
				rtn.success = true
			}
		} else {
			//return token errors?
		}
		return rtn
	}

	static getBackupRepositories(Map authConfig, Map opts=[:]) {
		def rtn = [success:false, repositories:[]]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def apiPath = authConfig.basePath + '/repositories'
			def headers = buildHeaders([:], tokenResults.token)
			def page = '1'
			def perPage = '50'
			def query = [format:'Entity', pageSize:perPage, page:page]
			if(opts.backupType)
				query.filter = 'Platform==' + opts.backupType
			def keepGoing = true
			while(keepGoing) {
				HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
				HttpApiClient httpApiClient = new HttpApiClient()
				def results = httpApiClient.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'GET')
				if(results.success == true) {
					//iterate results
					def response = JsonUtils.normalize(results.data)
					JsonUtils.getEntityList(response, 'repositories', 'repository').each { repository ->
						def row = new LinkedHashMap((Map) repository)
						row.externalId = row.uid
						rtn.repositories << row
					}
					//paging
					def nextPage = getNextPage(response)
					if(nextPage) {
						query.page = nextPage.toString()
						keepGoing = true
					} else {
						keepGoing = false
						// we've iterated all pages successfully
						rtn.success = true
					}
				} else {
					keepGoing = false
				}
			}
		} else {
			//return token errors?

		}
		return rtn
	}

	static getBackupJob(url, token, backupJobId){
		log.debug("getBackupJob: ${backupJobId}")
		def rtn = [success:false]
		def headers = buildHeaders([:], token)
		def query = [format: "Entity"]
		HttpApiClient httpApiClient = new HttpApiClient()
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
		def results = httpApiClient.callJsonApi(url, "/api/jobs/${backupJobId}", null, null, requestOpts, 'GET')
		rtn.results = results
		log.debug("got: ${results}")
		rtn.success = results?.success
		if(results.success) {
			def job = JsonUtils.normalizeMap(results.data)
			rtn.jobId = backupJobId
			rtn.jobName = job.name?.toString()
			rtn.scheduleEnabled = job.scheduleEnabled?.toString()
			rtn.scheduleConfigured = job.scheduleConfigured?.toString()
			rtn.scheduleCron = VeeamScheduleUtils.decodeScheduling(job)
		} else {
			rtn.content = results.content ?: results.data?.toString()
			rtn.data = results.data
			rtn.errorCode = results.errorCode
			rtn.headers = results.headers
		}

		return rtn
	}

	static getBackupJobJson(url, token, backupJobId){
		def rtn = [success:false]
		def headers = buildHeaders([:], token)
		def query = [format: "Entity"]
		HttpApiClient httpApiClient = new HttpApiClient()
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
		def results = httpApiClient.callJsonApi(url, "/api/jobs/${backupJobId}", requestOpts, 'GET')
		log.debug("getBackupJobJson got: ${results}")
		rtn.success = results?.success
		if(rtn.success) {
			rtn.data = results.data
		} else {
			rtn.content = results.content ?: results.data?.toString()
			rtn.data = results.data
			rtn.errorCode = results.errorCode
			rtn.headers = results.headers
		}
		return rtn
	}

	static updateBackupJob(String url, String token, String backupJobId, Map config) {
		def headers = buildHeaders([:], token)
		def query = [format: "Entity"]
		def body = config
		HttpApiClient httpApiClient = new HttpApiClient()
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query, body:body)
		def results = httpApiClient.callJsonApi(url, "/api/jobs/${backupJobId}", requestOpts, 'PUT')
		log.debug("updateBackupJob results: ${results}")

		return results
	}

	static getBackupJobBackups(url, token, backupJobId){
		def rtn = [success:false]
		def headers = buildHeaders([:], token)
		def query = [format:'Entity']
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
		HttpApiClient httpApiClient = new HttpApiClient()
		def results = httpApiClient.callJsonApi(url, "/api/jobs/${backupJobId}/includes", null, null, requestOpts, 'GET')
		def response = JsonUtils.normalize(results.data)
		rtn.taskId = JsonUtils.get(response, 'taskId')
		rtn.data = []
		JsonUtils.getEntityList(response, 'objectInJobs', 'objectInJob').each { vmInJob ->
			rtn.data << [objectId: vmInJob.objectInJobId, objectRef: vmInJob.hierarchyObjRef, name: vmInJob.name]
		}
		rtn.success = results?.success
		return rtn
	}

	static cloneBackupJob(Map authConfig, String cloneId, Map opts) {
		log.info("cloneBackupJob: ${opts}")
		def rtn = [success:false, jobId:null, data:null]
		def tokenResults = getToken(authConfig)
		log.debug("cloneBackupJob tokenResults: ${tokenResults}")
		if(tokenResults.success == true) {
			def sourceJob = getBackupJobJson(authConfig.apiUrl, tokenResults.token, cloneId)?.data
			def apiPath = authConfig.basePath + '/jobs/' + cloneId
			def headers = buildHeaders([:], tokenResults.token)
			def query = [action:'clone']
			def jobName = opts.jobName
			def repositoryId = opts.repositoryId
			// request bodies remain PascalCase, only the response representation changed casing in Veeam 13
			def requestBody = [
				BackupJobCloneInfo: [
					JobName: jobName,
					RepositoryUid: repositoryId
				]
			]
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query, body:requestBody)
			HttpApiClient httpApiClient = new HttpApiClient()
			log.debug("requestOpts: ${requestOpts}")
			def results = httpApiClient.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'POST')
			log.debug("clone results: ${results}")
			if(results.success == true) {
				rtn.data = results.data
				def taskId = JsonUtils.getValue(results.data, 'TaskId')?.toString()
				if(taskId) {
					def taskResults = waitForTask(authConfig, taskId)
					log.debug("taskResults: ${taskResults}")
					if(taskResults.success == true) {
						//find the job link
						def clonedJobInfo = null
						def jobLink = taskResults.links?.find{ it.type == 'Job' }
						if(jobLink) {
							rtn.jobId = VeeamUtils.parseEntityId(jobLink.href)
							clonedJobInfo = getBackupJobJson(authConfig.apiUrl, tokenResults.token, rtn.jobId)?.data
							rtn.scheduleCron = VeeamScheduleUtils.decodeScheduling(JsonUtils.normalizeMap(clonedJobInfo))
							rtn.success = true
						} else {
							rtn.msg = taskResults.msg
							log.error("Error cloning job: ${taskResults.msg}")
						}

						// copy retention info to the new job (not done by the job clone API)
						def sourceJobInfo = JsonUtils.getValue(sourceJob, 'JobInfo')
						def clonedJobInfoDetail = JsonUtils.getValue(clonedJobInfo, 'JobInfo')
						if(sourceJobInfo && clonedJobInfoDetail) {
							JsonUtils.setValue(clonedJobInfoDetail, 'SimpleRetentionPolicy', JsonUtils.getValue(sourceJobInfo, 'SimpleRetentionPolicy'))
							updateBackupJob(authConfig.apiUrl, tokenResults.token, rtn.jobId, clonedJobInfo)
						}

						// ensure the job is enabled if the source job was enabled
						if(JsonUtils.getValue(sourceJob, 'ScheduleEnabled')?.toString() == "true" && rtn.jobId) {
							// The only way I found to ensure a job is enabled after cloning is to first
							// disable it and then enable it.
							def disableScheduleResults = disableBackupJobSchedule(authConfig.apiUrl, tokenResults.token, rtn.jobId)
							if(disableScheduleResults.taskId) {
								waitForTask(authConfig, disableScheduleResults.taskId)
							}
						}
					} else {
						rtn.msg = taskResults.msg
						log.error("Error cloning job: ${taskResults.msg}")
					}
				}
			}
		}
		return rtn
	}

	//vm utils
	static findVm(Map authConfig, List vmObjectRefs, Map opts) {
		log.debug("findVm, vmObjectRefs: ${vmObjectRefs}, opts: ${opts}")
		def rtn = [success:false, vmId:null]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			//search all servers
			vmObjectRefs?.each { vmObjectRef ->
				if(rtn.success != true) {
					def results = lookupVm(authConfig.apiUrl, tokenResults.token, vmObjectRef)
					//check results
					log.debug("findVm results: ${results}")
					if(results.success == true) {
						rtn.vmId = results.vmId
						rtn.vmName = results.vmName
						rtn.success = true
					}
				}
			}
		}

		return rtn
	}

	/**
	 * This method waits for a VM to be available in the Veeam backup server.
	 * It continuously checks for the VM's availability until a maximum number of attempts is reached.
	 *
	 * @param authConfig A map containing the authentication configuration for the Veeam server.
	 *                   This includes the API URL, base path, and the token.
	 * @param vmExternalId The external ID of the VM to wait for.
	 * @param managedServers A list of managed servers in the Veeam backup server that the VM could be associated with.
	 * @param maxWaitTime The maximum time (in seconds) to wait for the VM to be available.
	 *                    The method will stop checking after this time has passed.
	 * @param opts Additional options for the method. This can include various configuration parameters.
	 * @return A map containing the result of the operation.
	 *         This includes a success flag and the VM ID if the VM was found.
	 */
	static waitForVm(Map authConfig, List vmObjectRefs, Long maxWaitTime = 300, Map opts) {
		def rtn = [success:false, error:false, data:null, vmId:null]
		def attempt = 0
		def keepGoing = true
		def maxAttempts = (maxWaitTime * 1000l) / taskSleepInterval
		while(keepGoing == true && attempt < maxAttempts) {
			//load the vm
			def results = findVm(authConfig, vmObjectRefs, opts)
			if(results.success == true) {
				rtn.success = true
				rtn.data = results.data
				rtn.vmId = results.vmId
				rtn.vmName = results.vmName
				rtn.hierarchyRoot = results.hierarchyRoot
				keepGoing = false
			} else {
				attempt++
				sleep(taskSleepInterval)
			}


		}
		return rtn
	}

	//backup
	static createBackupJobBackup(Map authConfig, String jobId, vmId, vmName, Map opts) {
		log.debug("createBackupJobBackup: ${opts}")
		def rtn = [success:false, backupId:null, data:null]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def apiPath = authConfig.basePath + '/jobs/' + jobId + '/includes'
			def headers = buildHeaders([:], tokenResults.token)
			def query = [format:'Entity']
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
			HttpApiClient httpApiClient = new HttpApiClient()

			//load up the existing job
			def results = httpApiClient.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'GET')
			log.info("job results: ${results}")
			if(results.success == true) {
				//save off the existing
				def existingItems = []
				def processingOpts
				def response = JsonUtils.normalize(results.data)
				JsonUtils.getEntityList(response, 'objectInJobs', 'objectInJob').each { vmInJob ->
					if(vmInJob.objectInJobId) {
						existingItems << vmInJob.objectInJobId.toString()
						if(processingOpts == null)
							processingOpts = vmInJob.guestProcessingOptions
					}
				}
				//add new
				// request bodies remain PascalCase, only the response representation changed casing in Veeam 13
				def requestBody = [
					HierarchyObjRef: vmId,
					HierarchyObjName: vmName
				]
				//add it
				log.debug("requestOpts: ${requestOpts}")
				requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query, body: requestBody)
				def addResults = httpApiClient.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'POST')
				log.debug("addResults: ${addResults}")
				if(addResults.success == true) {
					//get task id and wait
					def taskId = JsonUtils.getValue(addResults.data, 'TaskId')?.toString()
					log.debug("taskId: ${taskId}")
					if(taskId) {
						def taskResults = waitForTask(authConfig, taskId)
						if(taskResults.success == true) {
							rtn.success = true
							log.debug("taskResults: ${taskResults}")
							//get the backup id?
							//remove existing?
							if(opts.removeJobs == true) {
								requestOpts = new HttpApiClient.RequestOptions(headers:headers)
								existingItems.each { existingId ->
									def itemPath = apiPath + '/' + existingId
									def deleteResults = httpApiClient.callJsonApi(authConfig.apiUrl, itemPath, null, null, requestOpts, 'DELETE')
									def deleteTaskId = JsonUtils.getValue(deleteResults.data, 'TaskId')?.toString()
									if(deleteTaskId) {
										def deleteTaskResults = waitForTask(authConfig, deleteTaskId)
										log.debug("deleteResults: ${deleteResults}")
									}
								}
							}
							//get the job and id
							def jobObject
							def jobDetailAttempts = 0
							def maxJobDetailAttempts = 10
							while(!jobObject && jobDetailAttempts < maxJobDetailAttempts) {
								def jobResults = loadBackupJob(authConfig, jobId, opts)
								if(jobResults.success == true) {
									def includes = JsonUtils.get(jobResults.job, 'jobInfo', 'backupJobInfo', 'includes')
									jobObject = JsonUtils.getList(includes, 'objectInJobs', 'objectInJob').find {
										if(opts.externalId) {
											def itMor = VeeamUtils.extractVmIdFromObjectRef(it.hierarchyObjRef?.toString())
											def itUid = VeeamUtils.extractVeeamUuid(it.hierarchyObjRef?.toString())
											return opts.externalId == itMor || opts.externalId.contains(itUid)
										} else {
											return vmName == it.name
										}
									}
									if(rtn.backupId == null && jobObject) {
										log.debug("JOB OBJECT WAS FOUND")
										rtn.backupId = jobObject.objectInJobId
										rtn.objectId = jobResults.job.uid
										rtn.success = true
									}

								} else {
									//couldn't find the job
								}
								if(!jobObject) {
									log.debug("DIDN'T FIND THE JOB OBJECT WE WERE LOOKING FOR")
									jobDetailAttempts++
									sleep(3000)
								}
							}
						} else {
							//error waiting for task to finish
							rtn.msg = taskResults.msg ?: "Failed to find backup creation task in Veeam."
						}
					} else {
						// failed to create task, no task ID returned
						rtn.msg = "Failed to create request for backup creation in Veeam."
						log.error("Failed to create back up include in backup job, task ID not found in API response: ${addResults}")
					}
				} else {
					//error adding to job
					rtn.msg = "Failed to create backup job."
					log.error("Failed to create backup job: ${addResults}")
				}
			} else {
				rtn.msg = "Unable to load details for job ${jobId}."
				log.error("Failed to load job details: ${results}")
			}

		}
		return rtn
	}

	static startBackupJob(Map authConfig, jobId, opts=[:]){
		log.debug "startBackupJob: ${jobId}"
		def rtn = [success:false]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def taskId
			def apiPath = authConfig.basePath + '/jobs/' + VeeamUtils.extractVeeamUuid(jobId.toString())
			def headers = buildHeaders([:], tokenResults.token)
			def query = [action:'start']
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
			HttpApiClient httpApiClient = new HttpApiClient()
			def results = httpApiClient.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'POST')
			log.debug("veeam backup start request got: ${results}")
			def jobStartDate = results.headers?.Date
			rtn.success = results?.success
			if (results?.success == true) {
				taskId = JsonUtils.normalizeMap(results.data).taskId
			}
			if (taskId) {
				def taskResults = waitForTask(authConfig, taskId, ['Finished'])
				rtn.success = taskResults?.success
				if(taskResults.success && !taskResults.error) {
					log.debug("backup job task results: ${taskResults}")
					def jobSessionLink = taskResults.links.find { it.type == "BackupJobSession"}?.href
					if(jobSessionLink) {
						rtn.backupSessionId = VeeamUtils.extractVeeamUuid(jobSessionLink)
					} else {
						if(jobStartDate instanceof String) {
							def tmpJobStartDate = DateUtility.parseDate(jobStartDate)
							jobStartDate = DateUtility.formatDate(tmpJobStartDate)
						}
						def backupResult = getLastBackupResult(authConfig, jobId, opts + [startRefDateStr: jobStartDate])
						log.info("got backup result - " + backupResult)
						rtn.backupSessionId = backupResult.backupResult?.backupSessionId
					}
				} else{
					rtn.success = false
					rtn.errorMsg = getTaskErrorMessage(taskResults)
				}
			}
		}
		return rtn
	}

	static stopBackupJob(url, token, backupJobId){
		def rtn = [success:false]
		def taskId = ""
		def headers = buildHeaders([:], token)
		def query = [action:'stop']
		HttpApiClient httpApiClient = new HttpApiClient()
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
		def results = httpApiClient.callJsonApi(url, "/api/jobs/${backupJobId}", null, null, requestOpts, 'POST')
		log.debug("got: ${results}")
		rtn.success = results?.success
		if(results?.success == true) {
			rtn.taskId = JsonUtils.getValue(results.data, 'TaskId')?.toString()
		} else if(results?.errorCode?.toString() == "404") {
			rtn.success = true
		}
		return rtn
	}

	static startQuickBackup(Map authConfig, String backupServerId, String vmId, Map opts = [:]){
		log.debug "startQuickBackup - backupServerId: ${backupServerId}, vmId: ${vmId}"
		def rtn = [success:false]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def taskId
			def apiPath = authConfig.basePath + "/backupServers/${backupServerId}"
			def headers = buildHeaders([:], tokenResults.token)
			def query = [action:'quickbackup']
			// request bodies remain PascalCase, only the response representation changed casing in Veeam 13
			def body = [VmRef: vmId]
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query, body: body)
			HttpApiClient httpApiClient = new HttpApiClient()
			def results = httpApiClient.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'POST')
			log.debug("veeam quick backup start request got: ${results}")
			def backupStartDate = results.headers?.Date
			rtn.success = results?.success
			if (results?.success == true) {
				taskId = JsonUtils.normalizeMap(results.data).taskId
			}
			if (taskId) {
				def taskResults = waitForTask(authConfig, taskId, ['Finished'])
				rtn.success = taskResults?.success
				if(taskResults.success && !taskResults.error) {
					log.debug("quick backup task results: ${taskResults}")
					def jobSessionLink = taskResults.links.find { it.type == "BackupJobSession"}?.href
					if(jobSessionLink) {
						rtn.data = [
							backupSessionId: VeeamUtils.extractVeeamUuid(jobSessionLink),
							startDate: backupStartDate
						]
					} else {
						rtn.success = false
						rtn.errorMsg = "Job session ID not found in Veeam task results."
					}
				} else{
					rtn.success = false
					rtn.status = "FAILED"
					rtn.errorMsg = getTaskErrorMessage(taskResults)
				}
			}
		}
		return rtn
	}

	static startVeeamZip(Map authConfig, String backupServerId, String repositoryId, String vmId, Map opts = [:]){
		log.debug "startVeeamZip - backupServerId: ${backupServerId}, RepositoryId: ${repositoryId}, vmId: ${vmId}"
		def rtn = [success:false]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def taskId
			def apiPath = authConfig.basePath + "/backupServers/${backupServerId}"
			def headers = buildHeaders([:], tokenResults.token)
			def query = [action:'veeamzip']
			// request bodies remain PascalCase, only the response representation changed casing in Veeam 13
			def body = [
				VmRef: vmId,
				RepositoryUid: repositoryId,
				BackupRetention: "Never",
				CompressionLevel: 3
			]
			if(opts.vmwToolsInstalled) {
				// doesn't work well with vmware tools quiescensce
				body.DisableGuestQuiescence = true
			}
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query, body: body)
			HttpApiClient httpApiClient = new HttpApiClient()
			def results = httpApiClient.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'POST')
			log.debug("veeamzip backup start request got: ${results}")
			def backupStartDate = results.headers?.Date
			log.debug("backupStartDate: ${backupStartDate}")
			rtn.success = results?.success
			if (results?.success == true) {
				taskId = JsonUtils.normalizeMap(results.data).taskId
			}
			log.debug("taskId: $taskId")
			if (taskId) {
				def taskResults = waitForTask(authConfig, taskId, ['Finished'])
				rtn.success = taskResults?.success
				if(taskResults.success && !taskResults.error) {
					log.debug("veeamzip task results: ${taskResults}")
					def jobSessionLink = taskResults.links.find { it.type == "BackupJobSession"}?.href
					log.debug("jobSessionLink: ${jobSessionLink}")
					if(jobSessionLink) {
						rtn.data = [
							backupSessionId: VeeamUtils.extractVeeamUuid(jobSessionLink),
							startDate: backupStartDate
						]
					} else {
						rtn.success = false
						rtn.errorMsg = "Job session ID not found in Veeam task results."
					}
				} else{
					rtn.success = false
					rtn.status = "FAILED"
					rtn.errorMsg = getTaskErrorMessage(taskResults)
				}
			}
		}
		return rtn
	}


	//not supported by API
	static deleteBackupJob(url, token, backupJobId) {
		def rtn = [success:false, data:[:]]
		try {
			def headers = buildHeaders([:], token)
			HttpApiClient httpApiClient = new HttpApiClient()
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers)
			def results = httpApiClient.callJsonApi(url, "/api/jobs/${backupJobId}", null, null, requestOpts, 'DELETE')
			log.debug("got: ${results}")
			rtn.success = results?.success
			if(results?.success == true) {
				rtn.data.taskId = JsonUtils.getValue(results.data, 'TaskId')?.toString()
			} else if(results.errorCode?.toString() == "400") {
				rtn.success = true
			}
		} catch(Exception e) {
			log.error("deleteBackupJob error: {}", e, e)
		}

		return rtn
	}

	//turn off backup schedule
	static disableBackupJobSchedule(url, token, backupJobId){
		def rtn = [success:false]
		def backupJob = getBackupJob(url, token, backupJobId)
		if(backupJob.scheduleEnabled == "false" && backupJob.scheduleConfigured == "false"){
			//schedule is already off
			rtn.success = true
			return rtn
		}

		// the job entity is fetched and sent back with the schedule turned off, so the document is modified in
		// place rather than normalized to preserve the property casing Veeam expects on the request body
		def body = backupJob.results?.data
		if(!(body instanceof Map)) {
			rtn.msg = "Unable to load backup job ${backupJobId}"
			return rtn
		}
		JsonUtils.setValue(body, 'ScheduleConfigured', false)
		JsonUtils.setValue(body, 'ScheduleEnabled', false)
		JsonUtils.setValue(body, 'JobScheduleOptions', [:])

		def headers = buildHeaders([:], token)
		def query = [format: "Entity"]
		HttpApiClient httpApiClient = new HttpApiClient()
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query, body: body)
		def results = httpApiClient.callJsonApi(url, "/api/jobs/${backupJobId}", null, null, requestOpts, 'PUT')
		log.debug("disableBackupJobSchedule got: ${results}")
		rtn.success = results?.success
		if(results?.success == true) {
			rtn.taskId = JsonUtils.getValue(results.data, 'TaskId')?.toString()
		}
		return rtn
	}

	static removeVmFromBackupJob(url, token, backupJobId, vmId) {
		def rtn = [success:false]
		def headers = buildHeaders([:], token)
		def query = [format:'Entity']
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
		HttpApiClient httpApiClient = new HttpApiClient()
		def apiPath = '/api/jobs/' + backupJobId + '/includes/' + vmId
		def results = httpApiClient.callJsonApi(url, apiPath, null, null, requestOpts, 'DELETE')
		rtn.taskId = JsonUtils.getValue(results.data, 'TaskId')?.toString()
		rtn.success = results?.success
		rtn.data = results.data
		log.debug "remove vm results ${results}"
		return rtn
	}

	static getBackupResult(url, token, backupSessionId) {
		def rtn = [success:false]
		def backupResult = [:]
		def headers = buildHeaders([:], token, [format:"json"])
		def query = [format: "Entity"]
		HttpApiClient httpApiClient = new HttpApiClient()
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
		def results = httpApiClient.callJsonApi(url, "/api/backupSessions/${backupSessionId}", requestOpts, 'GET')
		log.debug("getBackupResult got: ${results}")
		rtn.success = results?.success
		if(results?.success == true) {
			def session = JsonUtils.normalizeMap(results.data)
			backupResult = [
				backupSessionId: backupSessionId,
				backupJobName: session.jobName?.toString(),
				startTime: session.creationTimeUTC?.toString(),
				endTime: session.endTimeUTC?.toString(),
				state: session.state?.toString(),
				result: session.result?.toString(),
				progress: session.progress?.toString(),
				links: JsonUtils.getLinks(session)
			]
			log.debug("getBackupResult Links: ${backupResult.links}")
			if(backupResult.result == "Success" || backupResult.result == "Warning"){
				def stats = getBackupResultStats(url, token, backupSessionId)
				backupResult.totalSize = stats?.totalSize ?: 0
			}
		}
		rtn.result = backupResult
		return rtn
	}

	static getBackupResults(url, token, backupJobId) {
		def rtn = [success:false]
		def backupJobUid = "urn:veeam:Job:${backupJobId}".toString()
		def backupResults = []
		def headers = buildHeaders([:], token)
		def query = [type:'backupJobSession', filter:"jobUid==\"${backupJobUid}\"", format:'entities']
		HttpApiClient httpApiClient = new HttpApiClient()
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
		def results = httpApiClient.callJsonApi(url, "/api/query", null, null, requestOpts, 'GET')
		log.debug("got: ${results}")
		rtn.success = results?.success
		if(results?.success == true) {
			def response = JsonUtils.normalize(results.data)
			def entities = JsonUtils.get(response, 'entities')
			JsonUtils.getList(entities, 'backupJobSessions', 'backupJobSession').each { backupJobSession ->
				def backupJobSessionUid = backupJobSession.uid.toString()
				def backupJobSessionId = backupJobSessionUid.substring(backupJobSessionUid.lastIndexOf(":")+1)
				def jobName = backupJobSession.jobName?.toString()
				def startTime = backupJobSession.creationTimeUTC?.toString()
				def endTime = backupJobSession.endTimeUTC?.toString()
				def state = backupJobSession.state?.toString()
				def result = backupJobSession.result?.toString()
				def progress = backupJobSession.progress?.toString()
				def backupResult = [backupSessionId: backupJobSessionId, backupJobName: jobName, startTime: startTime, endTime: endTime, state: state, result: result, progress: progress]
				if(result == "Success" || result == "Warning"){
					def stats = getBackupResultStats(url, token, backupJobSessionId)
					backupResult.totalSize = stats?.totalSize ?: 0
				}
				backupResults << backupResult
			}
		}
		rtn.results = backupResults
		return rtn
	}

	static getLastBackupResult(Map authConfig, backupJobId, opts=[:]) {
		log.debug "getLastBackupResult: ${backupJobId}"
		def rtn = [success:false]
		def backupResult
		def backupJobUid = "urn:veeam:Job:${VeeamUtils.extractVeeamUuid(backupJobId)}"
		//get hiearchy	 root for the VM cloud
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def apiPath = authConfig.basePath + '/query'
			def headers = buildHeaders([:], tokenResults.token)
			def queryFilter = "jobUid==\"${backupJobUid}\""
			if(opts.startRefDateStr) {
				queryFilter += ";CreationTime>=\"${opts.startRefDateStr}\""
			}
			def query = [type: 'backupJobSession', filter: queryFilter, format: 'entities', sortDesc: 'CreationTime', pageSize: '1' ]
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers: headers, queryParams: query)
			HttpApiClient httpApiClient = new HttpApiClient()
			log.info("getLastBackupResult query: ${query}")
			def attempt = 0
			def keepGoing = true
			def backupJobSession
			while(keepGoing == true && attempt < maxTaskAttempts) {
				def results = httpApiClient.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'GET')
				rtn.success = results?.success
				if(rtn.success == true) {
					def response = JsonUtils.normalize(results.data)
					def entities = JsonUtils.get(response, 'entities')
					def tmpJobSession = JsonUtils.getList(entities, 'backupJobSessions', 'backupJobSession')?.getAt(0)
					if(tmpJobSession) {
						backupJobSession = tmpJobSession
						def tmpJobSessionUid = tmpJobSession.uid.toString()
						def tmpBackupSessionId = tmpJobSessionUid.substring(tmpJobSessionUid.lastIndexOf(":")+1)
						if(!opts.lastBackupSessionId || tmpBackupSessionId != opts.lastBackupSessionId) {
							keepGoing = false
						}
					}

				} else {
					keepGoing = false
					return rtn
				}
				sleep(taskSleepInterval)
				attempt++
			}

			if(backupJobSession) {
				def backupJobSessionUid = backupJobSession.uid.toString()
				def backupJobSessionId = backupJobSessionUid.substring(backupJobSessionUid.lastIndexOf(":") + 1)
				def jobName = backupJobSession.jobName?.toString()
				def startTime = backupJobSession.creationTimeUTC?.toString()
				def endTime = backupJobSession.endTimeUTC?.toString()
				def state = backupJobSession.state?.toString()
				def result = backupJobSession.result?.toString()
				def progress = backupJobSession.progress?.toString()
				backupResult = [backupSessionId: backupJobSessionId, backupJobName: jobName, startTime: startTime, endTime: endTime, state: state, result: result, progress: progress]
			}
			rtn.backupResult = backupResult
		}

		return rtn
	}

	static getBackupSessionTaskSessions(String url, String token, String backupSessionId) {
		def rtn = [success:false]
		String apiPath = "/api/backupSessions/${backupSessionId}/taskSessions"
		Map headers = buildJsonHeaders([:], token)
		Map query = [format: "Entity"]
		HttpApiClient httpApiClient = new HttpApiClient()
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
		rtn = httpApiClient.callJsonApi(url, apiPath, requestOpts, 'GET')

		return rtn
	}

	static getBackupSession(Map authConfig, String backupSessionId) {
		def rtn = [success:false]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def headers = buildHeaders([:], tokenResults.token)
			def query = [format: "Entity"]
			HttpApiClient httpApiClient = new HttpApiClient()
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
			rtn = httpApiClient.callJsonApi(authConfig.apiUrl, "/api/backupSessions/${backupSessionId}", requestOpts, 'GET')
			rtn.data = JsonUtils.normalize(rtn.data)
		}
		return rtn
	}

	static getRestorePoint(Map authConfig, String objectRef, Map opts=[:]) {
		log.debug "getLatestRestorePoint: ${objectRef}"
		def rtn = [success:false, data: [:]]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def headers = buildHeaders([:], tokenResults.token)
			def queryFilter = "HierarchyObjRef==\"${objectRef}\""
			if(opts.startDate) {
				def formattedStartDate= DateUtility.formatDate(opts.startDate)
				queryFilter += ";CreationTime>=\"${formattedStartDate}\""
			}
			log.debug("getRestorePoint queryFilter: ${queryFilter}")
				def query = [type: 'VmRestorePoint', filter: queryFilter, format: 'Entities', sortDesc: 'CreationTime', pageSize: "1" ]

			def attempt = 0
			def keepGoing = true
			while(keepGoing == true && attempt < maxTaskAttempts) {
				HttpApiClient httpApiClient = new HttpApiClient()
				HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
				def restorePointsResults = httpApiClient.callJsonApi(authConfig.apiUrl, "/api/query", null, null, requestOpts, 'GET')
				if(restorePointsResults.success) {
					def restorePointsResponse = JsonUtils.normalize(restorePointsResults.data)
					def entities = JsonUtils.get(restorePointsResponse, 'entities')
					def restoreRef = JsonUtils.getList(entities, 'vmRestorePoints', 'vmRestorePoint')?.getAt(0)
					if(restoreRef) {
						rtn.data.externalId = restoreRef.uid?.toString()
						rtn.success = true
						keepGoing = false
					}
				} else {
					keepGoing = false
					return rtn
				}
				sleep(taskSleepInterval)
				attempt++
			}
		}

		return rtn
	}

	static getVmRestorePointsFromRestorePointId(Map authConfig, String restorePointId, Map opts=[:]) {
		log.debug "getVmRestorePointsFromRestorePointId: ${restorePointId}"
		def rtn = [success:false, data: [:]]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def headers = buildHeaders([:], tokenResults.token)
			def query = [format: "Entity"]
			HttpApiClient httpApiClient = new HttpApiClient()
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
			rtn = httpApiClient.callJsonApi(authConfig.apiUrl, "/api/restorePoints/${restorePointId}/vmRestorePoints", requestOpts, 'GET')
			rtn.data = JsonUtils.normalize(rtn.data)
		}
		return rtn
	}

	static getRestorePointFromRestorePointId(Map authConfig, String restorePointId) {
		log.debug "getRestorePointFromRestorePointId: ${restorePointId}"
		def rtn = [success:false, data: [:]]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def headers = buildHeaders([:], tokenResults.token)
			def query = [format: "Entity"]
			HttpApiClient httpApiClient = new HttpApiClient()
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
			rtn = httpApiClient.callJsonApi(authConfig.apiUrl, "/api/vmRestorePoints/${restorePointId}", requestOpts, 'GET')
			rtn.data = JsonUtils.normalize(rtn.data)
		}
		return rtn
	}

	static getRestoreResult(url, token, restoreSessionId) {
		log.debug("getRestoreResult: ${restoreSessionId}, url: ${url}, token: ${token}")
		def rtn = [success:false]
		def restoreResult = [:]
		def headers = buildHeaders([:], token)
		def query = [format: "Entity"]
		HttpApiClient httpApiClient = new HttpApiClient()
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
		def results = httpApiClient.callJsonApi(url, "/api/restoreSessions/${restoreSessionId}", requestOpts, 'GET')
		log.debug("getRestoreResult results: ${results}")
		rtn.success = results?.success
		if(results?.success == true) {
			def response = JsonUtils.normalizeMap(results.data)
			def startTime = response.creationTimeUTC?.toString()
			def endTime = response.endTimeUTC?.toString()
			def state = response.state?.toString()
			def result = response.result?.toString()
			def progress = response.progress?.toString()
			def vmId
			def vmRef = response.restoredObjRef?.toString() ?: ''
			if(vmRef && vmRef != ""){
				vmId = vmRef?.substring(vmRef?.lastIndexOf(".")+1)
			}
			restoreResult = [restoreSessionId: restoreSessionId, vmId:vmId, startTime: startTime, endTime: endTime, state: state, result: result, progress: progress]
		}
		rtn.result = restoreResult
		return rtn
	}

	//lookup backup task sessions for backup size
	static getBackupResultStats(url, token, backupJobSessionId){
		def rtn = [success:false]
		rtn.totalSize = 0
		def headers = buildHeaders([:], token)
		def query = [format: "Entity"]
		HttpApiClient httpApiClient = new HttpApiClient()
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
		def results = httpApiClient.callJsonApi(url, "/api/backupSessions/${backupJobSessionId}/taskSessions", null, null, requestOpts, 'GET')
		log.debug("got: ${results}")
		rtn.success = results?.success
		if(rtn.success == true) {
			def response = JsonUtils.normalize(results.data)
			JsonUtils.getList(response, 'backupTaskSessions', 'backupTaskSession').each { backupTaskSession ->
				rtn.totalSize += (JsonUtils.toLong(backupTaskSession.totalSize) ?: 0l)
			}
		}
		return rtn
	}

	// this should just take a restore point or a restore endpoint URL, finding the restore info can go in the restore execution service
	static restoreVM(String url, String token, String restorePath, Map restoreSpec, opts=[:]) {
		log.debug("restoreVM: ${url}, ${restorePath}, ${restoreSpec} ${opts}")
		def rtn = [success:false]
		def headers = buildHeaders([:], token)
		def query = [format: "Entity"]
		def restoreTaskId

		// initiate the restore
		if(restorePath) {
			log.debug("Performing restore with endpoint: ${restorePath}")
			def body = restoreSpec
			log.debug("body: ${body}")
			def restoreQuery = query + [action: 'restore']
			HttpApiClient httpApiClient = new HttpApiClient()
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams:restoreQuery, body: body)
			def results = httpApiClient.callJsonApi(url, restorePath, requestOpts, 'POST')
			rtn.success = results?.success
			if(rtn.success == true) {
				//get the restore session id
				restoreTaskId = JsonUtils.getValue(results.data, 'TaskId')
			}
		} else if(!rtn.msg) {
			log.debug("Unable to perform restore, no restore link found.")
			rtn.msg = "Veeam restore link not found"
			rtn.success = false
			log.error(rtn.msg)
		}


		// get the restore task details
		if(restoreTaskId) {
			def restoreTaskResults = waitForTask([token: token, apiUrl: url, basePath:'/api'], restoreTaskId.toString())
			rtn.success = restoreTaskResults?.success
			if(rtn.success == true) {
				restoreTaskResults.links.each{ link ->
					if(link.type == "RestoreSession"){
						def restoreSessionUrl =  new URL(link.href?.toString())
						def restoreSessionId = restoreSessionUrl.path.substring(restoreSessionUrl.path.lastIndexOf("/")+1)
						rtn.restoreSessionId = restoreSessionId
					}
				}
			} else {
				rtn.success = false
				rtn.msg = restoreTaskResults.msg
			}
		}
		return rtn
	}

	//lookup the veeam VM ID by searching a single hierarchy root for the VM name
	static lookupVmByName(url, token, managedServerId, vmName) {
		def rtn = [success:false]
		rtn = getVmIdByName(url, token, managedServerId, vmName)
		if(!rtn.vmId) {
			log.error("Failed to find VM object in Veeam: ${vmName}")
		}
		return rtn
	}

	//get the veeam VM ID given the veeam managed server ID (hiearchy root) and VM name
	static getVmIdByName(url, token, managedServerId, vmName) {
		def rtn = [success:false]
		//find the VM under the VM cloud
		def vmId
		if(managedServerId) {
			def hierarchyRoot = managedServerId.contains("HierarchyRoot") ? managedServerId : "urn:veeam:HierarchyRoot:${managedServerId}"
			def headers = buildHeaders([:], token)
			def query = [host: hierarchyRoot, name: vmName, type: 'Vm']
			HttpApiClient httpApiClient = new HttpApiClient()
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
			def results = httpApiClient.callJsonApi(url, "/api/lookup", null, null, requestOpts, 'GET')
			log.debug("got vmbyid results: ${results}")
			rtn.success = results?.success
			if(rtn.success == true) {
				def response = JsonUtils.normalize(results.data)
				def hierarchyItem = JsonUtils.getList(response, 'hierarchyItems', 'hierarchyItem')?.getAt(0)
				vmId = hierarchyItem?.objectRef?.toString()
				rtn.vmId = vmId
			}
		}
		return rtn
	}

	//lookup the veeam VM ID given the veeam managed server and the vmware VM ref ID
	static lookupVm(url, token, vmHierarchyRef) {
		def rtn = [success:false]
		rtn = getVmId(url, token, vmHierarchyRef)
		if(!rtn.vmId){
			log.error("Failed to find VM object in Veeam: ${vmHierarchyRef}")
		}
		return rtn
	}

	//get the veeam VM ID given the veeam managed server ID (hiearchy root) and vmware VM ref ID
	static getVmId(url, token, vmHierachyRef) {
		def rtn = [success:false]
		def headers = buildHeaders([:], token)
		def query = [hierarchyRef: vmHierachyRef]
		log.debug("getVmId query: ${query}")
		HttpApiClient httpApiClient = new HttpApiClient()
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
		def results = httpApiClient.callJsonApi(url, "/api/lookup", requestOpts, 'GET')
		log.debug("getVmId results: ${results}")
		rtn.success = results?.success
		if(rtn.success == true) {
			def response = JsonUtils.normalize(results.data)
			def hierarchyItem = JsonUtils.getList(response, 'hierarchyItems', 'hierarchyItem')?.getAt(0)
			rtn.vmId = hierarchyItem?.objectRef?.toString()
			rtn.vmName = hierarchyItem?.objectName?.toString()
		}
		return rtn
	}

	static fetchQuery(Map authConfig, String objType, Map filters, Boolean entityFormat=false, Map opts=[:]) {
		def rtn = [success:false]
		def apiPath = authConfig.basePath + '/query'
		def apiUrl = authConfig.apiUrl
		def headers = buildHeaders([:], authConfig.token)
		def query = [type: objType, filter:""]
		if(entityFormat) {
			query.format = 'entities'
		}
		for(filter in filters) {
			if(query.filter.size() > 0) {
				query.filter += "&"
			}
			query.filter += "${filter.key}==\"${filter.value}\""
		}

		HttpApiClient httpApiClient = new HttpApiClient()
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
		rtn = httpApiClient.callJsonApi(apiUrl, apiPath, requestOpts, 'GET')
		log.debug("fetchQuery results: ${rtn}")

		return rtn
	}

	//tasks
	static waitForTask(Map authConfig, String taskId, waitForState=['Finished']) {
		def rtn = [success:false, error:false, data:null, state:null, links:[]]
		def apiPath = authConfig.basePath + '/tasks/' + taskId
		def headers = buildHeaders([:], authConfig.token)
		def query = [format:'Entity']
		HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
		HttpApiClient httpApiClient = new HttpApiClient()
		def attempt = 0
		def keepGoing = true
		while(keepGoing == true && attempt < maxTaskAttempts) {
			//load the task
			def results = httpApiClient.callJsonApi(authConfig.apiUrl, apiPath, null, null, requestOpts, 'GET')
			//check results
			if(results?.success == true) {
				def taskData = JsonUtils.normalizeMap(results.data)
				def taskState = taskData.state?.toString()
				if(waitForState.contains(taskState)) {
					rtn.success = true
					rtn.data = taskData
					rtn.state = taskState
					keepGoing = false
					//parse results
					def taskSuccess = taskData.result?.success
					if(taskSuccess?.toString() == 'true') {
						JsonUtils.getLinks(taskData).each { link ->
							def linkType = link.type?.toString()
							def linkHref = link.href?.toString()
							if(linkType && linkHref)
								rtn.links << [type: linkType, href: linkHref]
						}
					} else if(taskSuccess?.toString() == 'false') {
						rtn.success = false
						def msg = taskData.result?.message?.toString()
						if(msg?.indexOf('not found') > -1 && attempt < 3) {
							//try again
							sleep(taskSleepInterval)
							keepGoing = true
						} else {
							rtn.msg = msg
							rtn.error = true
							keepGoing = false
						}
					}
				} else {
					sleep(taskSleepInterval)
				}
			} else if(results.errorCode?.toString() == "500") {
				def errorMessage
				try {
					errorMessage = JsonUtils.getValue(results.data, 'Message')?.toString()
				} catch (Exception ex1) {
					log.debug("unable to parse task error response: ${ex1.message}", ex1)
				}
				if(!errorMessage) {
					// if all else fails, just treat it as a string
					errorMessage = results.data?.toString()
				}
				if(errorMessage =~ /^.*?no\s.*?\stask\swith\sid/) {
					// "There is no backup task with id [task-297] in current rest session"
					// the task has completed and cleaned up???
					rtn.success = true
					rtn.error = true
				} else {
					rtn.msg = errorMessage
				}
				keepGoing = false
			} else {
				sleep(taskSleepInterval)
			}
			attempt++
		}
		return rtn
	}
	
	static callJsonApi(Map authConfig, String apiUri, String method='GET', Map opts=[:]) {
		log.debug "callJsonApi: ${apiUri}"
		def rtn = [success:false, data: [:]]
		def tokenResults = getToken(authConfig)
		if(tokenResults.success == true) {
			def uri = new URI(apiUri)
			def headers = buildHeaders([:], tokenResults.token, [format:'json'])
			def query = [format: "Entity"]
			HttpApiClient httpApiClient = new HttpApiClient()
			HttpApiClient.RequestOptions requestOpts = new HttpApiClient.RequestOptions(headers:headers, queryParams: query)
			rtn = httpApiClient.callJsonApi(authConfig.apiUrl, uri.path, requestOpts, method)
			rtn.data = JsonUtils.normalize(rtn.data)
		}
		return rtn
	}

	/**
	 * Extract the failure message from a task response returned by {@link #waitForTask}.
	 *
	 * @param taskResults the map returned by waitForTask
	 * @return the failure message or null
	 */
	static String getTaskErrorMessage(Map taskResults) {
		return taskResults?.msg ?: JsonUtils.get(taskResults?.data, 'result', 'message')?.toString()
	}

	/**
	 * Build the standard request headers for the Veeam Enterprise Manager REST API. The XML representation of this
	 * API is deprecated, so JSON is always requested.
	 *
	 * @param headers additional headers to merge in
	 * @param token the session token returned by the logon request
	 * @param opts unused, retained for call site compatibility
	 * @return the header map
	 */
	static buildHeaders(Map headers, String token, Map opts=[:]) {
		def rtn = [:]
		if(token) {
			rtn.'X-RestSvcSessionId' = token
		}
		rtn.Accept = 'application/json'
		rtn.'Content-Type' = 'application/json'
		// retain veeam 11 API functionality
		rtn.'x-api-version' = '1.0-rev2'

		return rtn + headers
	}

	static buildJsonHeaders(Map headers, String token, Map opts=[:]) {
		buildHeaders(headers, token, opts)
	}
}
