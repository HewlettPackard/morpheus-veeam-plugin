package com.morpheusdata.veeam.services

import spock.lang.Specification

class ApiServiceSpec extends Specification {

	void "findSessionToken reads the token returned by veeam"() {
		when:
		def token = ApiService.findSessionToken(['Content-Type': 'application/json', 'X-RestSvcSessionId': 'session-token'])

		then:
		token == 'session-token'
	}

	void "findSessionToken matches the header regardless of case"() {
		expect:
		ApiService.findSessionToken([(header): 'session-token']) == 'session-token'

		where:
		header << ['X-RestSvcSessionId', 'x-restsvcsessionid', 'X-RESTSVCSESSIONID', 'X-RestSvcSessionID']
	}

	void "findSessionToken returns null when veeam did not answer with a token"() {
		expect:
		ApiService.findSessionToken(headers) == null

		where:
		headers << [null, [:], ['Location': '/login'], ['Content-Type': 'text/html']]
	}
}
