package com.morpheusdata.veeam.utils

import spock.lang.Specification

class JsonUtilsSpec extends Specification {

	void "normalize rewrites veeam 12 pascal case keys to camel case"() {
		when:
		def result = JsonUtils.normalize([UID: 'urn:veeam:Job:1', JobScheduleOptions: [OptionsDaily: [Enabled: true]]])

		then:
		result.uid == 'urn:veeam:Job:1'
		result.jobScheduleOptions.optionsDaily.enabled == true
	}

	void "normalize leaves veeam 13 camel case keys untouched"() {
		when:
		def result = JsonUtils.normalize([uid: 'urn:veeam:Job:1', scheduleEnabled: true])

		then:
		result.uid == 'urn:veeam:Job:1'
		result.scheduleEnabled == true
	}

	void "normalize recurses into collections"() {
		when:
		def result = JsonUtils.normalize([Links: [[Type: 'BackupServerReference', Href: 'http://host/api/backupServers/1']]])

		then:
		result.links[0].type == 'BackupServerReference'
		result.links[0].href == 'http://host/api/backupServers/1'
	}

	void "getValue reads a property regardless of casing"() {
		expect:
		JsonUtils.getValue([TaskId: 'task-1'], 'TaskId') == 'task-1'
		JsonUtils.getValue([taskId: 'task-1'], 'TaskId') == 'task-1'
		JsonUtils.getValue([taskId: 'task-1'], 'taskid') == 'task-1'
		JsonUtils.getValue(null, 'TaskId') == null
	}

	void "setValue reuses the existing key so the document schema is preserved"() {
		given:
		def source = [ScheduleEnabled: true]

		when:
		JsonUtils.setValue(source, 'scheduleEnabled', false)

		then:
		source == [ScheduleEnabled: false]
	}

	void "setValue adds the property when it is not present"() {
		given:
		def source = [:]

		when:
		JsonUtils.setValue(source, 'ScheduleConfigured', false)

		then:
		source == [ScheduleConfigured: false]
	}

	void "get walks a nested path and tolerates missing segments"() {
		expect:
		JsonUtils.get([result: [message: 'boom']], 'result', 'message') == 'boom'
		JsonUtils.get([result: [:]], 'result', 'message') == null
		JsonUtils.get(null, 'result') == null
	}

	void "getEntityList handles the bare array collection shape"() {
		expect:
		JsonUtils.getEntityList([jobs: [[uid: 'a'], [uid: 'b']]], 'jobs', 'job')*.uid == ['a', 'b']
	}

	void "getEntityList handles the singular child wrapped collection shape"() {
		expect:
		JsonUtils.getEntityList([jobs: [job: [[uid: 'a'], [uid: 'b']]]], 'jobs', 'job')*.uid == ['a', 'b']
	}

	void "getEntityList wraps a single entity into a list"() {
		expect:
		JsonUtils.getEntityList([jobs: [job: [uid: 'a']]], 'jobs', 'job')*.uid == ['a']
	}

	void "getEntityList handles the same name wrapped collection shape used by the query endpoint"() {
		given:
		def response = JsonUtils.normalize([
			Entities: [BackupJobSessions: [BackupJobSessions: [[UID: 'urn:veeam:BackupJobSession:a']]]]
		])

		expect:
		JsonUtils.getList(JsonUtils.get(response, 'entities'), 'backupJobSessions', 'backupJobSession')*.uid == ['urn:veeam:BackupJobSession:a']
	}

	void "getList handles the same name wrapped collection shape used by the api root"() {
		given:
		def response = JsonUtils.normalize([SupportedVersions: [SupportedVersions: [[Name: 'v1_5'], [Name: 'v1_7']]]])

		expect:
		JsonUtils.getList(response, 'supportedVersions', 'supportedVersion')*.name == ['v1_5', 'v1_7']
	}

	void "getEntityList handles a root level collection"() {
		expect:
		JsonUtils.getEntityList([[uid: 'a']], 'jobs', 'job')*.uid == ['a']
	}

	void "getEntityList returns an empty list when the collection is absent"() {
		expect:
		JsonUtils.getEntityList([:], 'jobs', 'job') == []
		JsonUtils.getEntityList(null, 'jobs', 'job') == []
	}

	void "findLink matches on type or rel"() {
		given:
		def entity = [links: [[type: 'BackupServerReference', href: 'a'], [rel: 'Restore', href: 'b']]]

		expect:
		JsonUtils.findLink(entity, 'BackupServerReference').href == 'a'
		JsonUtils.findLink(entity, 'Restore').href == 'b'
		JsonUtils.findLink(entity, 'Missing') == null
	}

	void "findLink tolerates the legacy links dot link shape persisted in existing config maps"() {
		given:
		def entity = [links: [link: [[type: 'BackupServerReference', href: 'a']]]]

		expect:
		JsonUtils.findLink(entity, 'BackupServerReference').href == 'a'
	}

	void "findLink tolerates a legacy config map holding a single link"() {
		given: 'the xml representation collapsed a lone link into an element rather than a list'
		def entity = [links: [link: [type: 'BackupServerReference', href: 'a']]]

		expect:
		JsonUtils.findLink(entity, 'BackupServerReference').href == 'a'
	}

	void "toLong coerces json numbers and legacy strings"() {
		expect:
		JsonUtils.toLong(value) == expected

		where:
		value        || expected
		1024L        || 1024L
		1024         || 1024L
		1024.0d      || 1024L
		'1024'       || 1024L
		' 1024 '     || 1024L
		'1024.0'     || 1024L
		null         || null
		'not-a-size' || null
	}

	void "getCamelKeyName preserves identifier acronyms"() {
		expect:
		JsonUtils.getCamelKeyName('UID') == 'uid'
		JsonUtils.getCamelKeyName('ID') == 'id'
		JsonUtils.getCamelKeyName('TaskId') == 'taskId'
		JsonUtils.getCamelKeyName('CreationTimeUTC') == 'creationTimeUTC'
	}
}
